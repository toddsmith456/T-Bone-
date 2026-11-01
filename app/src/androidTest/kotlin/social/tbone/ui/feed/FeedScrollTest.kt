package social.tbone.ui.feed

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import social.tbone.FeedRegressionActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
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
    @Test fun longInlineQuoteChainIsBounded() {
        val ids = (1..40).map { it.toString(16).padStart(64, '0') }
        val notes = ids.mapIndexed { index, id ->
            val next = ids.getOrNull(index + 1)
            val body = "chain-node-$index" + (next?.let { "\nnostr:${Nip19.hexToNote(it)}" } ?: "")
            Event(id, "a".repeat(64), 1_700_000_000, 1, emptyList(), body, "")
        }
        val lookup = notes.associateBy { it.id }
        compose.setContent { BonyTheme { QuotedNoteCard(event = notes.first(), profile = null, quotedEvents = lookup) } }
        compose.onNodeWithText("chain-node-0", substring = true).assertExists()
        compose.onNodeWithText("chain-node-1", substring = true).assertExists()
        compose.onNodeWithText("chain-node-10", substring = true).assertDoesNotExist()
        compose.onNodeWithText("↗ open note", substring = true).assertExists()
    }

    @Test fun selfReferentialInlineQuoteDoesNotRecurse() {
        val id = "b".repeat(64)
        val event = Event(id, "a".repeat(64), 1_700_000_000, 1, emptyList(),
            "self reference nostr:${Nip19.hexToNote(id)}", "")
        compose.setContent { BonyTheme { QuotedNoteCard(event = event, profile = null, quotedEvents = mapOf(id to event)) } }
        compose.onNodeWithText("↗ open note", substring = true).assertExists()
    }

    @Test fun videoPlaceholdersLoadOnlyOnTapAndOnlyOneIsSelected() {
        compose.setContent {
            BonyTheme {
                NoteMediaContent(listOf(
                    MediaItem.Video("file:///nonexistent-first.mp4"),
                    MediaItem.Video("file:///nonexistent-second.mp4"),
                ))
            }
        }
        compose.onAllNodesWithTag("video-placeholder").assertCountEquals(2)
        compose.onAllNodesWithTag("active-inline-video").assertCountEquals(0)
        compose.onAllNodesWithTag("video-placeholder")[0].performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("active-inline-video").assertCountEquals(1)
        compose.onAllNodesWithTag("video-placeholder").assertCountEquals(1)
        compose.onAllNodesWithTag("video-placeholder")[0].performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("active-inline-video").assertCountEquals(1)
        compose.onAllNodesWithTag("video-placeholder").assertCountEquals(1)
    }

    @Test fun repostAndQuoteShareOneMenu() {
        val event = Event("1".repeat(64), "a".repeat(64), 1_700_000_000, 1, emptyList(), "test", "")
        var reposts = 0
        var quotes = 0
        compose.setContent {
            BonyTheme {
                NoteCard(event = event, profile = null,
                    onBoost = { reposts++ }, onQuote = { quotes++ })
            }
        }
        compose.onNodeWithContentDescription("Repost or quote").performClick()
        compose.onNodeWithText("Repost").assertExists().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, reposts) }
        compose.onNodeWithContentDescription("Repost or quote").performClick()
        compose.onNodeWithText("Quote").assertExists().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, quotes) }
    }

    @Test fun quoteAfterProfileMentionIsRendered() {
        val id = "b".repeat(64)
        val target = Event(id, "c".repeat(64), 1_700_000_000, 1, emptyList(), "quoted target body", "")
        val profile = Nip19.hexToNpub("d".repeat(64))
        val note = Event("e".repeat(64), "f".repeat(64), 1_700_000_000, 1, emptyList(),
            "hello nostr:$profile nostr:${Nip19.hexToNote(id)}", "")
        compose.setContent { BonyTheme { NoteCard(event = note, profile = null, quotedEvent = target,
            quotedEventId = id, quotedEvents = mapOf(id to target)) } }
        compose.onNodeWithText("quoted target body", substring = true).assertExists()
    }

    @Test fun longPressCopiesOriginalLink() {
        val raw = "https://example.org/path?utm_source=friend&x=1"
        compose.setContent { BonyTheme { NoteBodyText(content = raw, style = social.tbone.ui.theme.BonyType.body) } }
        compose.onNodeWithText("https://example.org/path?x=1", substring = true)
            .performTouchInput { longClick(center) }
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            org.junit.Assert.assertEquals(raw, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test fun longPressCopiesEmbeddedQuoteReference() {
        val id = "9".repeat(64)
        val raw = "nostr:${Nip19.hexToNote(id)}"
        val quote = Event(id, "c".repeat(64), 1_700_000_000, 1, emptyList(), "quoted for copy", "")
        compose.setContent { BonyTheme { NoteBodyText(content = raw, style = social.tbone.ui.theme.BonyType.body,
            quotedEvents = mapOf(id to quote), onThreadClick = {}) } }
        compose.onNodeWithText("quoted for copy", substring = true).performTouchInput { longClick(center) }
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            org.junit.Assert.assertEquals(raw, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }
}
