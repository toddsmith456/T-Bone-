package social.tbone.notes

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.FeedRegressionActivity
import social.tbone.db.AppDatabase
import social.tbone.ui.decoy.DecoyNoteEditorScreen
import social.tbone.ui.notes.NoteEditorScreen
import social.tbone.ui.notes.NotesViewModel
import social.tbone.ui.theme.BonyTheme

/** In-memory Room only: no user's vault or files are modified. */
@RunWith(AndroidJUnit4::class)
class NoteEditorSaveTest {
    @get:Rule val compose = createAndroidComposeRule<FeedRegressionActivity>()

    @Test fun regularEditorSavesNewerEditBeforeLeaving() = exercise(decoy = false)
    @Test fun decoyEditorSavesNewerEditBeforeLeaving() = exercise(decoy = true)

    private fun exercise(decoy: Boolean) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writes = NoteWriteCoordinator(owner)
        val crypto = NotesCrypto()
        val regular = NotesRepository(db.noteDao(), db.noteFolderDao(), db.noteAttachmentDao(), crypto, writes)
        val hidden = DecoyNotesRepository(db.decoyNoteDao(), db.decoyNoteFolderDao(), db.decoyNoteAttachmentDao(), crypto, writes)
        val regularVm = NotesViewModel(regular, crypto, writes)
        val hiddenVm = DecoyNotesViewModel(hidden, crypto, writes)
        val left = AtomicBoolean(false)
        val showing = mutableStateOf(true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            runBlocking {
                if (decoy) hidden.saveText("note-id", "old title", "body", null)
                else regular.saveText("note-id", "old title", "body", null)
            }
            compose.setContent { BonyTheme {
                if (showing.value) {
                    val back = { left.set(true); showing.value = false }
                    if (decoy) DecoyNoteEditorScreen(id = "note-id", onBack = back, onShare = {}, viewModel = hiddenVm)
                    else NoteEditorScreen(id = "note-id", onBack = back, onShare = {}, viewModel = regularVm)
                }
            } }
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("old title")).fetchSemanticsNodes().isNotEmpty() }
            writes.submit { started.complete(Unit); release.await() }
            runBlocking { withTimeout(5_000) { started.await() } }
            compose.onNodeWithText("old title").performTextReplacement("first title")
            compose.onNodeWithText("←").performClick()
            compose.onNodeWithText("first title").performTextReplacement("newer title")
            assertEquals(false, left.get())
            release.complete(Unit)
            compose.waitUntil(10_000) { left.get() }
            val saved = runBlocking { if (decoy) hidden.getById("note-id") else regular.getById("note-id") }
            assertEquals("newer title", saved?.title)
            assertEquals("body", saved?.text)
        } finally {
            release.complete(Unit)
            owner.cancel()
            db.close()
        }
    }
}
