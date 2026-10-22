package social.tbone.webxdc

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import social.tbone.nostr.relay.RelayConnection
import social.tbone.settings.AppSettings
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-device webxdc library: imported `.xdc` packages, their metadata,
 * per-app update journals, and the network fetches for the official store and
 * Nostr/Blossom attachments.
 *
 * Everything is stored under `filesDir/webxdc/`, which is private to the app:
 *
 *   apps/<id>/files/…      extracted mini app (served to the WebView)
 *   apps/<id>/meta.json    SavedXdcApp
 *   updates/<id>.log       append-only local update journal
 */
@Singleton
class XdcRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val rootDir: File get() = File(context.filesDir, "webxdc")
    private val appsDir: File get() = File(rootDir, "apps")
    private val updatesDir: File get() = File(rootDir, "updates")

    private val _savedApps = MutableStateFlow<List<SavedXdcApp>>(emptyList())
    val savedApps: StateFlow<List<SavedXdcApp>> = _savedApps.asStateFlow()

    /** False until the library has been read from disk once. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** ids currently being downloaded/imported (catalog app ids or urls). */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    init {
        scope.launch { reload() }
    }

    // ── Library ───────────────────────────────────────────────────────────────

    suspend fun reload() = withContext(Dispatchers.IO) {
        _savedApps.value = readAll()
        _loaded.value = true
    }

    private fun readAll(): List<SavedXdcApp> {
        val dirs = appsDir.listFiles()?.filter { it.isDirectory && !it.name.endsWith(".tmp") }.orEmpty()
        return dirs.mapNotNull { dir ->
            val meta = File(dir, "meta.json")
            if (!meta.exists()) return@mapNotNull null
            runCatching { json.decodeFromString<SavedXdcApp>(meta.readText()) }.getOrNull()
                ?.takeIf { it.id == dir.name }
        }.sortedByDescending { it.importedAt }
    }

    fun appDir(appId: String): File = File(appsDir, appId)
    fun filesDir(appId: String): File = File(appDir(appId), "files")

    fun iconFile(app: SavedXdcApp): File? {
        val name = app.iconFile ?: return null
        val file = File(filesDir(app.id), name)
        return file.takeIf { it.isFile }
    }

    fun updateStore(appId: String): XdcUpdateStore = XdcUpdateStore(File(updatesDir, "$appId.log"))

    suspend fun delete(appId: String) = withContext(Dispatchers.IO) {
        File(appsDir, appId).deleteRecursively()
        File(updatesDir, "$appId.log").delete()
        val remaining = appSettings.xdcCustomTools.value.filterNot { it.appId == appId }
        if (remaining.size != appSettings.xdcCustomTools.value.size) {
            appSettings.setXdcCustomTools(remaining)
        }
        reload()
    }

    // ── Import ────────────────────────────────────────────────────────────────

    suspend fun importFromUri(uri: Uri): SavedXdcApp = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readCapped(it) }
            ?: throw XdcException("could not open the selected file")
        val name = queryDisplayName(uri)
        importBytes(bytes, name, origin = "local")
    }

    suspend fun importBytes(
        bytes: ByteArray,
        fallbackName: String?,
        origin: String,
        uuid: String? = null,
    ): SavedXdcApp = withContext(Dispatchers.IO) {
        val pkg = XdcArchive.read(bytes, fallbackName)
        val existing = _savedApps.value.firstOrNull { it.id == pkg.id }
        if (existing != null) return@withContext existing

        val target = appDir(pkg.id)
        val tmp = File(appsDir, "${pkg.id}.tmp")
        try {
            tmp.deleteRecursively()
            val filesTarget = File(tmp, "files")
            pkg.files.forEach { (path, data) ->
                val out = File(filesTarget, path)
                out.parentFile?.mkdirs()
                out.writeBytes(data)
            }
            val iconName = pkg.iconPath?.let { File(it).name }
            val meta = SavedXdcApp(
                id = pkg.id,
                name = pkg.name,
                description = pkg.description,
                sourceCodeUrl = pkg.sourceCodeUrl,
                sizeBytes = pkg.sizeBytes,
                importedAt = System.currentTimeMillis(),
                origin = origin,
                uuid = uuid,
                iconFile = iconName,
            )
            File(tmp, "meta.json").writeText(json.encodeToString(meta))
            appsDir.mkdirs()
            if (target.exists()) target.deleteRecursively()
            if (!tmp.renameTo(target)) {
                throw IOException("could not move the imported app into place")
            }
            reload()
            _savedApps.value.firstOrNull { it.id == pkg.id } ?: meta
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw t
        }
    }

    /**
     * Downloads an `.xdc` from a URL (Blossom, webxdc.org store, anywhere) and
     * saves it. [key] marks the catalog entry as busy so the UI can spin.
     */
    suspend fun downloadAndSave(
        url: String,
        fallbackName: String?,
        origin: String,
        uuid: String? = null,
        key: String = url,
    ): SavedXdcApp = withContext(Dispatchers.IO) {
        _busy.value = _busy.value + key
        try {
            val bytes = httpGetBytes(url, XdcArchive.MAX_ARCHIVE_BYTES)
            importBytes(bytes, fallbackName, origin, uuid)
        } finally {
            _busy.value = _busy.value - key
        }
    }

    // ── webxdc.org store ─────────────────────────────────────────────────────

    suspend fun fetchStoreCatalog(): List<XdcStoreEntry> = withContext(Dispatchers.IO) {
        val bytes = httpGetBytes(XdcStoreCatalog.INDEX_URL, 8L * 1024 * 1024)
        val text = String(bytes, Charsets.UTF_8)
        json.decodeFromString<List<XdcStoreEntry>>(text)
            .filter { it.name.isNotBlank() }
            .sortedBy { it.name.lowercase() }
    }

    // ── Networking ────────────────────────────────────────────────────────────

    private fun httpClient(): OkHttpClient =
        RelayConnection.buildClient(useTor = appSettings.torEnabled.value)
            .newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    private fun httpGetBytes(url: String, limit: Long): ByteArray {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw XdcException("unsupported url")
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "T-Bone-Android")
            .header("Accept", "application/x-webxdc, application/octet-stream, application/json;q=0.9, */*;q=0.5")
            .build()
        httpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            if (body.contentLength() > limit) throw XdcException("file is too large")
            return body.byteStream().use { readCapped(it, limit) }
        }
    }

    private fun readCapped(input: InputStream, limit: Long = XdcArchive.MAX_ARCHIVE_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw XdcException("file is too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    /** Best-effort package read for feed previews / debugging. */
    fun inspect(bytes: ByteArray): XdcPackage? = runCatching { XdcArchive.read(bytes) }
        .onFailure { Timber.w(it, "xdc inspect failed") }
        .getOrNull()
}
