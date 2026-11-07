package social.tbone.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.Arrays
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import social.tbone.calendar.CalendarAlarmScheduler
import social.tbone.calendar.CalendarCrypto
import social.tbone.calendar.CalendarEventUi
import social.tbone.calendar.RepeatUnit
import social.tbone.db.AppDatabase
import social.tbone.db.CalendarEventDao
import social.tbone.db.CalendarEventEntity
import social.tbone.db.NoteAttachmentDao
import social.tbone.db.NoteAttachmentEntity
import social.tbone.db.NoteDao
import social.tbone.db.NoteEntity
import social.tbone.db.NoteFolderDao
import social.tbone.db.NoteFolderEntity
import social.tbone.notes.NotesCrypto

/**
 * Creates/imports portable passphrase-encrypted snapshots of the local notes
 * vault, attachments, folders and calendar. Import is an atomic replacement of
 * those four datasets, then re-encrypts their plaintext with this device's own
 * Android Keystore keys and restores all original IDs/ordering/relationships.
 */
@Singleton
class EncryptedBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val noteDao: NoteDao,
    private val folderDao: NoteFolderDao,
    private val attachmentDao: NoteAttachmentDao,
    private val calendarDao: CalendarEventDao,
    private val notesCrypto: NotesCrypto,
    private val calendarCrypto: CalendarCrypto,
    private val alarmScheduler: CalendarAlarmScheduler,
    private val noteWrites: social.tbone.notes.NoteWriteCoordinator,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    /** Writes one consistent database snapshot via a temporary encrypted cache file. */
    suspend fun exportTo(uri: Uri, passphrase: String) = noteWrites.runCancellable {
        exportToInternal(uri, passphrase)
    }

    private suspend fun exportToInternal(uri: Uri, passphrase: String) {
        require(passphrase.isNotBlank()) { "Enter a backup passphrase." }
        val temporary = File(context.cacheDir, "${UUID.randomUUID()}.tbbackup.tmp")
        try {
            // Keep every query—including attachment bytes—inside one Room read
            // transaction. Notes/folders/events cannot drift apart while the
            // encrypted file is being assembled if the user edits in parallel.
            database.withTransaction {
                val manifest = createManifest()
                validateManifest(manifest)
                val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                try {
                    require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "The backup manifest is too large." }
                    temporary.outputStream().buffered().use { rawOutput ->
                        val encrypted = EncryptedBackupCrypto.newEncryptingStream(rawOutput, passphrase)
                        DataOutputStream(encrypted).use { output ->
                            output.writeInt(manifestBytes.size)
                            output.write(manifestBytes)
                            manifest.attachments.forEach { descriptor ->
                                val stored = attachmentDao.getById(descriptor.id)
                                    ?: error("An attachment changed while the backup was being created.")
                                require(
                                    stored.noteId == descriptor.noteId && stored.kind == descriptor.kind &&
                                        stored.mime == descriptor.mime && stored.size == descriptor.size &&
                                        stored.createdAt == descriptor.createdAt
                                ) { "An attachment changed while the backup was being created." }
                                val plaintext = notesCrypto.decryptBytes(stored.ciphertext, stored.iv)
                                try {
                                    require(plaintext.size.toLong() == descriptor.size) {
                                        "An attachment is incomplete; the backup was not written."
                                    }
                                    output.writeLong(plaintext.size.toLong())
                                    output.write(plaintext)
                                } finally {
                                    Arrays.fill(plaintext, 0)
                                }
                            }
                        }
                    }
                } finally {
                    Arrays.fill(manifestBytes, 0)
                }
            }

            val resolver = context.contentResolver
            val destination = resolver.openOutputStream(uri, "wt")
                ?: throw IOException("Could not open the selected backup destination.")
            destination.use { output -> temporary.inputStream().buffered().use { input -> input.copyTo(output) } }
        } finally {
            // The temporary file contains only the already-encrypted envelope.
            temporary.delete()
        }
    }

    /**
     * Authenticates and validates the entire file before the replacement
     * transaction commits. Any wrong passphrase, truncation, bad tag, invalid
     * relation or storage error rolls the transaction back to the old vault.
     */
    suspend fun importFrom(uri: Uri, passphrase: String) = noteWrites.runCancellable {
        importFromInternal(uri, passphrase)
    }

    private suspend fun importFromInternal(uri: Uri, passphrase: String) {
        require(passphrase.isNotBlank()) { "Enter the backup passphrase." }
        val source = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected backup file.")
        var restoredEvents: List<BackupCalendarEvent> = emptyList()

        source.use { rawInput ->
            val decrypted = EncryptedBackupCrypto.newDecryptingStream(rawInput, passphrase)
            DataInputStream(decrypted).use { input ->
                val manifestSize = input.readInt()
                require(manifestSize in 1..MAX_MANIFEST_BYTES) { "The backup manifest is invalid or damaged." }
                val manifestBytes = ByteArray(manifestSize)
                input.readFully(manifestBytes)
                val manifest = try {
                    json.decodeFromString<BackupManifest>(String(manifestBytes, Charsets.UTF_8))
                } catch (e: SerializationException) {
                    throw IllegalArgumentException("The backup contents are invalid or damaged.", e)
                } finally {
                    Arrays.fill(manifestBytes, 0)
                }
                validateManifest(manifest)
                restoredEvents = manifest.calendarEvents

                database.withTransaction {
                    // Replace, never merge: all IDs, order and folder links in
                    // the restored snapshot remain exactly as they were backed up.
                    attachmentDao.deleteAll()
                    noteDao.deleteAll()
                    folderDao.deleteAll()
                    calendarDao.deleteAll()

                    manifest.folders.forEach { folder ->
                        folderDao.upsert(
                            NoteFolderEntity(
                                id = folder.id,
                                name = folder.name,
                                pinned = folder.pinned,
                                sortOrder = folder.sortOrder,
                                createdAt = folder.createdAt,
                                updatedAt = folder.updatedAt,
                            ),
                        )
                    }

                    manifest.notes.forEach { note ->
                        val (ciphertext, iv) = notesCrypto.encrypt(note.text)
                        val (titleCiphertext, titleIv) = if (note.title.isEmpty()) {
                            null to null
                        } else {
                            notesCrypto.encrypt(note.title)
                        }
                        noteDao.upsert(
                            NoteEntity(
                                id = note.id,
                                ciphertext = ciphertext,
                                iv = iv,
                                createdAt = note.createdAt,
                                updatedAt = note.updatedAt,
                                kind = note.kind,
                                pinned = note.pinned,
                                folderPinned = note.folderPinned,
                                sortOrder = note.sortOrder,
                                folderId = note.folderId,
                                titleCiphertext = titleCiphertext,
                                titleIv = titleIv,
                            ),
                        )
                    }

                    manifest.calendarEvents.forEach { event ->
                        val (titleCiphertext, titleIv) = calendarCrypto.encrypt(event.title)
                        val (descriptionCiphertext, descriptionIv) = if (event.description.isEmpty()) {
                            null to null
                        } else {
                            calendarCrypto.encrypt(event.description)
                        }
                        calendarDao.upsert(
                            CalendarEventEntity(
                                id = event.id,
                                titleCiphertext = titleCiphertext,
                                titleIv = titleIv,
                                descriptionCiphertext = descriptionCiphertext,
                                descriptionIv = descriptionIv,
                                startMillis = event.startMillis,
                                endMillis = event.endMillis,
                                allDay = event.allDay,
                                createdAt = event.createdAt,
                                updatedAt = event.updatedAt,
                                repeatUnit = event.repeatUnit,
                                repeatInterval = event.repeatInterval,
                                repeatEndMillis = event.repeatEndMillis,
                            ),
                        )
                    }

                    manifest.attachments.forEach { descriptor ->
                        val size = input.readLong()
                        require(size == descriptor.size && size in 0..MAX_ATTACHMENT_BYTES) {
                            "An attachment in the backup has an invalid size."
                        }
                        val bytes = ByteArray(size.toInt())
                        try {
                            input.readFully(bytes)
                            val (ciphertext, iv) = notesCrypto.encryptBytes(bytes)
                            attachmentDao.insert(
                                NoteAttachmentEntity(
                                    id = descriptor.id,
                                    noteId = descriptor.noteId,
                                    kind = descriptor.kind,
                                    mime = descriptor.mime,
                                    ciphertext = ciphertext,
                                    iv = iv,
                                    size = descriptor.size,
                                    createdAt = descriptor.createdAt,
                                ),
                            )
                        } finally {
                            Arrays.fill(bytes, 0)
                        }
                    }

                    // Reading to authenticated EOF is essential: GCM verifies
                    // the passphrase and every byte here. Trailing plaintext is
                    // rejected, and a bad tag aborts/rolls back the transaction.
                    require(input.read() == -1) { "Unexpected data after the backup contents." }
                }
            }
        }

        // Replace any alarms from the old calendar and install alarms for the
        // restored events without waiting for a relaunch or network connection.
        alarmScheduler.rescheduleAll(
            restoredEvents.map { event ->
                CalendarEventUi(
                    id = event.id,
                    title = event.title,
                    description = event.description,
                    startMillis = event.startMillis,
                    endMillis = event.endMillis,
                    allDay = event.allDay,
                    createdAt = event.createdAt,
                    updatedAt = event.updatedAt,
                    repeatUnit = RepeatUnit.valueOf(event.repeatUnit),
                    repeatInterval = event.repeatInterval,
                    repeatEndMillis = event.repeatEndMillis,
                )
            },
        )
    }

    private suspend fun createManifest(): BackupManifest {
        val notes = noteDao.getAll().map { entity ->
            val (titleCiphertext, titleIv) = entity.titleCiphertext to entity.titleIv
            require((titleCiphertext == null) == (titleIv == null)) { "A note title is damaged; backup was not created." }
            BackupNote(
                id = entity.id,
                kind = entity.kind,
                text = notesCrypto.decrypt(entity.ciphertext, entity.iv),
                title = if (titleCiphertext == null) "" else notesCrypto.decrypt(titleCiphertext, titleIv!!),
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                pinned = entity.pinned,
                sortOrder = entity.sortOrder,
                folderId = entity.folderId,
                folderPinned = entity.folderPinned,
            )
        }
        val folders = folderDao.getAll().map { entity ->
            BackupFolder(
                id = entity.id,
                name = entity.name,
                pinned = entity.pinned,
                sortOrder = entity.sortOrder,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
        }
        val attachments = attachmentDao.getAllMetadata().map { entity ->
            BackupAttachment(
                id = entity.id,
                noteId = entity.noteId,
                kind = entity.kind,
                mime = entity.mime,
                size = entity.size,
                createdAt = entity.createdAt,
            )
        }
        val events = calendarDao.getAll().map { entity ->
            val descriptionCiphertext = entity.descriptionCiphertext
            val descriptionIv = entity.descriptionIv
            require((descriptionCiphertext == null) == (descriptionIv == null)) {
                "A calendar event is damaged; backup was not created."
            }
            BackupCalendarEvent(
                id = entity.id,
                title = calendarCrypto.decrypt(entity.titleCiphertext, entity.titleIv),
                description = if (descriptionCiphertext == null) "" else
                    calendarCrypto.decrypt(descriptionCiphertext, descriptionIv!!),
                startMillis = entity.startMillis,
                endMillis = entity.endMillis,
                allDay = entity.allDay,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                repeatUnit = entity.repeatUnit,
                repeatInterval = entity.repeatInterval,
                repeatEndMillis = entity.repeatEndMillis,
            )
        }
        return BackupManifest(
            formatVersion = BACKUP_FORMAT_VERSION,
            notes = notes,
            folders = folders,
            attachments = attachments,
            calendarEvents = events,
        )
    }

    private fun validateManifest(manifest: BackupManifest) {
        require(manifest.formatVersion == BACKUP_FORMAT_VERSION) { "This backup format version is not supported." }
        require(manifest.notes.size <= MAX_RECORDS && manifest.folders.size <= MAX_RECORDS &&
            manifest.attachments.size <= MAX_RECORDS && manifest.calendarEvents.size <= MAX_RECORDS
        ) { "The backup contains too many records." }

        val folderIds = manifest.folders.map { it.id }
        val noteIds = manifest.notes.map { it.id }
        val attachmentIds = manifest.attachments.map { it.id }
        val eventIds = manifest.calendarEvents.map { it.id }
        require(folderIds.size == folderIds.toSet().size) { "The backup contains duplicate folder IDs." }
        require(noteIds.size == noteIds.toSet().size) { "The backup contains duplicate note IDs." }
        require(attachmentIds.size == attachmentIds.toSet().size) { "The backup contains duplicate attachment IDs." }
        require(eventIds.size == eventIds.toSet().size) { "The backup contains duplicate event IDs." }

        val validFolderIds = folderIds.toSet()
        val validNoteIds = noteIds.toSet()
        manifest.folders.forEach { requireValidId(it.id) }
        manifest.notes.forEach { note ->
            requireValidId(note.id)
            require(note.kind == "note" || note.kind == "checklist") { "The backup contains an unknown note type." }
            require(note.folderId == null || note.folderId in validFolderIds) {
                "A note refers to a folder that is not in the backup."
            }
            require(!note.folderPinned || note.folderId != null) {
                "A note pinned in a folder must refer to a folder in the backup."
            }
        }
        manifest.attachments.forEach { attachment ->
            requireValidId(attachment.id)
            require(attachment.noteId in validNoteIds) { "An attachment refers to a note that is not in the backup." }
            require(attachment.kind.isNotBlank() && attachment.mime.isNotBlank()) { "An attachment descriptor is invalid." }
            require(attachment.size in 0..MAX_ATTACHMENT_BYTES) { "An attachment is too large to restore safely." }
        }
        manifest.calendarEvents.forEach { event ->
            requireValidId(event.id)
            require(event.repeatUnit in RepeatUnit.entries.map { it.name }) { "The backup contains an unknown calendar recurrence." }
            require(event.repeatInterval >= 0) { "A calendar recurrence interval is invalid." }
        }
    }

    private fun requireValidId(id: String) {
        require(id.length in 1..160 && id.all { it.isLetterOrDigit() || it in "-_." }) {
            "The backup contains an invalid record ID."
        }
    }

    private companion object {
        const val BACKUP_FORMAT_VERSION = 1
        const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
        const val MAX_ATTACHMENT_BYTES = 64L * 1024L * 1024L
        const val MAX_RECORDS = 50_000
    }
}
