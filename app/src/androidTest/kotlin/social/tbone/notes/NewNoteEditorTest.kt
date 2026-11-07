package social.tbone.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.FeedRegressionActivity
import social.tbone.db.AppDatabase
import social.tbone.ui.decoy.DecoyNoteEditorScreen
import social.tbone.ui.notes.NoteEditorScreen
import social.tbone.ui.notes.NotesViewModel
import social.tbone.ui.theme.BonyTheme

/** Uses in-memory Room and a fake image-picker result, never user data. */
@RunWith(AndroidJUnit4::class)
class NewNoteEditorTest {
    @get:Rule val compose = createAndroidComposeRule<FeedRegressionActivity>()

    @Test fun normalTitleOnlySavesOnBackAndReopens() = titleOnly(false, false)
    @Test fun decoyTitleOnlySavesOnBackAndReopens() = titleOnly(true, false)
    @Test fun normalTitleOnlyAutosavesAndReopens() = titleOnly(false, true)
    @Test fun decoyTitleOnlyAutosavesAndReopens() = titleOnly(true, true)
    @Test fun normalTitleOnlyReportsFailureAndCanRetry() = titleOnlyFailure(false)
    @Test fun decoyTitleOnlyReportsFailureAndCanRetry() = titleOnlyFailure(true)
    @Test fun normalBlankNoteAcceptsFirstImage() = firstImage(false, null)
    @Test fun decoyBlankNoteAcceptsFirstImage() = firstImage(true, null)
    @Test fun normalBlankNoteCanRetryAfterParentSaveFailure() = firstImage(false, "parent")
    @Test fun decoyBlankNoteCanRetryAfterParentSaveFailure() = firstImage(true, "parent")
    @Test fun normalBlankNoteCanRetryAfterAttachmentFailure() = firstImage(false, "attachment")
    @Test fun decoyBlankNoteCanRetryAfterAttachmentFailure() = firstImage(true, "attachment")
    @Test fun canceledNormalPickerDoesNotCreateNote() = canceledPicker(false)
    @Test fun canceledDecoyPickerDoesNotCreateNote() = canceledPicker(true)

    @Test fun corruptNormalAttachmentBlocksEditorAndPreservesNote() = corruptAttachment(false)
    @Test fun corruptDecoyAttachmentBlocksEditorAndPreservesNote() = corruptAttachment(true)

    private fun corruptAttachment(decoy: Boolean) {
        val f = Fixture()
        var closed = false
        try {
            val id = "corrupt-" + java.util.UUID.randomUUID()
            val marker = runBlocking {
                val repo = if (decoy) null else f.normal
                if (decoy) f.decoy.saveText(id, "secret title", "initial", null)
                else checkNotNull(repo).saveText(id, "secret title", "initial", null)
                val attachmentId = if (decoy) f.decoy.addAttachment(id, "image", "image/png", byteArrayOf(1, 2, 3))
                    else checkNotNull(repo).addAttachment(id, "image", "image/png", byteArrayOf(1, 2, 3))
                val text = "keep [[image:$attachmentId]] intact"
                if (decoy) f.decoy.saveText(id, "secret title", text, null)
                else checkNotNull(repo).saveText(id, "secret title", text, null)
                val table = if (decoy) "decoy_note_attachments" else "note_attachments"
                f.db.openHelper.writableDatabase.execSQL("UPDATE $table SET ciphertext = X'00' WHERE id = ?", arrayOf(attachmentId))
                text
            }
            compose.setContent { BonyTheme {
                if (decoy) DecoyNoteEditorScreen(id, onBack = { closed = true }, onShare = {}, viewModel = f.decoyVm)
                else NoteEditorScreen(id, onBack = { closed = true }, onShare = {}, viewModel = f.normalVm)
            } }
            compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("note unavailable")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("note unavailable").assertIsDisplayed()
            compose.onNodeWithText("← back").performClick()
            compose.runOnIdle { assertTrue(closed) }
            assertEquals(marker, runBlocking { f.get(decoy, id)?.text })
            assertEquals("secret title", runBlocking { f.get(decoy, id)?.title })
        } finally { f.close() }
    }

    @Test fun laterNormalAttachmentFailureBlocksSaves() = laterAttachmentFailure(false)
    @Test fun laterDecoyAttachmentFailureBlocksSaves() = laterAttachmentFailure(true)

