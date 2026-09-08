package social.tbone.security

import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import social.tbone.MainActivity
import social.tbone.db.AppDatabase
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Completely wipes the app's local data:
 *  - Room database (events, profiles, notifications, encrypted notes)
 *  - DataStore preferences (accounts, settings, PIN, everything)
 *  - Internal files (logs, recordings)
 *  - Android Keystore keys used by the app
 *
 * Used by the duress PIN: entering it on the lock screen wipes instead of
 * unlocking. After wiping, the activity restarts into a clean onboarding state.
 */
@Singleton
class DataWiper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {

    /**
     * Wipes all app data and restarts into onboarding.
     * Safe to call from any thread.
     */
    fun wipeAndRestart() {
        scope.launch(Dispatchers.IO) {
            // 1. Room database — every table.
            runCatching { appDatabase.clearAllTables() }

            // 2. DataStore — all preferences (accounts, settings, pins).
            runCatching {
                dataStore.edit { it.clear() }
            }

            // 3. Internal files (logs, recordings, anything else).
            runCatching {
                context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
            }

            // 4. Keystore keys owned by this app.
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                ks.deleteEntry("tbone_notes_key")
                ks.deleteEntry("tbone_local_key_encryption")
            }

            // 5. Restart the activity so the app boots into a clean state
            //    (no account -> onboarding).
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
