package social.tbone.webxdc

import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.notes.NoteWriteCoordinator
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Main-thread owner of active mini-app WebViews and their browser-data wipe. */
@Singleton
class XdcWebViewWipe @Inject constructor(private val writes: NoteWriteCoordinator) {
    private val active = mutableMapOf<WebView, () -> Unit>()
    private val destroyed = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())

    fun register(view: WebView, epoch: Long, onClosed: () -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (epoch == Long.MIN_VALUE || writes.currentWipeEpoch() != epoch) {
            view.settings.javaScriptEnabled = false
            return false
        }
        active[view] = onClosed
        return true
    }

    fun release(view: WebView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (view in destroyed) return
        active[view]?.invoke()
        view.stopLoading()
        view.removeJavascriptInterface(WebxdcShim.BRIDGE_NAME)
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        active.remove(view)
        destroyed.add(view)
    }

    /** Must run after the shared write gate closes; no mini app may write
     * while WebView's asynchronous browser-data deletion is in progress. */
    suspend fun clearForWipe() = withContext(Dispatchers.Main) {
        active.keys.toList().forEach(::release)
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            "WebView browser-data deletion is unavailable; update Android System WebView and retry"
        }
        val completed = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            true
        } ?: false
        if (!completed) throw java.io.IOException("WebView browser-data deletion timed out")
    }
}
