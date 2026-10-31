package social.tbone.ui.feed

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import social.tbone.FeedRegressionActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.nostr.Event
import social.tbone.ui.theme.BonyTheme

/** Exercises real note composition and disposal as items enter/leave a scrolling feed. */
@RunWith(AndroidJUnit4::class)
class FeedScrollTest {
    @get:Rule val compose = createAndroidComposeRule<FeedRegressionActivity>()

    @Test fun scrollAcrossNormalMediaAndMalformedEvents() {
        val notes = (0 until 120).map { index ->
            val tags = if (index % 9 == 0) listOf(JsonArray(listOf(JsonPrimitive("p"), JsonObject(emptyMap())))) else emptyList()
            val content = if (index % 7 == 0) "A video https://example.org/$index.mp4" else "Feed test $index #nostr"
            Event(index.toString(16).padStart(64, '0'), (index + 1).toString(16).padStart(64, '0'),
                if (index % 11 == 0) Long.MIN_VALUE + 1 else 1_700_000_000L - index,
                1, tags, content, "")
        }
        compose.setContent {
            BonyTheme {
                LazyColumn(Modifier.testTag("feed")) {
                    items(notes, key = { it.id }) { note -> NoteCard(event = note, profile = null) }
                }
            }
        }
        for (index in 0 until notes.size step 3) {
            compose.onNodeWithTag("feed").performScrollToIndex(index)
            compose.waitForIdle()
        }
        for (index in notes.indices.reversed().step(4)) {
            compose.onNodeWithTag("feed").performScrollToIndex(index)
            compose.waitForIdle()
        }
    }
}
