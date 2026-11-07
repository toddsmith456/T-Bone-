package social.tbone.migration

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run in a separate fresh process with a separate WebView data directory. */
@RunWith(AndroidJUnit4::class)
class LegacyBrowserCleanupTest {
    @Test fun browserCleanupRemovesLocalStorageCookiesAndServiceWorkerState() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        withContext(Dispatchers.Main) { WebView.setDataDirectorySuffix("legacy_probe_" + UUID.randomUUID().toString().replace("-", "")) }
        assumeTrue("Installed WebView lacks DELETE_BROWSING_DATA", withContext(Dispatchers.Main) {
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
        })
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/sw.js")
                MockResponse().setHeader("Content-Type", "application/javascript").setBody("self.addEventListener('fetch', event => {});")
            else MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>local test</body></html>")
        }
        server.start()
        val url = server.url("/").toString()
        var toDispose: WebView? = null
        try {
            val first = createView(url)
            toDispose = first
            assertEquals("\"secret\"", evaluate(first, "localStorage.setItem('private', 'secret'); localStorage.getItem('private')"))
            withContext(Dispatchers.Main) { CookieManager.getInstance().setCookie(url, "private=secret; Path=/"); CookieManager.getInstance().flush() }
            evaluate(first, "window.setup='waiting'; Promise.all([navigator.serviceWorker.register('/sw.js'), caches.open('private-cache')]).then(()=>window.setup='ok').catch(e=>window.setup='error:'+e);")
            awaitValue(first, "window.setup", "\"ok\"")
            withContext(Dispatchers.Main) { first.destroy() }
            toDispose = null
            BrowserDeletion().delete()
            val second = createView(url)
            toDispose = second
            assertEquals("null", evaluate(second, "localStorage.getItem('private')"))
            assertTrue(withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(url).isNullOrEmpty() })
            evaluate(second, "window.remaining='waiting'; Promise.all([navigator.serviceWorker.getRegistrations(), caches.keys()]).then(v=>window.remaining=JSON.stringify([v[0].length,v[1].length]));")
            awaitValue(second, "window.remaining", "\"[0,0]\"")
        } finally {
            withContext(Dispatchers.Main) { toDispose?.destroy() }
            server.shutdown()
        }
    }

    private suspend fun createView(url: String): WebView {
        val loaded = CompletableDeferred<Unit>()
        val view = withContext(Dispatchers.Main) {
            WebView(ApplicationProvider.getApplicationContext()).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
                }
                loadUrl(url)
            }
        }
        withTimeout(15_000) { loaded.await() }
        return view
    }

    private suspend fun evaluate(view: WebView, script: String): String {
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main) { view.evaluateJavascript(script) { result.complete(it) } }
        return withTimeout(10_000) { result.await() }
    }

    private suspend fun awaitValue(view: WebView, expression: String, expected: String) {
        withTimeout(15_000) {
            while (evaluate(view, expression) != expected) delay(100)
        }
    }
}
