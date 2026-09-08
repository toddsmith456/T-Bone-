package social.tbone.ui.toolbox

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import social.tbone.db.AppDatabase
import social.tbone.settings.AppSettings
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Toolbox-only security: a PIN that locks the toolbox and a duress PIN that
 * wipes everything toolbox-related (encrypted notes, voice recordings, and
 * toolbox settings). Mirrors the app lock / duress features but scoped to the
 * Toolbox.
 */
@Singleton
class ToolboxSecurity @Inject constructor(
    private val appSettings: AppSettings,
    private val appDatabase: AppDatabase,
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
) {

    suspend fun isPinEnabled(): Boolean = appSettings.getToolboxPinEnabled()

    suspend fun verifyPin(input: String): Boolean {
        val stored = appSettings.getToolboxPinHash() ?: return false
        return sha256(input) == stored
    }

    suspend fun isDuressPin(input: String): Boolean {
        if (!appSettings.getToolboxDuressPinEnabled()) return false
        val stored = appSettings.getToolboxDuressPinHash() ?: return false
        return sha256(input) == stored
    }

    /** Sets the toolbox pin; refuses one equal to the duress pin. */
    suspend fun setPin(pin: String): Boolean {
        if (appSettings.getToolboxDuressPinEnabled() && sha256(pin) == appSettings.getToolboxDuressPinHash()) return false
        appSettings.setToolboxPinHash(sha256(pin))
        appSettings.setToolboxPinEnabled(true)
        return true
    }

    /** Sets the toolbox duress pin; refuses one equal to the normal pin. */
    suspend fun setDuressPin(pin: String): Boolean {
        if (appSettings.getToolboxPinEnabled() && sha256(pin) == appSettings.getToolboxPinHash()) return false
        appSettings.setToolboxDuressPinHash(sha256(pin))
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
     *  - encrypted notes (notes, note_folders, note_attachments)
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
            runCatching {
                // Delete every table except `notifications`. Children first so
                // any foreign-key ordering is respected.
                val db = appDatabase.openHelper.writableDatabase
                listOf("note_attachments", "note_folders", "calendar_events", "notes", "events", "profiles")
                    .forEach { table ->
                        runCatching { db.execSQL("DELETE FROM $table") }
                    }
                // Geohash identity: channels + nickname + seed live in the
                // DataStore; per-channel last-seen marks live in prefs.
                appSettings.clearGeohashData()
                runCatching {
                    context.getSharedPreferences("tbone_geohash", Context.MODE_PRIVATE)
                        .edit().clear().apply()
                }
                context.filesDir.listFiles()?.filter { it.name == "recordings" }?.forEach { it.deleteRecursively() }
                appSettings.setToolboxPinEnabled(false)
                appSettings.setToolboxPinHash("")
                appSettings.setToolboxDuressPinEnabled(false)
                appSettings.setToolboxDuressPinHash("")
                appSettings.setToolboxScreenshotBlockEnabled(false)
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
