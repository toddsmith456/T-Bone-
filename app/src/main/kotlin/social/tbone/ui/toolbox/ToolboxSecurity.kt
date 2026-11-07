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
import androidx.room.withTransaction
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
 * wipes everything toolbox-related (encrypted notes and bookmarks, voice
 * recordings, and toolbox settings). Mirrors the app lock / duress features
 * but scoped to the Toolbox.
 */
@Singleton
class ToolboxSecurity @Inject constructor(
    private val appSettings: AppSettings,
    private val appDatabase: AppDatabase,
    private val geohashCache: social.tbone.ui.toolbox.geohash.GeohashMessageCache,
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
    private val calendarAlarms: social.tbone.calendar.CalendarAlarmScheduler,
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
        val ok = PinHasher.verify(input, stored)
        if (ok && PinHasher.isLegacy(stored)) appSettings.setToolboxDuressPinHash(PinHasher.hash(input))
        return ok
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
     * Wipes everything toolbox-related:
     *  - encrypted notes and bookmarks (including their folders and attachments)
     *  - calendar events (a toolbox tool)
     *  - geohash channels, nickname and identity seed (DataStore + prefs)
     *  - voice recordings (filesDir/recordings)
     *  - toolbox settings (pin, duress pin, screenshot toggle)
     * The app's accounts / settings / relay data are untouched, and the
     * notifications table is PRESERVED — incoming activity from other people
     * is not your private content, so the duress wipe keeps it.
     */
    fun wipeToolbox() {
        scope.launch(Dispatchers.IO) {
            val steps = WipeSteps { name, error -> timber.log.Timber.e(error, "Toolbox wipe step failed: $name") }
            steps.run("block and cancel reminders") { calendarAlarms.beginSensitiveWipe() }
            steps.run("disable geohash cache") { appSettings.setGeohashCacheMillis(0L) }
            steps.run("clear geohash cache") { geohashCache.clear() }
            steps.run("clear toolbox tables") {
                appDatabase.withTransaction {
                    val db = appDatabase.openHelper.writableDatabase
                    listOf("bookmarks", "bookmark_folders", "note_attachments", "note_folders", "calendar_events", "notes", "events", "profiles", "geohash_messages")
                        .forEach { table -> db.execSQL("DELETE FROM $table") }
                }
            }
            steps.run("clear geohash settings") { appSettings.clearGeohashData() }
            steps.run("clear geohash preferences") {
                check(context.getSharedPreferences("tbone_geohash", Context.MODE_PRIVATE).edit().clear().commit())
            }
            steps.run("clear recordings") {
                context.filesDir.listFiles()?.filter { it.name == "recordings" }?.forEach {
                    check(it.deleteRecursively()) { "recording deletion failed" }
                }
            }
            // Preserve lock credentials until the data-removal steps succeed.
            if (!steps.failed) steps.run("clear toolbox security settings") {
                appSettings.setToolboxPinEnabled(false)
                appSettings.setToolboxPinHash("")
                appSettings.setToolboxDuressPinEnabled(false)
                appSettings.setToolboxDuressPinHash("")
                appSettings.setToolboxScreenshotBlockEnabled(false)
                attempts.reset()
            }
            if (!steps.failed) steps.run("finish calendar wipe gate") { calendarAlarms.finishSensitiveWipe() }
            if (steps.failed) withContext(Dispatchers.Main) {
                Toast.makeText(context, "Toolbox wipe incomplete. Keep it locked and try again.", Toast.LENGTH_LONG).show()
            }
        }
    }


}
