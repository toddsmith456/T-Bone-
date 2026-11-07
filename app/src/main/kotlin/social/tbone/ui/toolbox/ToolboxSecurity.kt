package social.tbone.ui.toolbox

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import social.tbone.security.WipeSteps
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import social.tbone.security.PinAttemptLimiter
import social.tbone.db.AppDatabase
import social.tbone.settings.AppSettings
import social.tbone.security.PinHasher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Toolbox-only security: a PIN that locks the toolbox and a duress PIN that
 * attempts to clear selected toolbox data (encrypted notes and bookmarks,
 * recordings and toolbox settings); external copies remain. Mirrors the app lock / duress features
 * but scoped to the Toolbox.
 */
@Singleton
class ToolboxSecurity @Inject constructor(
    private val appSettings: AppSettings,
    private val appDatabase: AppDatabase,
    private val geohashCache: social.tbone.ui.toolbox.geohash.GeohashMessageCache,
    private val geohashMonitor: social.tbone.ui.toolbox.geohash.GeohashPinnedMonitor,
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
    private val calendarAlarms: social.tbone.calendar.CalendarAlarmScheduler,
    private val noteWrites: social.tbone.notes.NoteWriteCoordinator,
    private val voiceRecordingGate: social.tbone.toolbox.VoiceRecordingGate,
    private val legacyCleanup: social.tbone.migration.LegacyMiniAppCleanup,
) {

    private val attempts = PinAttemptLimiter(context, "toolbox")
    private val verifyMutex = Mutex()
    fun retryAfterMillis(): Long = attempts.remainingMillis()

    suspend fun isPinEnabled(): Boolean = appSettings.getToolboxPinEnabled()

    suspend fun verifyPin(input: String): Boolean = verifyMutex.withLock {
        if (attempts.remainingMillis() > 0) return@withLock false
        val stored = appSettings.getToolboxPinHash() ?: return@withLock false
        val ok = PinHasher.verify(input, stored)
        if (ok) attempts.reset() else attempts.failure()
        if (ok && PinHasher.isLegacy(stored)) appSettings.setToolboxPinHash(PinHasher.hash(input))
        ok
    }

    suspend fun isDuressPin(input: String): Boolean {
        if (!appSettings.getToolboxDuressPinEnabled()) return false
        val stored = appSettings.getToolboxDuressPinHash() ?: return false
        // The matching PIN initiates a wipe; migration must not delay or block it.
        return PinHasher.verify(input, stored)
    }

    /** Sets the toolbox pin; refuses one equal to the duress pin. */
    suspend fun setPin(pin: String): Boolean {
        if (appSettings.getToolboxDuressPinEnabled()) {
            val duressHash = appSettings.getToolboxDuressPinHash()
            if (duressHash != null && PinHasher.verify(pin, duressHash)) return false
        }
        attempts.reset()
        appSettings.setToolboxPinHash(PinHasher.hash(pin))
        appSettings.setToolboxPinEnabled(true)
        return true
    }

    /** Sets the toolbox duress pin; refuses one equal to the normal pin. */
    suspend fun setDuressPin(pin: String): Boolean {
        if (appSettings.getToolboxPinEnabled()) {
            val pinHash = appSettings.getToolboxPinHash()
            if (pinHash != null && PinHasher.verify(pin, pinHash)) return false
        }
        appSettings.setToolboxDuressPinHash(PinHasher.hash(pin))
        appSettings.setToolboxDuressPinEnabled(true)
        return true
    }

    suspend fun disablePin() {
        appSettings.setToolboxPinEnabled(false)
        appSettings.setToolboxPinHash("")
    }

    suspend fun disableDuressPin() {
        appSettings.setToolboxDuressPinEnabled(false)
        appSettings.setToolboxDuressPinHash("")
    }

    /**
     * Attempts to clear these toolbox-related datasets:
     *  - encrypted notes and bookmarks (including their folders and attachments)
     *  - calendar events (a toolbox tool)
     *  - geohash channels, nickname and identity seed (DataStore + prefs)
     *  - voice recordings (filesDir/recordings)
     *  - toolbox settings (pin, duress pin, screenshot toggle)
     * The current database cleanup also deletes cached events and profiles,
     * but preserves notifications. Accounts and general app settings remain.
     * Decoy notes are included. Pending legacy browser cleanup must succeed;
     * external copies cannot be recalled.
     */
    private val wiping = java.util.concurrent.atomic.AtomicBoolean(false)

    fun wipeToolbox(discreet: Boolean = false) {
        if (!wiping.compareAndSet(false, true)) return
        if (!noteWrites.requestWipe(social.tbone.notes.NoteWriteCoordinator.WipeKind.TOOLBOX)) {
            wiping.set(false)
            scope.launch(Dispatchers.Main) {
                Toast.makeText(context, if (discreet) "Unable to unlock. Please try again." else "Toolbox wipe could not start. Keep it locked and try again.", Toast.LENGTH_LONG).show()
            }
            return
        }
        scope.launch(Dispatchers.IO) {
          try {
            val complete = noteWrites.runWipe(social.tbone.notes.NoteWriteCoordinator.WipeKind.TOOLBOX) {
            val steps = WipeSteps { name, error -> timber.log.Timber.e(error, "Toolbox wipe step failed: $name") }
            steps.run("pause geohash watchers") { geohashMonitor.pauseForWipe() }
            steps.run("stop active voice recording") { voiceRecordingGate.stopForWipe() }
            steps.run("pause and drain note writes") { noteWrites.pauseAndDrain() }
            steps.run("clear mini-app browser storage") { legacyCleanup.run() }
            steps.run("block and cancel reminders") { calendarAlarms.beginSensitiveWipe() }
            steps.run("disable geohash cache") { appSettings.setGeohashCacheMillis(0L) }
            steps.run("clear geohash cache") { geohashCache.clear() }
            steps.run("clear toolbox tables") {
                clearToolboxTables(appDatabase)
            }
            steps.run("clear geohash settings") { appSettings.clearGeohashData() }
            steps.run("clear geohash preferences") {
                check(context.getSharedPreferences("tbone_geohash", Context.MODE_PRIVATE).edit().clear().commit())
            }
            steps.run("clear recordings") {
                checkNotNull(context.filesDir.listFiles()) { "cannot enumerate internal files" }
                    .filter { it.name == "recordings" }.forEach {
                    check(it.deleteRecursively()) { "recording deletion failed" }
                }
            }
            steps.run("clear note audio cache") {
                checkNotNull(context.cacheDir.listFiles()) { "cannot enumerate cache" }
                    .filter { it.name.startsWith("note_voice_") || it.name.startsWith("play_") }
                    .forEach { check(it.deleteRecursively()) { "note audio cache deletion failed" } }
            }
            // A toolbox-only wipe cannot finalize a previously failed full wipe.
            if (noteWrites.hasFailedAppWipe()) steps.run("check previous app wipe") {
                error("Previous full app wipe requires retry")
            }
            // Preserve lock credentials until the data-removal steps succeed.
            if (!steps.failed) steps.run("clear toolbox security settings") {
                attempts.reset()
                appSettings.clearToolboxSecurity()
            }
            if (!steps.failed) steps.run("finish calendar wipe gate") { calendarAlarms.finishSensitiveWipe() }
            if (!steps.failed) steps.run("resume geohash watchers") { geohashMonitor.resumeAfterWipe() }
            !steps.failed
            }
            if (!complete) withContext(Dispatchers.Main) {
                Toast.makeText(context, if (discreet) "Unable to unlock. Please try again." else "Toolbox wipe incomplete. Keep it locked and try again.", Toast.LENGTH_LONG).show()
            }
          } finally {
              wiping.set(false)
          }
        }
    }


}
