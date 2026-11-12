package social.tbone.backup

import kotlinx.serialization.Serializable

/** Plaintext payload model inside the authenticated encrypted backup envelope. */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val notes: List<BackupNote>,
    val folders: List<BackupFolder>,
    val attachments: List<BackupAttachment>,
    val calendarEvents: List<BackupCalendarEvent>,
    /**
     * The home-tab layout, as the same JSON the layout editor exports.
     *
     * Stored as a string rather than as typed slots on purpose: the backup
     * format is a *transport*, and the layout already has its own versioned,
     * tolerant codec. Re-modelling it here would create a second schema that
     * can drift from the first one — and a backup written by a newer build
     * would be rejected by an older one instead of degrading to defaults.
     *
     * Absent in backups made before home feeds existed; restoring one of those
     * leaves whatever layout the device already has.
     */
    val homeLayout: String? = null,
)

@Serializable
data class BackupNote(
    val id: String,
    /** "note" or "checklist". */
    val kind: String,
    val text: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val sortOrder: Double,
    val folderId: String?,
    /** Pin state scoped to the note's folder. Missing in pre-folder-pin backups. */
    val folderPinned: Boolean = false,
)

@Serializable
data class BackupFolder(
    val id: String,
    val name: String,
    val pinned: Boolean,
    val sortOrder: Double,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Attachment bytes follow the manifest as length-prefixed binary records. */
@Serializable
data class BackupAttachment(
    val id: String,
    val noteId: String,
    val kind: String,
    val mime: String,
    val size: Long,
    val createdAt: Long,
)

@Serializable
data class BackupCalendarEvent(
    val id: String,
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val repeatUnit: String,
    val repeatInterval: Int,
    val repeatEndMillis: Long?,
)
