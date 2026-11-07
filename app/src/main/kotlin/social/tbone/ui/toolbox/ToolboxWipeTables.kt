package social.tbone.ui.toolbox

import androidx.room.withTransaction
import social.tbone.db.AppDatabase

/** Clear the related vault and cache tables atomically while writers are paused. */
internal suspend fun clearToolboxTables(database: AppDatabase) {
    database.withTransaction {
        val db = database.openHelper.writableDatabase
        listOf(
            "bookmarks", "bookmark_folders", "note_attachments", "note_folders",
            "calendar_events", "notes", "decoy_note_attachments", "decoy_note_folders",
            "decoy_notes", "events", "profiles", "geohash_messages",
        ).forEach { table -> db.execSQL("DELETE FROM $table") }
    }
}
