package social.tbone.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.FeedRegressionActivity
import social.tbone.ui.theme.BonyTheme

@RunWith(AndroidJUnit4::class)
class HideAccountDialogTest {
    @get:Rule val compose = createAndroidComposeRule<FeedRegressionActivity>()

    @Test fun hidingRequiresMatchingConfirmation() {
        var submitted: String? = null
        compose.setContent { BonyTheme { HideAccountDialog(onCancel = {}, onHide = { submitted = it }) } }
        compose.onNodeWithText("HIDE").assertIsNotEnabled()
        compose.onNodeWithText("PIN (any length)").performTextInput("1234")
        compose.onNodeWithText("Confirm PIN").performTextInput("1235")
        compose.onNodeWithText("HIDE").assertIsNotEnabled()
        assertNull(submitted)
        compose.onNodeWithText("Confirm PIN").performTextInput("4")
        // Replace instead of appending to demonstrate a correction.
        compose.onNodeWithText("Confirm PIN").performTextReplacement("1234")
        compose.onNodeWithText("HIDE").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("1234", submitted) }
    }
}
