package social.tbone.notifications

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.nostr.Nip19

@RunWith(AndroidJUnit4::class)
class NavigationIntentTest {
    @Test fun notificationAndColdStartUrisQueueInOrderUntilCollected() = runBlocking {
        val handler = DeepLinkHandler()
        val thread = "ab".repeat(32)
        val profile = "cd".repeat(32)
        assertTrue(dispatchNavigationIntent(Intent().putExtra(EXTRA_EVENT_ID, thread.uppercase()), handler))
        assertTrue(dispatchNavigationIntent(Intent(Intent.ACTION_VIEW, Uri.parse("nostr:${Nip19.hexToNpub(profile)}")), handler))
        assertTrue(dispatchNavigationIntent(Intent(Intent.ACTION_VIEW, Uri.parse("nostr:${Nip19.hexToNote(thread)}")), handler))
        assertEquals(listOf(PendingNavigation.Thread(thread), PendingNavigation.Profile(profile), PendingNavigation.Thread(thread)), handler.destinations.take(3).toList())
    }

    @Test fun malformedAndUnsupportedInputsDoNotCreateRoutes() {
        val handler = DeepLinkHandler()
        assertFalse(dispatchNavigationIntent(null, handler))
        assertFalse(dispatchNavigationIntent(Intent().putExtra(EXTRA_EVENT_ID, "bad/path"), handler))
        assertFalse(dispatchNavigationIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")), handler))
        assertFalse(dispatchNavigationIntent(Intent(Intent.ACTION_VIEW, Uri.parse("nostr:note1bad")), handler))
    }
}
