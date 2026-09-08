package social.tbone.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import social.tbone.nostr.NostrJson
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
) {
    val accounts: Flow<List<Account>> = dataStore.data.map { prefs ->
        prefs[KEY_ACCOUNTS]
            ?.let { NostrJson.decodeFromString<List<Account>>(it) }
            ?: emptyList()
    }

    val activeAccount: Flow<Account?> = dataStore.data.map { prefs ->
        val pubkey = prefs[KEY_ACTIVE_PUBKEY] ?: return@map null
        prefs[KEY_ACCOUNTS]
            ?.let { NostrJson.decodeFromString<List<Account>>(it) }
            ?.find { it.pubkey == pubkey }
    }

    suspend fun addAccount(account: Account, encryptedPrivkey: ByteArray? = null) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_ACCOUNTS]
                ?.let { NostrJson.decodeFromString<List<Account>>(it) }
                ?.toMutableList()
                ?: mutableListOf()

            if (current.none { it.pubkey == account.pubkey }) {
                current.add(account)
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
