package social.tbone.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val crypto = NotesCrypto()
        real = NotesRepository(db.noteDao(), db.noteFolderDao(), db.noteAttachmentDao(), crypto)
        decoy = DecoyNotesRepository(db.decoyNoteDao(), db.decoyNoteFolderDao(), db.decoyNoteAttachmentDao(), crypto)
    }

    @After fun teardown() = db.close()

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

}
