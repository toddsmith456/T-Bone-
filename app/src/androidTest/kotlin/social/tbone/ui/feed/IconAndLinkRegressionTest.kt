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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
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

    @Test fun hashtagAccessibilityActionTracksCurrentHandler() {
        val enabled = mutableStateOf(false)
        var clicks = 0
        compose.setContent { BonyTheme {
            val handler: ((String) -> Unit)? = if (enabled.value) ({ clicks++ }) else null
            NoteBodyText(content = "#example", style = TextStyle(fontSize = 20.sp), onHashtagClick = handler)
        } }
        val node = compose.onNodeWithText("#example")
        val before = node.fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions).orEmpty()
        assertTrue(before.none { it.label == "Open hashtag example" })
        compose.runOnIdle { enabled.value = true }
        val after = node.fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions).orEmpty()
        val open = after.single { it.label == "Open hashtag example" }
        compose.runOnIdle {
            assertTrue(open.action())
            assertEquals(1, clicks)
        }
    }


    @Test fun pinKeypadIsDisabledWhileVerificationRuns() {
        val enabled = mutableStateOf(false)
        var entered = ""
        compose.setContent { BonyTheme {
            social.tbone.ui.lock.PinKeypad(onDigit = { entered += it }, onBackspace = { entered = entered.dropLast(1) }, enabled = enabled.value)
        } }
        compose.onNodeWithText("1").assertIsNotEnabled()
        compose.onNodeWithText("←").assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("1").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("1", entered) }
    }


    @Test fun uploadedLoadingImageDecodesAndBadInputPreservesIt() {
        val base = compose.activity.applicationContext
        val dir = java.io.File(base.cacheDir, "splash-test-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val prefsName = "splash-test-" + java.util.UUID.randomUUID()
        val isolated = object : android.content.ContextWrapper(base) {
            override fun getFilesDir(): java.io.File = dir
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences =
                base.getSharedPreferences(prefsName, mode)
        }
        val source = java.io.File(dir, "source.png")
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(2048, 1024, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.RED)
            source.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            social.tbone.ui.settings.saveSplashImage(isolated, android.net.Uri.fromFile(source))
            val saved = java.io.File(dir, "splash-image.png")
            assertTrue(saved.isFile)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(saved.absolutePath, bounds)
            assertTrue(bounds.outWidth in 1..1024)
            assertTrue(bounds.outHeight in 1..1024)
            assertEquals("upload", isolated.getSharedPreferences("launcher_appearance", Context.MODE_PRIVATE).getString("splash", null))
            val previous = saved.readBytes()
            val invalid = java.io.File(dir, "invalid.png").apply { writeText("not an image") }
            try {
                social.tbone.ui.settings.saveSplashImage(isolated, android.net.Uri.fromFile(invalid))
                throw AssertionError("bad image should be rejected")
            } catch (_: IllegalArgumentException) { }
            assertTrue(previous.contentEquals(saved.readBytes()))
        } finally {
            base.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
            dir.deleteRecursively()
        }
    }

}
