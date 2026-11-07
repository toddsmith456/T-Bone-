package social.tbone.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.FeedRegressionActivity
import social.tbone.ui.theme.BonyTheme

@RunWith(AndroidJUnit4::class)
class AccountStartupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<FeedRegressionActivity>()

    @Test fun unreadableAccountsShowNonDestructiveGuidance() {
        compose.setContent { BonyTheme { AccountDataUnavailableScreen() } }
        compose.onNodeWithText("Account data could not be read", substring = true).assertIsDisplayed()
        compose.onNodeWithText("before clearing app data or reinstalling", substring = true).assertIsDisplayed()
    }
}
