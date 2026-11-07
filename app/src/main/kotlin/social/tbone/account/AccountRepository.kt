package social.tbone.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import social.tbone.nostr.NostrJson
import social.tbone.Tunables
import social.tbone.settings.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_ACCOUNTS = stringPreferencesKey("accounts")
private val KEY_ACTIVE_PUBKEY = stringPreferencesKey("active_pubkey")
private val KEY_ENCRYPTED_KEYS = stringPreferencesKey("encrypted_keys") // pubkey → base64(encrypted privkey)

/** Distinguishes a new install from unreadable or inconsistent account data. */
internal enum class AccountStartupStatus { EMPTY, READY, UNREADABLE }

internal fun accountStartupStatus(prefs: Preferences): AccountStartupStatus {
    val raw = prefs[KEY_ACCOUNTS]
    val active = prefs[KEY_ACTIVE_PUBKEY]?.takeIf { it.isNotBlank() }
    if (raw == null) return if (active == null) AccountStartupStatus.EMPTY else AccountStartupStatus.UNREADABLE
    val accounts = try { NostrJson.decodeFromString<List<Account>>(raw) }
        catch (_: kotlinx.serialization.SerializationException) { return AccountStartupStatus.UNREADABLE }
        catch (_: IllegalArgumentException) { return AccountStartupStatus.UNREADABLE }
    if (accounts.isEmpty() && active == null) return AccountStartupStatus.EMPTY
    return if (active != null && accounts.any { it.pubkey == active }) AccountStartupStatus.READY
        else AccountStartupStatus.UNREADABLE
}

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
    private val hiddenVerification = Mutex()

    internal val startupStatus: Flow<AccountStartupStatus> = dataStore.data.map(::accountStartupStatus)

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
    suspend fun findHiddenByPin(pin: String): Account? = hiddenVerification.withLock {
        findHiddenMatches(pin).firstOrNull()
    }

    /** A PIN can intentionally be shared by several hidden accounts. */
    private suspend fun findHiddenMatches(pin: String): List<Account> {
        val input = pin.trim()
        // Unlike the display flow, recovery must distinguish a genuinely empty
        // account list from unreadable stored data instead of saying "wrong PIN".
        val candidates = dataStore.data.first()[KEY_ACCOUNTS]
            ?.let { NostrJson.decodeFromString<List<Account>>(it) }
            ?: emptyList()
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            candidates.filter {
                it.isHidden && it.hiddenPinHash?.let { stored ->
                    social.tbone.security.PinHasher.verify(input, stored)
                } == true
            }
        }
        if (found.isEmpty()) return emptyList()
        val upgrades = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            found.filter { it.hiddenPinHash?.let(social.tbone.security.PinHasher::isLegacy) == true }
                .associate { it.pubkey to hashPin(input) }
        }
        if (upgrades.isEmpty()) return found
        val migrated = mutableMapOf<String, String>()
        dataStore.edit { prefs ->
            val raw = prefs[KEY_ACCOUNTS] ?: return@edit
            val accounts = NostrJson.decodeFromString<List<Account>>(raw)
            prefs[KEY_ACCOUNTS] = NostrJson.encodeToString(accounts.map { current ->
                val previous = found.firstOrNull { it.pubkey == current.pubkey }
                val upgraded = upgrades[current.pubkey]
                if (upgraded != null && current.isHidden && current.hiddenPinHash == previous?.hiddenPinHash) {
                    migrated[current.pubkey] = upgraded
                    current.copy(hiddenPinHash = upgraded)
                } else current
            })
        }
        return found.mapNotNull { account ->
            if (account.pubkey !in upgrades) account
            else migrated[account.pubkey]?.let { account.copy(hiddenPinHash = it) }
        }
    }

    /** Hide / unhide an account. When hiding, [pin] is required and hashed. */
    suspend fun setHidden(pubkey: String, hidden: Boolean, pin: String? = null): Boolean {
        val hash = pin?.takeIf { it.isNotBlank() }?.let { value ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { hashPin(value.trim()) }
        }
        if (hidden && hash == null) return false // need a PIN to hide
        var changed = false
        dataStore.edit { prefs ->
            val accounts = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?: return@edit
            val updated = accounts.map {
                if (it.pubkey == pubkey) {
                    changed = true
                    it.copy(isHidden = hidden, hiddenPinHash = if (hidden) hash else null)
                } else it
            }
            prefs[KEY_ACCOUNTS] = NostrJson.encodeToString(updated)
        }
        // If we just hid the active account, keep it active for now — it
        // will vanish from the list once the user switches away.
        return changed
    }

    /** Unlock a hidden account via PIN: reveals it for this session and switches to it. */
    suspend fun unlockHiddenByPin(pin: String): Boolean {
        val epoch = appSettings.hiddenSessionEpoch()
        if (epoch == Long.MIN_VALUE) return false
        return hiddenVerification.withLock { activateHiddenMatches(findHiddenMatches(pin), epoch) }
    }

    /** A verified PIN must not unlock an account whose PIN changed while
     * verification was in flight, or resurrect an account already removed. */
    internal suspend fun activateHiddenIfUnchanged(candidate: Account): Boolean =
        activateHiddenMatches(listOf(candidate), appSettings.hiddenSessionEpoch())

    private suspend fun activateHiddenMatches(candidates: List<Account>, sessionEpoch: Long): Boolean {
        if (candidates.isEmpty() || sessionEpoch == Long.MIN_VALUE) return false
        val unlocked = mutableListOf<String>()
        var hiddenToRelock: String? = null
        dataStore.edit { prefs ->
            val accounts = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?: return@edit
            val matches = accounts.filter { current ->
                current.isHidden && current.hiddenPinHash != null && candidates.any {
                    it.pubkey == current.pubkey && it.hiddenPinHash == current.hiddenPinHash
                }
            }
            val active = matches.firstOrNull() ?: return@edit
            val previous = prefs[KEY_ACTIVE_PUBKEY]
            if (previous != null && previous != active.pubkey && accounts.any { it.pubkey == previous && it.isHidden }) {
                hiddenToRelock = previous
            }
            prefs[KEY_ACTIVE_PUBKEY] = active.pubkey
            unlocked.addAll(matches.map { it.pubkey })
        }
        if (unlocked.isEmpty()) return false
        return appSettings.updateHiddenSession(sessionEpoch, hiddenToRelock, unlocked)
    }

    private fun hashPin(input: String): String = social.tbone.security.PinHasher.hash(input)

    suspend fun verifyHiddenPin(pin: String, storedHash: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { social.tbone.security.PinHasher.verify(pin.trim(), storedHash) }


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
        val sessionEpoch = appSettings.hiddenSessionEpoch()
        if (sessionEpoch == Long.MIN_VALUE) return
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
        appSettings.updateHiddenSession(sessionEpoch, hiddenToRelock, if (unlockTarget) listOf(pubkey) else emptyList())
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
