package social.tbone.migration

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyCleanupTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolated(block: suspend (Context, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>, File) -> Unit) = runBlocking {
        val id = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "legacy_cleanup_test_$id").apply { mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val ctx = object : ContextWrapper(base) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
            override fun getSharedPreferences(name: String?, mode: Int) = base.getSharedPreferences("test_${id}_$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(root, "test.preferences_pb") }
        try { block(ctx, store, root) } finally {
            scope.cancel()
            ctx.getSharedPreferences(LegacyCleanupRunner.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }

    @Test fun upgradeDeletesOnlyLegacyFilesAndRetriesBrowserAfterRestart() = isolated { context, store, _ ->
        File(context.filesDir, "webxdc/apps/a/files").mkdirs()
        File(context.filesDir, "webxdc/apps/a/files/index.html").writeText("secret")
        File(context.filesDir, "webxdc/updates").mkdirs()
        File(context.filesDir, "webxdc/updates/a.log").writeText("state")
        File(context.cacheDir, "webxdc_share").mkdirs()
        File(context.cacheDir, "webxdc_share/export.txt").writeText("export")
        val unrelated = File(context.filesDir, "notes.txt").apply { writeText("keep") }
        val order = stringPreferencesKey("toolbox_tool_order")
        val tools = stringPreferencesKey("xdc_custom_tools")
        val account = stringPreferencesKey("accounts_json")
        store.edit { it[order] = "webxdc,notes,xdc_abc,calendar,xdc_def"; it[tools] = "[saved]"; it[account] = "keep-account" }
        var calls = 0
        val failing = LegacyCleanupRunner(context, store, { true }, { calls++; error("unsupported WebView") })
        try { failing.run(); fail("expected failure") } catch (_: IllegalStateException) { }
        assertEquals("pending", context.getSharedPreferences(LegacyCleanupRunner.PREFS, 0).getString("phase", null))
        assertFalse(File(context.filesDir, "webxdc").exists())
        assertFalse(File(context.cacheDir, "webxdc_share").exists())
        assertEquals("keep", unrelated.readText())
        assertEquals("notes,calendar", store.data.first()[order])
        assertNull(store.data.first()[tools])
        assertEquals("keep-account", store.data.first()[account])
        val resumed = LegacyCleanupRunner(context, store, { false }, { calls++ })
        resumed.run()
        assertEquals(2, calls) // pending marker remembers browser work even when files no longer exist
        assertEquals("done", context.getSharedPreferences(LegacyCleanupRunner.PREFS, 0).getString("phase", null))
        resumed.run()
        assertEquals(2, calls)
    }

    @Test fun freshInstallDoesNotInitializeOrDeleteWebViewData() = isolated { context, store, _ ->
        var deletes = 0
        LegacyCleanupRunner(context, store, { false }, { deletes++ }).run()
        assertEquals(0, deletes)
        assertEquals("done", context.getSharedPreferences(LegacyCleanupRunner.PREFS, 0).getString("phase", null))
    }

    @Test fun browserStorageDetectionDoesNotCreateWebView() = isolated { context, _, root ->
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(context.applicationInfo).apply { dataDir = root.absolutePath }
        }
        val browser = BrowserDeletion()
        assertFalse(browser.hasData(isolated))
        val webviewDir = File(root, "app_webview").apply { mkdirs() }
        assertTrue(browser.hasData(isolated))
        webviewDir.deleteRecursively()
        File(context.cacheDir, "WebView").mkdirs()
        assertTrue(browser.hasData(isolated))
    }

    @Test fun incompletePendingMarkerNeverSilentlyCompletes() = isolated { context, store, _ ->
        val marker = context.getSharedPreferences(LegacyCleanupRunner.PREFS, 0)
        marker.edit().putString("phase", "pending").commit()
        try { LegacyCleanupRunner(context, store, { false }, {}).run(); fail("expected invalid marker") }
        catch (_: IllegalStateException) { }
        assertEquals("pending", marker.getString("phase", null))
    }

    @Test fun cancellationDoesNotMarkBrowserCleanupFinished() = isolated { context, store, _ ->
        val first = LegacyCleanupRunner(context, store, { true }, { throw CancellationException("interrupted") })
        try { first.run(); fail("expected cancellation") } catch (_: CancellationException) { }
        assertEquals("pending", context.getSharedPreferences(LegacyCleanupRunner.PREFS, 0).getString("phase", null))
        var completed = false
        LegacyCleanupRunner(context, store, { false }, { completed = true }).run()
        assertTrue(completed)
        assertEquals("done", context.getSharedPreferences(LegacyCleanupRunner.PREFS, 0).getString("phase", null))
    }
}
