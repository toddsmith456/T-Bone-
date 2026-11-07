package social.tbone.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.db.AppDatabase

/** Real Room + Android Keystore checks, without touching the app's database. */
@RunWith(AndroidJUnit4::class)
class NotesIntegrityTest {
    private lateinit var db: AppDatabase
    private lateinit var real: NotesRepository
    private lateinit var decoy: DecoyNotesRepository
    private lateinit var owner: CoroutineScope
    private lateinit var noteWrites: NoteWriteCoordinator

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val crypto = NotesCrypto()
        owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        noteWrites = NoteWriteCoordinator(owner)
        real = NotesRepository(db.noteDao(), db.noteFolderDao(), db.noteAttachmentDao(), crypto, noteWrites, db)
        decoy = DecoyNotesRepository(db.decoyNoteDao(), db.decoyNoteFolderDao(), db.decoyNoteAttachmentDao(), crypto, noteWrites, db)
    }

    @After fun teardown() { owner.cancel(); db.close() }

    @Test fun toolboxWipeClearsBothVaultsAndRollsBackOnFailure() = runBlocking {
        real.saveText("r", "real", "body", null)
        decoy.saveText("d", "decoy", "body", null)
        real.addAttachment("r", "voice", "audio/mp4", byteArrayOf(1))
        decoy.addAttachment("d", "voice", "audio/mp4", byteArrayOf(2))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_decoy_wipe BEFORE DELETE ON decoy_notes BEGIN SELECT RAISE(ABORT, 'injected'); END")
        try {
            social.tbone.ui.toolbox.clearToolboxTables(db)
            fail("wipe should roll back")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertNotNull(real.getById("r"))
        assertNotNull(decoy.getById("d"))
        assertEquals(1, real.attachments("r").first().size)
        assertEquals(1, decoy.attachments("d").first().size)
        sql.execSQL("DROP TRIGGER fail_decoy_wipe")
        social.tbone.ui.toolbox.clearToolboxTables(db)
        assertNull(real.getById("r"))
        assertNull(decoy.getById("d"))
        assertEquals(0, real.attachments("r").first().size)
        assertEquals(0, decoy.attachments("d").first().size)
    }

    @Test fun titleOnlyAndClearedNotesPersistInSeparateVaults() = runBlocking {
        val realId = real.saveText("real", "Only title", "", null)
        val decoyId = decoy.saveText("decoy", "Decoy title", "", null)
        assertEquals("Only title", real.getById(realId)?.title)
        assertEquals("Decoy title", decoy.getById(decoyId)?.title)
        assertNull(real.getById(decoyId))
        assertNull(decoy.getById(realId))
        real.saveText(realId, "", "", null)
        decoy.saveText(decoyId, "", "", null)
        assertEquals("", real.getById(realId)?.title)
        assertEquals("", decoy.getById(decoyId)?.text)
    }

    @Test fun noteWhitespaceSurvivesSaveAndReopenInBothVaults() = runBlocking {
        val title = "  title  "
        val text = "\n  indented text  \n\n"
        real.saveText("spaced-real", title, text, null)
        decoy.saveText("spaced-decoy", title, text, null)
        assertEquals(title, real.getById("spaced-real")?.title)
        assertEquals(text, real.getById("spaced-real")?.text)
        assertEquals(title, decoy.getById("spaced-decoy")?.title)
        assertEquals(text, decoy.getById("spaced-decoy")?.text)
    }

    @Test fun checklistsUpdateSameIdAndPreserveIndependentValues() = runBlocking {
        val first = listOf(ChecklistItem("one"))
        val latest = listOf(ChecklistItem("two", checked = true))
        real.saveChecklist("same", "real", first, null)
        real.saveChecklist("same", "real newer", latest, null)
        decoy.saveChecklist("same", "decoy", first, null)
        assertEquals(ChecklistData("real newer", latest), real.getById("same")?.checklist())
        assertEquals(ChecklistData("decoy", first), decoy.getById("same")?.checklist())
        assertEquals(1, db.noteDao().observeAll().first().size)
    }

    @Test fun attachmentsNeedParentsAndAreRemovedWithTheirOwnNote() = runBlocking {
        try { real.addAttachment("missing", "image", "image/png", byteArrayOf(1)); fail("missing real parent accepted") }
        catch (_: IllegalStateException) { }
        try { decoy.addAttachment("missing", "voice", "audio/mp4", byteArrayOf(2)); fail("missing decoy parent accepted") }
        catch (_: IllegalStateException) { }
        real.saveText("r", "", "body", null)
        decoy.saveText("d", "", "body", null)
        val realAttachment = real.addAttachment("r", "image", "image/png", byteArrayOf(1, 2, 3))
        val decoyAttachment = decoy.addAttachment("d", "voice", "audio/mp4", byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(1, 2, 3), real.attachments("r").first().single().data)
        assertArrayEquals(byteArrayOf(4, 5), decoy.attachments("d").first().single().data)
        real.delete("r")
        assertNull(db.noteAttachmentDao().getById(realAttachment))
        assertNotNull(db.decoyNoteAttachmentDao().getById(decoyAttachment))
        decoy.delete("d")
        assertNull(db.decoyNoteAttachmentDao().getById(decoyAttachment))
    }

    @Test fun attachmentAndMarkerCommitOrRollbackTogetherInBothVaults() = runBlocking {
        for (isDecoy in listOf(false, true)) {
            val noteId = if (isDecoy) "atomic-decoy" else "atomic-real"
            if (isDecoy) decoy.saveText(noteId, "Before", "original", null)
            else real.saveText(noteId, "Before", "original", null)
            val table = if (isDecoy) "decoy_notes" else "notes"
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER fail_marker_save BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'injected'); END")
            try {
                try {
                    if (isDecoy) decoy.addAttachmentAndSaveText(noteId, "atomic-att", "image", "image/png", byteArrayOf(1), "After", "[[image:atomic-att]]", null)
                    else real.addAttachmentAndSaveText(noteId, "atomic-att", "image", "image/png", byteArrayOf(1), "After", "[[image:atomic-att]]", null)
                    fail("expected marker write failure")
                } catch (_: android.database.sqlite.SQLiteException) { }
                val note = if (isDecoy) decoy.getById(noteId) else real.getById(noteId)
                assertEquals("original", note?.text)
                assertEquals("Before", note?.title)
                assertTrue(if (isDecoy) decoy.attachments(noteId).first().isEmpty() else real.attachments(noteId).first().isEmpty())
            } finally { sql.execSQL("DROP TRIGGER fail_marker_save") }
            if (isDecoy) decoy.addAttachmentAndSaveText(noteId, "atomic-att", "image", "image/png", byteArrayOf(1), "After", "[[image:atomic-att]]", null)
            else real.addAttachmentAndSaveText(noteId, "atomic-att", "image", "image/png", byteArrayOf(1), "After", "[[image:atomic-att]]", null)
            val note = if (isDecoy) decoy.getById(noteId) else real.getById(noteId)
            assertEquals("[[image:atomic-att]]", note?.text)
            assertEquals("After", note?.title)
            assertEquals(1, if (isDecoy) decoy.attachments(noteId).first().size else real.attachments(noteId).first().size)
        }
    }

    @Test fun unreadableTitlesRejectEditingWithoutReplacingStoredCiphertext() = runBlocking {
        real.saveText("r", "title", "body", null)
        decoy.saveText("d", "title", "body", null)
        val realRow = db.noteDao().getById("r")!!
        val decoyRow = db.decoyNoteDao().getById("d")!!
        db.noteDao().upsert(realRow.copy(titleIv = byteArrayOf(0)))
        db.decoyNoteDao().upsert(decoyRow.copy(titleIv = byteArrayOf(0)))
        assertNull(real.getById("r"))
        assertNull(decoy.getById("d"))
        assertArrayEquals(realRow.ciphertext, db.noteDao().getById("r")!!.ciphertext)
        assertArrayEquals(decoyRow.ciphertext, db.decoyNoteDao().getById("d")!!.ciphertext)
    }

    @Test fun failedNoteDeletionRollsBackAttachmentDeletionInBothVaults() = runBlocking {
        real.saveText("r", "", "body", null)
        decoy.saveText("d", "", "body", null)
        val realAttachment = real.addAttachment("r", "image", "image/png", byteArrayOf(1))
        val decoyAttachment = decoy.addAttachment("d", "image", "image/png", byteArrayOf(2))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_real BEFORE DELETE ON notes BEGIN SELECT RAISE(ABORT, 'injected'); END")
        sql.execSQL("CREATE TRIGGER fail_decoy BEFORE DELETE ON decoy_notes BEGIN SELECT RAISE(ABORT, 'injected'); END")
        var realFailed = false
        var decoyFailed = false
        try { real.delete("r") } catch (_: Exception) { realFailed = true }
        try { decoy.delete("d") } catch (_: Exception) { decoyFailed = true }
        assertTrue(realFailed)
        assertTrue(decoyFailed)
        assertNotNull(db.noteDao().getById("r"))
        assertNotNull(db.noteAttachmentDao().getById(realAttachment))
        assertNotNull(db.decoyNoteDao().getById("d"))
        assertNotNull(db.decoyNoteAttachmentDao().getById(decoyAttachment))
    }


    @Test fun bookmarkFoldersCannotBeCreatedDuringWipeAndRecoverAfterward() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            val bookmarks = BookmarksRepository(db.bookmarkDao(), NotesCrypto(), coordinator)
            coordinator.pauseAndDrain()
            var rejected = false
            try { bookmarks.createFolder("secret") } catch (_: java.io.IOException) { rejected = true }
            assertTrue(rejected)
            // Even subscribing while blocked must not recreate the default folder.
            assertTrue(bookmarks.folders.first().isEmpty())
            assertEquals(0, db.bookmarkDao().folderCount())
            val afterWipe = async { withTimeout(5_000) { bookmarks.folders.first { it.isNotEmpty() } } }
            coordinator.resumeAfterWipe()
            assertEquals("bookmarks", afterWipe.await().single().name)
            assertEquals("secret", bookmarks.createFolder("secret").name)
            assertEquals(2, db.bookmarkDao().folderCount())
        } finally { owner.cancel() }
    }


    @Test fun failedFolderDeletionKeepsNotesInTheirOriginalFolders() = runBlocking {
        val realFolder = real.addFolder("real folder")
        val decoyFolder = decoy.addFolder("decoy folder")
        real.saveText("r", "", "body", realFolder)
        decoy.saveText("d", "", "body", decoyFolder)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_real_folder BEFORE DELETE ON note_folders BEGIN SELECT RAISE(ABORT, 'injected'); END")
        sql.execSQL("CREATE TRIGGER fail_decoy_folder BEFORE DELETE ON decoy_note_folders BEGIN SELECT RAISE(ABORT, 'injected'); END")
        var realFailed = false
        var decoyFailed = false
        try { real.deleteFolder(realFolder) } catch (_: Exception) { realFailed = true }
        try { decoy.deleteFolder(decoyFolder) } catch (_: Exception) { decoyFailed = true }
        assertTrue(realFailed)
        assertTrue(decoyFailed)
        assertEquals(realFolder, db.noteDao().getById("r")!!.folderId)
        assertEquals(decoyFolder, db.decoyNoteDao().getById("d")!!.folderId)
        assertNotNull(db.noteFolderDao().getById(realFolder))
        assertNotNull(db.decoyNoteFolderDao().getById(decoyFolder))
    }


    @Test fun unreadableCalendarDescriptionIsNotPresentedAsEditableBlank() = runBlocking {
        val calendar = social.tbone.calendar.CalendarRepository(db.calendarEventDao(), social.tbone.calendar.CalendarCrypto(), noteWrites)
        calendar.saveEvent("event", "title", "private description", 1L, 2L, false,
            social.tbone.calendar.RepeatUnit.NONE, 0, null)
        val row = db.calendarEventDao().getById("event")!!
        db.calendarEventDao().upsert(row.copy(descriptionIv = byteArrayOf(0)))
        assertNull(calendar.getById("event"))
        assertArrayEquals(row.descriptionCiphertext, db.calendarEventDao().getById("event")!!.descriptionCiphertext)
    }


    @Test fun bookmarkFlowHidesExistingNamesWhileWipeIsPaused() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            val bookmarks = BookmarksRepository(db.bookmarkDao(), NotesCrypto(), coordinator)
            bookmarks.createFolder("private folder")
            coordinator.pauseAndDrain()
            assertTrue(bookmarks.folders.first().isEmpty())
            coordinator.resumeAfterWipe()
            assertTrue(bookmarks.folders.first().any { it.name == "private folder" })
        } finally { owner.cancel() }
    }


    @Test fun staleBookmarkFolderRequestCannotRepopulateAfterWipe() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val coordinator = NoteWriteCoordinator(scope)
            val bookmarks = BookmarksRepository(db.bookmarkDao(), NotesCrypto(), coordinator)
            val stale = bookmarks.currentWipeEpoch()
            assertTrue(coordinator.runWipe(NoteWriteCoordinator.WipeKind.TOOLBOX) {
                coordinator.pauseAndDrain()
                db.clearAllTables()
                true
            })
            try { bookmarks.createFolder("old private folder", stale); fail("stale folder created") }
            catch (_: java.io.IOException) { }
            assertEquals(0, db.bookmarkDao().folderCount())
            assertEquals("new", bookmarks.createFolder("new").name)
        } finally { scope.cancel() }
    }

    @Test fun bookmarkContentsAndLookupsAreHiddenDuringWipe() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val coordinator = NoteWriteCoordinator(scope)
            val crypto = NotesCrypto()
            val bookmarks = BookmarksRepository(db.bookmarkDao(), crypto, coordinator)
            val folder = bookmarks.createFolder("private")
            val eventJson = """{"id":"id","pubkey":"pubkey","created_at":1,"kind":1,"tags":[],"content":"private bookmark","sig":"sig"}"""
            val (ciphertext, iv) = crypto.encrypt(eventJson)
            db.bookmarkDao().upsertBookmark(social.tbone.db.BookmarkEntity("b", folder.id, ciphertext, iv, 0.0, 1L))
            assertEquals("private bookmark", bookmarks.observeBookmarks(folder.id).first().single().event.content)
            coordinator.pauseAndDrain()
            assertTrue(bookmarks.observeBookmarks(folder.id).first().isEmpty())
            try { bookmarks.getBookmarks(folder.id); fail("bookmark read allowed during wipe") }
            catch (_: java.io.IOException) { }
            try { bookmarks.findEvent("id"); fail("bookmark lookup allowed during wipe") }
            catch (_: java.io.IOException) { }
            coordinator.resumeAfterWipe()
            assertEquals("private bookmark", bookmarks.getBookmarks(folder.id).single().event.content)
        } finally { scope.cancel() }
    }


    @Test fun encryptedBackupRestoreRoundTripAndTamperRollback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backupFile = java.io.File(context.cacheDir, "integrity-${java.util.UUID.randomUUID()}.tbbackup")
        val tamperedFile = java.io.File(context.cacheDir, "integrity-${java.util.UUID.randomUUID()}-bad.tbbackup")
        try {
            val coordinator = NoteWriteCoordinator(owner)
            val backups = social.tbone.backup.EncryptedBackupRepository(
                context, db, db.noteDao(), db.noteFolderDao(), db.noteAttachmentDao(), db.calendarEventDao(),
                NotesCrypto(), social.tbone.calendar.CalendarCrypto(),
                social.tbone.calendar.CalendarAlarmScheduler(context), coordinator,
            )
            real.saveText("r", "original title", "original body", null)
            val attachment = real.addAttachment("r", "image", "image/png", byteArrayOf(3, 2, 1))
            backups.exportTo(android.net.Uri.fromFile(backupFile), "test passphrase")
            real.saveText("r", "changed title", "changed body", null)
            val corrupted = backupFile.readBytes()
            corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
            tamperedFile.writeBytes(corrupted)
            var failed = false
            try { backups.importFrom(android.net.Uri.fromFile(tamperedFile), "test passphrase") }
            catch (_: Exception) { failed = true }
            assertTrue("tampered backup must be rejected", failed)
            assertEquals("changed body", real.getById("r")?.text)
            assertNotNull(db.noteAttachmentDao().getById(attachment))
            coordinator.pauseAndDrain()
            var blocked = false
            try { backups.importFrom(android.net.Uri.fromFile(backupFile), "test passphrase") }
            catch (_: java.io.IOException) { blocked = true }
            assertTrue("restore should be blocked during wipe", blocked)
            assertEquals("changed body", real.getById("r")?.text)
            coordinator.resumeAfterWipe()
            backups.importFrom(android.net.Uri.fromFile(backupFile), "test passphrase")
            assertEquals("original body", real.getById("r")?.text)
            assertEquals("original title", real.getById("r")?.title)
            assertArrayEquals(byteArrayOf(3, 2, 1), real.attachments("r").first().single().data)
        } finally {
            owner.cancel()
            backupFile.delete()
            tamperedFile.delete()
        }
    }


    @Test fun noteAndCalendarFlowsHideDuringWipeAndRecoverAfterwards() = runBlocking {
        real.saveText("hidden-real", "real", "body", null)
        decoy.saveText("hidden-decoy", "decoy", "body", null)
        val calendar = social.tbone.calendar.CalendarRepository(db.calendarEventDao(), social.tbone.calendar.CalendarCrypto(), noteWrites)
        calendar.saveEvent("hidden-event", "private", "", 1L, 2L, false,
            social.tbone.calendar.RepeatUnit.NONE, 0, null)
        assertEquals(1, real.notes.first().size)
        assertEquals(1, decoy.notes.first().size)
        assertEquals(1, calendar.events.first().size)
        assertTrue(noteWrites.requestWipe(NoteWriteCoordinator.WipeKind.TOOLBOX))
        assertTrue(real.notes.first().isEmpty())
        assertTrue(decoy.notes.first().isEmpty())
        assertTrue(calendar.events.first().isEmpty())
        assertEquals(NotesStats(), real.stats.first())
        assertTrue(noteWrites.runWipe(NoteWriteCoordinator.WipeKind.TOOLBOX) { true })
        assertEquals(1, real.notes.first().size)
        assertEquals(1, decoy.notes.first().size)
        assertEquals(1, calendar.events.first().size)
    }

}