    private fun laterAttachmentFailure(decoy: Boolean) {
        val f = Fixture()
        val showing = mutableStateOf(true)
        try {
            val id = "later-" + java.util.UUID.randomUUID()
            val attachmentId = runBlocking {
                if (decoy) {
                    f.decoy.saveText(id, "Before", "body", null)
                    val att = f.decoy.addAttachment(id, "image", "image/png", byteArrayOf(1, 2, 3))
                    f.decoy.saveText(id, "Before", "body [[image:$att]]", null)
                    att
                } else {
                    f.normal.saveText(id, "Before", "body", null)
                    val att = f.normal.addAttachment(id, "image", "image/png", byteArrayOf(1, 2, 3))
                    f.normal.saveText(id, "Before", "body [[image:$att]]", null)
                    att
                }
            }
            compose.setContent { BonyTheme {
                if (showing.value) {
                    if (decoy) DecoyNoteEditorScreen(id, onBack = { showing.value = false }, onShare = {}, viewModel = f.decoyVm)
                    else NoteEditorScreen(id, onBack = { showing.value = false }, onShare = {}, viewModel = f.normalVm)
                }
            } }
            compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Before")).fetchSemanticsNodes().isNotEmpty() }
            runBlocking {
                if (decoy) {
                    val dao = f.db.decoyNoteAttachmentDao()
                    dao.insert(checkNotNull(dao.getById(attachmentId)).copy(ciphertext = byteArrayOf(0)))
                } else {
                    val dao = f.db.noteAttachmentDao()
                    dao.insert(checkNotNull(dao.getById(attachmentId)).copy(ciphertext = byteArrayOf(0)))
                }
            }
            val warning = "Attachment data unavailable. Changes cannot be saved; copy any unsaved text before leaving."
            compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(warning)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Before").performTextReplacement("Unsaved change")
            compose.onNodeWithText("←").performClick()
            compose.runOnIdle { assertTrue(showing.value) }
            assertEquals("Before", runBlocking { f.get(decoy, id)?.title })
            assertEquals("body [[image:$attachmentId]]", runBlocking { f.get(decoy, id)?.text })
            compose.onNodeWithText("← leave without saving").performClick()
            compose.waitUntil(15_000) { !showing.value }
            assertEquals("Before", runBlocking { f.get(decoy, id)?.title })
        } finally { f.close() }
    }

    private class Fixture {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writes = NoteWriteCoordinator(owner)
        val crypto = NotesCrypto()
        val normal = NotesRepository(db.noteDao(), db.noteFolderDao(), db.noteAttachmentDao(), crypto, writes)
        val decoy = DecoyNotesRepository(db.decoyNoteDao(), db.decoyNoteFolderDao(), db.decoyNoteAttachmentDao(), crypto, writes)
        private val gate = social.tbone.toolbox.VoiceRecordingGate(writes, owner)
        val normalVm = NotesViewModel(normal, crypto, writes, gate)
        val decoyVm = DecoyNotesViewModel(decoy, crypto, writes, gate)
        suspend fun rows(decoyVault: Boolean): List<String> =
            if (decoyVault) db.decoyNoteDao().getAll().map { it.id } else db.noteDao().getAll().map { it.id }
        suspend fun get(decoyVault: Boolean, id: String): NoteUi? =
            if (decoyVault) decoy.getById(id) else normal.getById(id)
        suspend fun attachments(decoyVault: Boolean, id: String): List<NoteAttachmentUi> =
            if (decoyVault) decoy.attachments(id).first() else normal.attachments(id).first()
        fun close() { owner.cancel(); db.close() }
    }

