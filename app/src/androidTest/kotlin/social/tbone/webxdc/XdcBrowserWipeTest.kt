package social.tbone.webxdc

import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import social.tbone.FeedRegressionActivity
import org.junit.runner.RunWith
import social.tbone.notes.NoteWriteCoordinator

/** Run alone in a fresh instrumentation process, never in the ordinary suite.
 * A unique WebView data-directory suffix prevents touching user browser data. */
@RunWith(AndroidJUnit4::class)
class XdcBrowserWipeTest {
    @get:Rule val compose = createAndroidComposeRule<FeedRegressionActivity>()

    @Test fun destroysActiveViewAndClearsLocalStorageBeforeCompletion() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        withContext(Dispatchers.Main) {
            WebView.setDataDirectorySuffix("wipe_test_" + UUID.randomUUID().toString().replace("-", ""))
        }
        val supported = withContext(Dispatchers.Main) {
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
        }
        assumeTrue("WebView does not support browsing-data deletion", supported)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writes = NoteWriteCoordinator(owner)
        val registry = XdcWebViewWipe(writes)
        var closed = false
        var after: WebView? = null
        var before: WebView? = null
        val showing = mutableStateOf(true)
        val epoch = writes.currentWipeEpoch()
        try {
            val original = newView()
            before = original
            compose.setContent {
                if (showing.value) AndroidView(
                    factory = {
                        assertTrue(registry.register(original, epoch) { closed = true })
                        original
                    },
                    onRelease = { registry.release(it) },
                )
            }
            compose.waitForIdle()
            assertEquals("\"stored\"", evaluate(original, "localStorage.setItem('private', 'stored'); localStorage.getItem('private')"))
            assertTrue(writes.requestWipe(NoteWriteCoordinator.WipeKind.TOOLBOX))
            writes.pauseAndDrain()
            registry.clearForWipe()
            assertTrue(closed)
            // Compose disposal must tolerate the registry already destroying and
            // detaching the AndroidView during an active wipe.
            compose.runOnIdle { showing.value = false }
            compose.waitForIdle()
            val replacement = newView()
            after = replacement
            assertEquals("null", evaluate(replacement, "localStorage.getItem('private')"))
            withContext(Dispatchers.Main) {
                assertTrue(!registry.register(replacement, epoch) {})
            }
        } finally {
            withContext(Dispatchers.Main) {
                before?.let(registry::release)
                after?.destroy()
            }
            owner.cancel()
        }
    }

    private suspend fun newView(): WebView {
        val loaded = CompletableDeferred<Unit>()
        val view = withContext(Dispatchers.Main) {
            WebView(ApplicationProvider.getApplicationContext()).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
                }
                loadDataWithBaseURL("https://wipe-test.invalid/", "<html><body>test</body></html>", "text/html", "UTF-8", null)
            }
        }
        withTimeout(15_000) { loaded.await() }
        return view
    }

    private suspend fun evaluate(view: WebView, script: String): String {
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main) { view.evaluateJavascript(script) { result.complete(it) } }
        return withTimeout(15_000) { result.await() }
    }
}
