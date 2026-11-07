package social.tbone.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import social.tbone.nostr.NostrJson
import social.tbone.Tunables
import social.tbone.settings.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_ACCOUNTS = stringPreferencesKey("accounts")
private val KEY_ACTIVE_PUBKEY = stringPreferencesKey("active_pubkey")
private val KEY_ENCRYPTED_KEYS = stringPreferencesKey("encrypted_keys") // pubkey → base64(encrypted privkey)

/**
 * Persists and retrieves [Account] records using Jetpack DataStore.
 *
 * Accounts are stored as a JSON array. The active account pubkey is stored
 * separately so switching accounts is a single cheap write.
 *
 * Encrypted private key blobs (for LOCAL_KEY accounts) are stored in a
 * separate JSON map keyed by pubkey.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val appSettings: AppSettings,
) {
    // Raw, unfiltered — used internally for hidden PIN lookup and migration.
    private val allAccounts: Flow<List<Account>> = dataStore.data.map { prefs ->
        prefs[KEY_ACCOUNTS]
            ?.let { runCatching { NostrJson.decodeFromString<List<Account>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    // Visible accounts: hidden accounts are filtered unless the session has
    // unlocked them via PIN or they are the current active account.
    val accounts: Flow<List<Account>> = combine(
        allAccounts,
        appSettings.hiddenUnlockedPubkeys,
        dataStore.data.map { it[KEY_ACTIVE_PUBKEY] },
    ) { all, unlocked, activePubkey ->
        all.filter { account ->
            if (!account.isHidden) true
            else account.pubkey == activePubkey || account.pubkey in unlocked
        }
    }

    val activeAccount: Flow<Account?> = dataStore.data.map { prefs ->
        val pubkey = prefs[KEY_ACTIVE_PUBKEY] ?: return@map null
        prefs[KEY_ACCOUNTS]
            ?.let { runCatching { NostrJson.decodeFromString<List<Account>>(it) }.getOrDefault(emptyList()) }
            ?.find { it.pubkey == pubkey }
    }

    // ── Hidden helpers ──────────────────────────────────────────────────────

    /** Returns true if any account is hidden. */
    suspend fun hasHiddenAccounts(): Boolean =
        allAccounts.first().any { it.isHidden }

    /** Find a hidden account by its PIN (hashed). Null if no match. */
    suspend fun findHiddenByPin(pin: String): Account? {
        val input = pin.trim()
        val candidates = allAccounts.first()
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            candidates.firstOrNull {
                it.isHidden && it.hiddenPinHash?.let { stored ->
                    social.tbone.security.PinHasher.verify(input, stored)
                } == true
            }
        } ?: return null
        val oldHash = found.hiddenPinHash ?: return null
        if (!social.tbone.security.PinHasher.isLegacy(oldHash)) return found
        val upgraded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { hashPin(input) }
        var migrated = false
        dataStore.edit { prefs ->
            val raw = prefs[KEY_ACCOUNTS] ?: return@edit
            val accounts = NostrJson.decodeFromString<List<Account>>(raw)
            prefs[KEY_ACCOUNTS] = NostrJson.encodeToString(accounts.map {
                if (it.pubkey == found.pubkey && it.isHidden && it.hiddenPinHash == oldHash) {
                    migrated = true
                    it.copy(hiddenPinHash = upgraded)
                } else it
            })
        }
        return if (migrated) found.copy(hiddenPinHash = upgraded) else null
    }

    /** Hide / unhide an account. When hiding, [pin] is required and hashed. */
    suspend fun setHidden(pubkey: String, hidden: Boolean, pin: String? = null) {
        val hash = pin?.takeIf { it.isNotBlank() }?.let { hashPin(it.trim()) }
        if (hidden && hash == null) return // need a PIN to hide
        dataStore.edit { prefs ->
            val accounts = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?: return@edit
            val updated = accounts.map {
                if (it.pubkey == pubkey) it.copy(isHidden = hidden, hiddenPinHash = if (hidden) hash else null)
                else it
            }
            prefs[KEY_ACCOUNTS] = NostrJson.encodeToString(updated)
        }
        // If we just hid the active account, keep it active for now — it
        // will vanish from the list once the user switches away.
    }

    /** Unlock a hidden account via PIN: reveals it for this session and switches to it. */
    suspend fun unlockHiddenByPin(pin: String): Boolean {
        val account = findHiddenByPin(pin) ?: return false
        return activateHiddenIfUnchanged(account)
    }

    /** A verified PIN must not unlock an account whose PIN changed while
     * verification was in flight, or resurrect an account already removed. */
    internal suspend fun activateHiddenIfUnchanged(candidate: Account): Boolean {
        var switched = false
        var hiddenToRelock: String? = null
        dataStore.edit { prefs ->
            val accounts = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?: return@edit
            val match = accounts.firstOrNull {
                it.pubkey == candidate.pubkey && it.isHidden &&
                    it.hiddenPinHash != null && it.hiddenPinHash == candidate.hiddenPinHash
            } ?: return@edit
            val previous = prefs[KEY_ACTIVE_PUBKEY]
            if (previous != null && previous != match.pubkey && accounts.any { it.pubkey == previous && it.isHidden }) {
                hiddenToRelock = previous
            }
            prefs[KEY_ACTIVE_PUBKEY] = match.pubkey
            switched = true
        }
        if (!switched) return false
        hiddenToRelock?.let(appSettings::removeHiddenUnlocked)
        appSettings.addHiddenUnlocked(candidate.pubkey)
        return true
    }

    private fun hashPin(input: String): String = social.tbone.security.PinHasher.hash(input)

    suspend fun verifyHiddenPin(pin: String, storedHash: String): Boolean = social.tbone.security.PinHasher.verify(pin.trim(), storedHash)


    suspend fun addAccount(account: Account, encryptedPrivkey: ByteArray? = null) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?.toMutableList()
                ?: mutableListOf()

            if (current.none { it.pubkey == account.pubkey }) {
                // Seed only the first account created on a fresh install. Do not
                // rewrite existing users' empty or manually maintained relay lists.
                val initialAccount = if (current.isEmpty() && account.relays.isEmpty()) {
                    account.copy(relays = Tunables.NEW_INSTALL_RELAYS)
                } else {
                    account
                }
                current.add(initialAccount)
            }
            prefs[KEY_ACCOUNTS] = NostrJson.encodeToString(current)

            if (encryptedPrivkey != null) {
                val keys = prefs[KEY_ENCRYPTED_KEYS]
                    ?.let { NostrJson.decodeFromString<Map<String, String>>(it) }
                    ?.toMutableMap()
                    ?: mutableMapOf()
                keys[account.pubkey] = android.util.Base64.encodeToString(
                    encryptedPrivkey,
                    android.util.Base64.NO_WRAP,
                )
                prefs[KEY_ENCRYPTED_KEYS] = NostrJson.encodeToString(keys)
            }

            // Newly added account always becomes active
            prefs[KEY_ACTIVE_PUBKEY] = account.pubkey
        }
    }

    suspend fun removeAccount(pubkey: String) {
        dataStore.edit { prefs ->
            val updated = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?.filter { it.pubkey != pubkey }
                ?: emptyList()
            prefs[KEY_ACCOUNTS] = NostrJson.encodeToString(updated)

            // Offline follow/block lists belong to exactly one account: they go with it.
            social.tbone.lists.ListStorageKeys.allFor(pubkey).forEach { prefs.remove(it) }

            if (prefs[KEY_ACTIVE_PUBKEY] == pubkey) {
                prefs[KEY_ACTIVE_PUBKEY] = updated.firstOrNull()?.pubkey ?: ""
            }

            val keys = prefs[KEY_ENCRYPTED_KEYS]
                ?.let { NostrJson.decodeFromString<Map<String, String>>(it) }
                ?.toMutableMap()
                ?: return@edit
            keys.remove(pubkey)
            prefs[KEY_ENCRYPTED_KEYS] = NostrJson.encodeToString(keys)
        }
    }

    suspend fun setActiveAccount(pubkey: String) {
        var hiddenToRelock: String? = null
        var unlockTarget = false
        dataStore.edit { prefs ->
            val accounts = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?: return@edit
            val target = accounts.firstOrNull { it.pubkey == pubkey } ?: return@edit
            val previous = prefs[KEY_ACTIVE_PUBKEY]
            if (previous != null && previous != pubkey && accounts.any { it.pubkey == previous && it.isHidden }) {
                hiddenToRelock = previous
            }
            unlockTarget = target.isHidden
            prefs[KEY_ACTIVE_PUBKEY] = pubkey
        }
        hiddenToRelock?.let(appSettings::removeHiddenUnlocked)
        if (unlockTarget) appSettings.addHiddenUnlocked(pubkey)
    }

    suspend fun updateAccount(account: Account) {
        dataStore.edit { prefs ->
            val updated = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?.map { if (it.pubkey == account.pubkey) account else it }
                ?: return@edit
            prefs[KEY_ACCOUNTS] = NostrJson.encodeToString(updated)
        }
    }

    /**
     * Atomically transforms one account inside DataStore's edit block.
     * The [transform] always receives the current persisted state, so callers
     * never need to read the account separately — no stale-snapshot races.
     */
    suspend fun updateAccount(pubkey: String, transform: (Account) -> Account) {
        dataStore.edit { prefs ->
            val accounts = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?: return@edit
            val updated = accounts.map { if (it.pubkey == pubkey) transform(it) else it }
            prefs[KEY_ACCOUNTS] = NostrJson.encodeToString(updated)
        }
    }

    fun getEncryptedPrivkey(pubkey: String): Flow<ByteArray?> = dataStore.data.map { prefs ->
        prefs[KEY_ENCRYPTED_KEYS]
            ?.let { NostrJson.decodeFromString<Map<String, String>>(it) }
            ?.get(pubkey)
            ?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
    }
}