    private fun titleOnly(decoy: Boolean, autosave: Boolean) {
        val f = Fixture()
        val showing = mutableStateOf(true)
        val editorId = mutableStateOf("")
        try {
            compose.setContent { BonyTheme {
                if (showing.value) {
                    if (decoy) DecoyNoteEditorScreen(editorId.value, onBack = { showing.value = false }, onShare = {}, viewModel = f.decoyVm)
                    else NoteEditorScreen(editorId.value, onBack = { showing.value = false }, onShare = {}, viewModel = f.normalVm)
                }
            } }
            compose.onNodeWithText("title").performTextReplacement("Only a title")
            if (autosave) compose.waitUntil(15_000) { runBlocking { f.rows(decoy).size == 1 } }
            compose.onNodeWithText("←").performClick()
            compose.waitUntil(15_000) { !showing.value }
            val id = runBlocking { f.rows(decoy).single() }
            val saved = runBlocking { f.get(decoy, id) }
            assertEquals("Only a title", saved?.title)
            assertEquals("", saved?.text)
            compose.runOnIdle { editorId.value = id; showing.value = true }
            compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Only a title")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Only a title").assertIsDisplayed()
            assertEquals(1, runBlocking { f.rows(decoy).size })
        } finally { f.close() }
    }

    private fun titleOnlyFailure(decoy: Boolean) {
        val f = Fixture()
        val showing = mutableStateOf(true)
        try {
            val table = if (decoy) "decoy_notes" else "notes"
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_title BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'injected'); END")
            compose.setContent { BonyTheme {
                if (showing.value) {
                    if (decoy) DecoyNoteEditorScreen("", onBack = { showing.value = false }, onShare = {}, viewModel = f.decoyVm)
                    else NoteEditorScreen("", onBack = { showing.value = false }, onShare = {}, viewModel = f.normalVm)
                }
            } }
            compose.onNodeWithText("title").performTextReplacement("Retry title")
            compose.onNodeWithText("←").performClick()
            compose.waitUntil(15_000) { if (decoy) f.decoyVm.error.value != null else f.normalVm.error.value != null }
            assertTrue(showing.value)
            assertTrue(runBlocking { f.rows(decoy).isEmpty() })
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_title")
            compose.onNodeWithText("←").performClick()
            compose.waitUntil(15_000) { !showing.value }
            val id = runBlocking { f.rows(decoy).single() }
            assertEquals("Retry title", runBlocking { f.get(decoy, id)?.title })
        } finally { f.close() }
    }

    private fun canceledPicker(decoy: Boolean) {
        val f = Fixture()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
            }
        }
        val resultOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val showing = mutableStateOf(true)
        try {
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultOwner) { BonyTheme {
                if (showing.value) {
                    if (decoy) DecoyNoteEditorScreen("", onBack = { showing.value = false }, onShare = {}, viewModel = f.decoyVm)
                    else NoteEditorScreen("", onBack = { showing.value = false }, onShare = {}, viewModel = f.normalVm)
                }
            } } }
            compose.onNodeWithText("image").performClick()
            compose.onNodeWithText("←").performClick()
            compose.waitUntil(15_000) { !showing.value }
            assertTrue(runBlocking { f.rows(decoy).isEmpty() })
        } finally { f.close() }
    }

    private fun firstImage(decoy: Boolean, failAt: String?) {
        val f = Fixture()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = File.createTempFile("picked-image", ".png", context.cacheDir)
        val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        image.writeBytes(bytes)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(image)))
            }
        }
        val resultOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val showing = mutableStateOf(true)
        val editorId = mutableStateOf("")
        try {
            if (failAt != null) {
                val table = when {
                    failAt == "attachment" && decoy -> "decoy_note_attachments"
                    failAt == "attachment" -> "note_attachments"
                    decoy -> "decoy_notes"
                    else -> "notes"
                }
                f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_first_save BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'injected'); END")
            }
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultOwner) { BonyTheme {
                if (showing.value) {
                    if (decoy) DecoyNoteEditorScreen(editorId.value, onBack = { showing.value = false }, onShare = {}, viewModel = f.decoyVm)
                    else NoteEditorScreen(editorId.value, onBack = { showing.value = false }, onShare = {}, viewModel = f.normalVm)
                }
            } } }
            compose.waitForIdle()
            compose.onNodeWithText("image").performClick()
            if (failAt != null) {
                compose.waitUntil(15_000) { if (decoy) f.decoyVm.error.value != null else f.normalVm.error.value != null }
                val ids = runBlocking { f.rows(decoy) }
                if (failAt == "parent") assertTrue(ids.isEmpty())
                else {
                    assertEquals(1, ids.size)
                    assertEquals("", runBlocking { f.get(decoy, ids.single())?.text })
                    assertTrue(runBlocking { f.attachments(decoy, ids.single()).isEmpty() })
                }
                f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_first_save")
                compose.onNodeWithText("image").performClick()
            }
            compose.waitUntil(15_000) {
                runBlocking { val id = f.rows(decoy).singleOrNull(); id != null && f.get(decoy, id)?.text?.contains("[[image:") == true && f.attachments(decoy, id).size == 1 }
            }
            val id = runBlocking { f.rows(decoy).single() }
            val attachment = runBlocking { f.attachments(decoy, id).single() }
            assertArrayEquals(bytes, attachment.data)
            assertEquals("[[image:${attachment.id}]]", runBlocking { f.get(decoy, id)?.text })
            compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("image attachment")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("←").performClick()
            compose.waitUntil(15_000) { !showing.value }
            compose.runOnIdle { editorId.value = id; showing.value = true }
            compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("image attachment")).fetchSemanticsNodes().isNotEmpty() }
            assertNotNull(runBlocking { f.get(decoy, id) })
            assertEquals(1, runBlocking { f.rows(decoy).size })
        } finally { image.delete(); f.close() }
    }
}
