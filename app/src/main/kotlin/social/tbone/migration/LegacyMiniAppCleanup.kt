package social.tbone.migration

import android.content.Context
import android.webkit.WebStorage
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Temporary upgrade-only cleanup; there is no mini-app runtime or writer in this version. */
@Singleton
class LegacyMiniAppCleanup @Inject constructor(
    @ApplicationContext context: Context,
    dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val browser = BrowserDeletion()
    private val runner = LegacyCleanupRunner(context, dataStore, browser::hasData, browser::delete)
    private val _status = MutableStateFlow(CleanupStatus.Checking)
    val status = _status.asStateFlow()

    fun retry() { scope.launch(Dispatchers.IO) { runCatching { run() } } }

    /** Failure propagates to sensitive-wipe callers so they do not claim success. */
    suspend fun run() = lock.withLock {
        _status.value = CleanupStatus.Checking
        try {
            runner.run()
            _status.value = CleanupStatus.Complete
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = CleanupStatus.NeedsAttention
            throw e
        }
    }
}

enum class CleanupStatus { Checking, Complete, NeedsAttention }

/** Testable platform adapter. Pending is durable before the first destructive step. */
internal class LegacyCleanupRunner(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val browserHasData: (Context) -> Boolean,
    private val deleteBrowser: suspend () -> Unit,
) {
    companion object {
        const val PREFS = "legacy_mini_app_cleanup_v1"
        private const val PHASE = "phase"
        private const val BROWSER = "browser_required"
        private val OLD_TOOLS = stringPreferencesKey("xdc_custom_tools")
        private val TOOL_ORDER = stringPreferencesKey("toolbox_tool_order")
    }

    suspend fun run() {
        val marker = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val phase = marker.getString(PHASE, null)
        if (phase == "done") return
        check(phase == null || phase == "pending") { "Unknown legacy cleanup state" }
        if (phase == null) {
            // Do not create a WebView merely to check: that would create new data
            // on fresh installations. This app's only WebView was the mini-app player.
            val browserRequired = browserHasData(context)
            check(marker.edit().putString(PHASE, "pending").putBoolean(BROWSER, browserRequired).commit())
        }
        val needsBrowser = marker.getBoolean(BROWSER, false)
        listOf(File(context.filesDir, "webxdc"), File(context.cacheDir, "webxdc_share")).forEach {
            check(!it.exists() || it.deleteRecursively()) { "Legacy mini-app file cleanup failed" }
        }
        dataStore.edit { prefs ->
            prefs.remove(OLD_TOOLS)
            prefs[TOOL_ORDER]?.let { order ->
                prefs[TOOL_ORDER] = order.split(',').filter { it != "webxdc" && !it.startsWith("xdc_") && it.isNotBlank() }.joinToString(",")
            }
        }
        if (needsBrowser) deleteBrowser()
        check(marker.edit().putString(PHASE, "done").remove(BROWSER).commit())
    }
}

/** Platform cleanup is asynchronous. Retain an in-flight callback across retries. */
internal class BrowserDeletion {
    private var pending: CompletableDeferred<Unit>? = null

    fun hasData(context: Context): Boolean {
        val data = File(context.applicationInfo.dataDir)
        val appDirs = data.listFiles() ?: throw IOException("Cannot inspect app data directory")
        val caches = context.cacheDir.listFiles() ?: throw IOException("Cannot inspect app cache directory")
        return appDirs.any { it.name == "app_webview" || it.name.startsWith("app_webview_") } ||
            caches.any { it.name.equals("WebView", true) || it.name.equals("webview", true) }
    }

    suspend fun delete() = withContext(Dispatchers.Main) {
        pending?.let { await(it); pending = null }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            "Update Android System WebView to remove legacy browser data"
        }
        val current = CompletableDeferred<Unit>()
        pending = current
        try {
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) { current.complete(Unit) }
        } catch (e: Exception) {
            pending = null
            throw e
        }
        await(current)
        pending = null
    }

    private suspend fun await(operation: CompletableDeferred<Unit>) {
        if (withTimeoutOrNull(30_000) { operation.await(); true } != true)
            throw IOException("Legacy browser cleanup timed out")
    }
}
