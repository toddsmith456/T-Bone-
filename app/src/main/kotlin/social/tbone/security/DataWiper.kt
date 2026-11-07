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
import kotlinx.coroutines.withContext
import android.widget.Toast
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
    private val calendarAlarms: social.tbone.calendar.CalendarAlarmScheduler,
    private val noteWrites: social.tbone.notes.NoteWriteCoordinator,
    private val appSettings: social.tbone.settings.AppSettings,
    private val geohashCache: social.tbone.ui.toolbox.geohash.GeohashMessageCache,
    private val geohashMonitor: social.tbone.ui.toolbox.geohash.GeohashPinnedMonitor,
    private val followMutations: social.tbone.lists.FollowMutationCoordinator,
) {

    /**
     * Wipes all app data and restarts into onboarding.
     * Safe to call from any thread.
     */
    private val wiping = java.util.concurrent.atomic.AtomicBoolean(false)

    fun wipeAndRestart() {
        if (!wiping.compareAndSet(false, true)) return
        if (!noteWrites.requestWipe(social.tbone.notes.NoteWriteCoordinator.WipeKind.APP)) {
            wiping.set(false)
            scope.launch(Dispatchers.Main) {
                Toast.makeText(context, "Wipe could not start. Keep the app locked and try again.", Toast.LENGTH_LONG).show()
            }
            return
        }
        scope.launch(Dispatchers.IO) {
          try {
            val complete = noteWrites.runWipe(social.tbone.notes.NoteWriteCoordinator.WipeKind.APP) {
            val steps = WipeSteps { name, error -> timber.log.Timber.e(error, "Wipe step failed: $name") }
            steps.run("pause follow list mutations") { followMutations.pauseForWipe() }
            steps.run("pause geohash watchers") { geohashMonitor.pauseForWipe() }
            steps.run("pause and drain note writes") { noteWrites.pauseAndDrain() }
            steps.run("block and cancel calendar reminders") { calendarAlarms.beginSensitiveWipe() }
            steps.run("cancel notifications") {
                checkNotNull(context.getSystemService(android.app.NotificationManager::class.java)).cancelAll()
            }
            // Disable queued and future geohash writes before deleting cached rows.
            steps.run("disable geohash cache") { appSettings.setGeohashCacheMillis(0L) }
            steps.run("clear geohash cache") { geohashCache.clear() }
            // Clear the in-memory channel state before watchers can resume,
            // not only the underlying DataStore on its later flow emission.
            steps.run("clear geohash settings") { appSettings.clearGeohashData() }
            steps.run("clear database") { appDatabase.clearAllTables() }

            // The live DataStore file must never be deleted behind its active
            // instance. It is cleared atomically through its API below instead.
            steps.run("clear internal files") {
                checkNotNull(context.filesDir.listFiles()) { "cannot enumerate internal files" }
                    .filterNot { it.name == "datastore" }.forEach {
                    check(it.deleteRecursively()) { "internal file deletion failed" }
                }
            }
            steps.run("clear cache") {
                checkNotNull(context.cacheDir.listFiles()) { "cannot enumerate cache" }
                    .forEach { check(it.deleteRecursively()) { "cache deletion failed" } }
            }
            steps.run("clear code cache") {
                checkNotNull(context.codeCacheDir.listFiles()) { "cannot enumerate code cache" }
                    .forEach { check(it.deleteRecursively()) { "code cache deletion failed" } }
            }
            listOf("tbone_notes_key", "tbone_local_key_encryption", "tbone_calendar_key").forEach { alias ->
                steps.run("remove owned key") {
                    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    ks.deleteEntry(alias)
                    check(!ks.containsAlias(alias)) { "key removal failed" }
                }
            }

            // Do not claim a clean restart or remove the PIN if an earlier step
            // failed. Keeping the gate permits a retry without exposing content.
            if (!steps.failed) {
                steps.run("clear shared preferences") {
                    val prefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                    // Keep the persisted alarm gate until all data removal is complete.
                    // Deleting it here would let a process restart deliver a stale alarm.
                    val prefsFiles = if (prefsDir.exists()) checkNotNull(prefsDir.listFiles()) { "cannot enumerate preferences" } else emptyArray()
                    prefsFiles.filter { it.name.endsWith(".xml") && it.name != "tbone_calendar_alarms.xml" && it.name != "${social.tbone.notes.PersistentWipeState.PREFS_NAME}.xml" }.forEach { file ->
                        check(context.getSharedPreferences(file.name.removeSuffix(".xml"), Context.MODE_PRIVATE)
                            .edit().clear().commit())
                    }
                }
            }
            if (!steps.failed) steps.run("clear settings and accounts") { dataStore.edit { it.clear() } }
            if (!steps.failed) steps.run("finish calendar wipe gate") { calendarAlarms.finishSensitiveWipe() }
            if (!steps.failed) {
                steps.run("restart") {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    context.startActivity(intent)
                }
            }
            if (!steps.failed) steps.run("resume follow list mutations") { followMutations.resumeAfterWipe() }
            if (!steps.failed) steps.run("resume geohash watchers") { geohashMonitor.resumeAfterWipe() }
            !steps.failed
            }
            if (!complete) withContext(Dispatchers.Main) {
                Toast.makeText(context, "Wipe incomplete. Keep the app locked and try again.", Toast.LENGTH_LONG).show()
            }
          } finally {
              wiping.set(false)
          }
        }
    }
}
