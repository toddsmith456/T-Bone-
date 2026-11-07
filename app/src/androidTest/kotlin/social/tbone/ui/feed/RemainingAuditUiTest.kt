package social.tbone.ui.feed

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.FeedRegressionActivity
import social.tbone.nostr.Nip19
import social.tbone.ui.StartupGate
import social.tbone.ui.media.InlineAudioPlayer
import social.tbone.ui.media.pauseAudioOnStop
import social.tbone.ui.media.rememberAudioPlayer
import social.tbone.ui.settings.IconSettings
import social.tbone.ui.theme.BonyTheme

@RunWith(AndroidJUnit4::class)
class RemainingAuditUiTest {
    @get:Rule val compose = createAndroidComposeRule<FeedRegressionActivity>()

    @Test fun startupCoverRemovesUnderlyingActionsUntilReady() {
        val starting = mutableStateOf(true)
        var taps = 0
        compose.setContent { StartupGate(starting.value, cover = { Box(Modifier.fillMaxSize().testTag("cover")) }) {
            Text("Underlying action", Modifier.clickable { taps++ })
        } }
        compose.onNodeWithTag("cover").assertExists()
        compose.onNodeWithText("Underlying action").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, taps); starting.value = false }
        compose.onNodeWithText("Underlying action").performClick()
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun nestedAddressNeverShowsLoadingAndCopiesReference() {
        val context = compose.activity
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = clipboard.primaryClip
        val raw = "nostr:naddr-example"
        try {
            compose.setContent { BonyTheme { AddressQuote(raw, Nip19.Address("article", "a".repeat(64), 30023),
                emptyMap(), emptyMap(), 2, emptySet(), null, null, null) } }
            compose.onNodeWithText("loading quoted note… · tap to copy").assertDoesNotExist()
            compose.onNodeWithText("nested quote hidden · tap to copy").performClick()
            compose.runOnIdle { assertEquals(raw, clipboard.primaryClip?.getItemAt(0)?.text.toString()) }
        } finally { if (previous == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(previous) }
    }

    @Test fun multipleHashtagsAndLinkExposeIndependentAccessibilityActions() {
        val tags = mutableListOf<String>()
        compose.setContent { BonyTheme { NoteBodyText("#first and https://example.com/x #second", TextStyle(fontSize = 18.sp), onHashtagClick = { tags.add(it) }) } }
        val node = compose.onNodeWithText("#first and https://example.com/x #second")
        val actions = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertTrue(actions.any { it.label == "Copy https://example.com/x" })
        compose.runOnIdle {
            assertTrue(actions.single { it.label == "Open hashtag first" }.action())
            assertTrue(actions.single { it.label == "Open hashtag second" }.action())
            assertEquals(listOf("first", "second"), tags)
        }
    }

    @Test fun colorSelectorsAreRadioButtonsAtLargeFontScale() {
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) { BonyTheme { IconSettings() } } }
        compose.onNodeWithText("○ Colors").performClick()
        val node = compose.onNodeWithText("● Colors")
        node.assertIsSelected()
        assertEquals(Role.RadioButton, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        compose.onNodeWithText("○ Cream").performClick()
        compose.onNodeWithText("● Cream").assertIsSelected()
    }

    @Test fun audioStopObserverPausesAndDetaches() {
        compose.runOnIdle {
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            var pauses = 0
            val detach = pauseAudioOnStop(owner.lifecycle) { pauses++ }
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(1, pauses)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertEquals(1, pauses)
            detach()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(1, pauses)
        }
    }

    @Test fun audioPlayControlHasAccessibleLabel() {
        compose.setContent { BonyTheme { InlineAudioPlayer("https://example.com/audio.mp3") } }
        compose.onNodeWithContentDescription("Play audio").assertExists()
    }

    @Test fun inlinePlayerIsReleasedWhenCompositionIsRemoved() {
        val shown = mutableStateOf(true)
        var instance: androidx.media3.exoplayer.ExoPlayer? = null
        compose.setContent {
            if (shown.value) {
                val (player, _) = rememberAudioPlayer("file:///dev/null", 0)
                SideEffect { if (player != null) instance = player }
            }
        }
        compose.waitForIdle()
        val created = requireNotNull(instance)
        compose.runOnIdle { assertFalse(created.isReleased); shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(created.isReleased) }
    }

    @Test fun collapsedTextReevaluatesAtLargerAndSmallerFontScales() {
        val scale = mutableStateOf(1.8f)
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) { BonyTheme {
            NoteBodyText("A moderate sentence that wraps when text is enlarged", TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
                modifier = Modifier.width(260.dp), maxLines = 2, expandable = true)
        } } }
        compose.onNodeWithText("show more →").assertExists()
        compose.runOnIdle { scale.value = 0.7f }
        compose.onNodeWithText("show more →").assertDoesNotExist()
    }
}
