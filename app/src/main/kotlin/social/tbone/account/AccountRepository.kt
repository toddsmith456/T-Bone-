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
        val hash = sha256(pin.trim())
        return allAccounts.first().firstOrNull { it.isHidden && it.hiddenPinHash == hash }
    }

    /** Hide / unhide an account. When hiding, [pin] is required and hashed. */
    suspend fun setHidden(pubkey: String, hidden: Boolean, pin: String? = null) {
        val hash = pin?.takeIf { it.isNotBlank() }?.let { sha256(it.trim()) }
        if (hidden && hash == null) return // need a PIN to hide
        dataStore.edit { prefs ->
            val accounts = prefs[KEY_ACCOUNTS]
                ?.let { runCatching { NostrJson.decodeFromString<List<Account>>(it) }.getOrDefault(emptyList()) }
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
        appSettings.addHiddenUnlocked(account.pubkey)
        setActiveAccount(account.pubkey)
        return true
    }

    private fun sha256(input: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        return d.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

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
        // Leaving a hidden account behind should re-hide it. We detect the
        // previous active: if it was hidden and we're switching away, drop
        // its session unlock so it disappears again.
        val previous = dataStore.data.first()[KEY_ACTIVE_PUBKEY]
        if (previous != null && previous != pubkey) {
            val all = allAccounts.first()
            val prevAccount = all.find { it.pubkey == previous }
            if (prevAccount?.isHidden == true) {
                appSettings.removeHiddenUnlocked(previous)
            }
        }
        // Entering an account always unlocks it for the session so it stays
        // visible while it is active (even if hidden).
        val all = allAccounts.first()
        if (all.any { it.pubkey == pubkey && it.isHidden }) {
            appSettings.addHiddenUnlocked(pubkey)
        }
        dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_PUBKEY] = pubkey
        }
    }

    suspend fun updateAccount(account: Account) {
        dataStore.edit { prefs ->
            val updated = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?.map { if (it.pubkey == account.pubkey) account else it }
                ?: listOf(account)
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
