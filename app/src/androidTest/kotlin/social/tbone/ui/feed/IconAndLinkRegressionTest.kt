package social.tbone.ui.feed

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.FeedRegressionActivity
import social.tbone.stealth.StealthManager
import social.tbone.ui.settings.IconSettings
import social.tbone.ui.theme.BonyTheme

@RunWith(AndroidJUnit4::class)
class IconAndLinkRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<FeedRegressionActivity>()

    @Test fun clickingRightHalfOfLinkGlyphOpensTheLink() {
        var clicked = 0
        compose.setContent { BonyTheme { NoteBodyText(content = "#example", style = TextStyle(fontSize = 20.sp), onHashtagClick = { clicked++ }) } }
        val node = compose.onNodeWithText("#example")
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val rect = layouts.single().getBoundingBox(3)
        node.performTouchInput { click(Offset(rect.right - 0.2f, rect.center.y)) }
        compose.runOnIdle { assertEquals(1, clicked) }
    }

    @Test fun iconPresetSavesAndCanBeRestored() {
        val context = compose.activity.applicationContext
        val manager = StealthManager(context)
        val old = manager.selectedAlias()
        val oldSplash = context.getSharedPreferences("launcher_appearance", Context.MODE_PRIVATE).getString("splash", null)
        try {
            manager.setNotepadFront(false)
            compose.setContent { BonyTheme { IconSettings() } }
            compose.onNodeWithText("○ Colors").performClick()
            compose.onNodeWithText("○ Cream").performClick()
            compose.onNodeWithText("○ Gold").performClick()
            compose.onNodeWithText("SAVE ICON").performClick()
            compose.runOnIdle {
                assertEquals("LauncherColor11", manager.selectedAlias())
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, "social.tbone.LauncherColor11")))
            }
        } finally {
            manager.setRegularIcon(old)
            context.getSharedPreferences("launcher_appearance", Context.MODE_PRIVATE).edit().apply {
                if (oldSplash == null) remove("splash") else putString("splash", oldSplash)
            }.commit()
        }
    }

    @Test fun collapsedHeightAppliesAcrossTextAndInlineQuote() {
        val id = "b".repeat(64)
        val quote = Event(id, "c".repeat(64), 1_700_000_000, 1, emptyList(), "quoted body", "")
        val content = "first paragraph\nnostr:${Nip19.hexToNote(id)}\n" + "remaining text ".repeat(20)
        compose.setContent { BonyTheme {
            NoteBodyText(content = content, style = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
                modifier = Modifier.testTag("outer-note"), maxLines = 3, expandable = true,
                quotedEvents = mapOf(id to quote))
        } }
        val collapsedHeight = compose.onNodeWithTag("outer-note").fetchSemanticsNode().size.height
        compose.onNodeWithText("show more →").performClick()
        val expandedHeight = compose.onNodeWithTag("outer-note").fetchSemanticsNode().size.height
        assertTrue("expanded note should be taller than collapsed note", expandedHeight > collapsedHeight)
    }

    @Test fun expandAffordanceResetsWhenWidthIncreases() {
        val wide = mutableStateOf(false)
        compose.setContent { BonyTheme {
            NoteBodyText(content = "A moderate length sentence that wraps on narrow screens",
                style = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
                modifier = Modifier.width(if (wide.value) 1000.dp else 100.dp),
                maxLines = 2, expandable = true)
        } }
        compose.onNodeWithText("show more →").assertExists()
        compose.runOnIdle { wide.value = true }
        // The activity may constrain width; a single shorter string fits its screen.
        compose.onNodeWithText("show more →").assertDoesNotExist()
    }
}
