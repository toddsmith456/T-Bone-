package social.tbone.updates

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import social.tbone.BuildConfig
import social.tbone.nostr.relay.RelayConnection
import social.tbone.settings.AppSettings
import social.tbone.settings.OrbotHelper
import social.tbone.settings.OrbotStatus
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val GITHUB_RELEASE_API = "https://api.github.com/repos/toddsmith456/T-Bone-/releases/latest"
private const val RELEASES_PAGE = "https://github.com/toddsmith456/T-Bone-/releases/latest"
private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
private const val MAX_UPDATE_APK_BYTES = 150L * 1024L * 1024L

/** Persistent release metadata and transient updater UI state. */
data class UpdateState(
    val latestVersion: String? = null,
    val releaseUrl: String = RELEASES_PAGE,
    val apkUrl: String? = null,
    val apkSha256: String? = null,
    val apkSizeBytes: Long? = null,
    val sha256sUrl: String? = null,
    val releaseNotes: String? = null,
    val lastCheckedAt: Long = 0L,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int? = null,
    val apkReady: Boolean = false,
    val error: String? = null,
) {
    val updateAvailable: Boolean
        get() = latestVersion?.let { UpdateManager.isNewerVersion(it, BuildConfig.VERSION_NAME) } == true
}

enum class UpdateInstallResult { STARTED, NEEDS_PERMISSION, NOT_READY, FAILED }
enum class SaveToDownloadsResult { SAVED, NOT_READY, FAILED }

/**
 * Checks the public GitHub release once per day, retains the latest result so
 * the toolbox badge survives restarts, and downloads only a package whose
 * application ID, version code, and signing certificate match this install.
 *
 * SHA256SUMS is checked when available; the installed APK signer is the trust anchor.
 * Tor-enabled requests never silently retry directly.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val updateDirectory = File(context.cacheDir, "updates")
    private val apkFile = File(updateDirectory, "latest.apk")
    private val checkMutex = Mutex()
    private val _state = MutableStateFlow(readPersistedState())
    val state = _state.asStateFlow()

    /** Exposed for collapsed “certificate” section — computed live, not persisted. */
    fun installedCertificateHashes(): List<String> = try {
        signingCertificates(installedPackageInfo()).map { sha256(it) }
    } catch (_: Exception) { emptyList() }

    fun downloadedCertificateHashes(): List<String> {
        return try {
            if (!apkFile.isFile) return emptyList()
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return emptyList()
            signingCertificates(archive).map { sha256(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun downloadedFileSha256(): String? {
        return try {
            if (!apkFile.isFile) return null
            sha256(apkFile)
        } catch (_: Exception) { null }
    }

    init {
        if (!BuildConfig.IS_BETA) scope.launch(Dispatchers.IO) {
            // Always refresh on process startup: persisted metadata may be stale.
            while (isActive) {
                val success = performCheck()
                delay(if (success) UPDATE_CHECK_INTERVAL_MS else 60L * 60L * 1000L)
            }
        }
    }

    fun checkNow() {
        if (BuildConfig.IS_BETA) return
        scope.launch(Dispatchers.IO) { performCheck() }
    }

    private suspend fun performCheck(): Boolean {
        if (_state.value.isDownloading || !checkMutex.tryLock()) return false
        var success = false
        val attemptedAt = System.currentTimeMillis()
        _state.update { it.copy(isChecking = true, error = null) }
        try {
            val release = fetchLatestRelease()
            val oldVersion = _state.value.latestVersion
            if (oldVersion != release.version) {
                apkFile.delete()
                preferences.edit().remove(KEY_APK_VERSION).apply()
            }
            preferences.edit()
                .putString(KEY_VERSION, release.version)
                .putString(KEY_RELEASE_URL, release.releaseUrl)
                .putString(KEY_APK_URL, release.apkUrl)
                .putString(KEY_APK_SHA256, release.apkSha256)
                .putString(KEY_SHA256S_URL, release.sha256sUrl)
                .putLong(KEY_APK_SIZE, release.apkSizeBytes ?: -1L)
                .putString(KEY_NOTES, release.notes)
                .putLong(KEY_LAST_CHECKED_AT, attemptedAt)
                .apply()
            val ready = apkFile.isFile && preferences.getString(KEY_APK_VERSION, null) == release.version
            _state.value = UpdateState(
                latestVersion = release.version,
                releaseUrl = release.releaseUrl,
                apkUrl = release.apkUrl,
                apkSha256 = release.apkSha256,
                apkSizeBytes = release.apkSizeBytes,
                sha256sUrl = release.sha256sUrl,
                releaseNotes = release.notes,
                lastCheckedAt = attemptedAt,
                isChecking = false,
                apkReady = ready,
            )
            success = true
        } catch (error: Exception) {
            Timber.w(error, "GitHub release check failed")
            _state.update {
                it.copy(
                    isChecking = false,
                    error = error.message?.take(320) ?: "Couldn't check GitHub for updates.",
                )
            }
        } finally {
            checkMutex.unlock()
        }
        return success
    }

    private data class LatestRelease(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        val apkSha256: String?,
        val sha256sUrl: String?,
        val apkSizeBytes: Long?,
        val notes: String?,
    )

    private suspend fun fetchLatestRelease(): LatestRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GITHUB_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "T-Bone-Android-Updater")
            .get()
            .build()
        val bodyText = githubClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // GitHub rate-limit is 60 unauthenticated req/hour; give a friendly message
                if (response.code == 403) error("GitHub rate limit hit — try again in a few minutes (or Check Now later)")
                error("GitHub returned HTTP ${response.code}")
            }
            response.body?.string() ?: error("GitHub returned an empty release response")
        }
        val json = Json.parseToJsonElement(bodyText).jsonObject
        val tag = json["tag_name"]?.jsonPrimitive?.content
            ?: error("Latest GitHub release has no version tag")
        val version = tag.removePrefix("v").removePrefix("V").trim()
        if (version.isBlank() || parseVersion(version) == null) {
            error("Latest GitHub release has an unsupported version tag")
        }
        val releaseUrl = json["html_url"]?.jsonPrimitive?.contentOrNull ?: RELEASES_PAGE
        val notes = json["body"]?.jsonPrimitive?.contentOrNull?.take(20_000)
        val assets = json["assets"]?.jsonArray.orEmpty()

        val apkAsset = assets.firstOrNull { element ->
            val name = element.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.startsWith("T-Bone-fork-", ignoreCase = true) && name.endsWith("-release.apk", ignoreCase = true)
        } ?: error("Latest release has no T-Bone release APK")
        val apkName = apkAsset.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            ?: error("Latest release APK has no name")
        val apkUrl = apkAsset.jsonObject["browser_download_url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("https://github.com/toddsmith456/T-Bone-/releases/download/") }
            ?: error("Latest release has no valid T-Bone APK download URL")
        val apkSize = apkAsset.jsonObject["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

        // Find SHA256SUMS.txt asset for verification + UI display
        val sumsAsset = assets.firstOrNull { element ->
            val name = element.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.equals("SHA256SUMS.txt", ignoreCase = true) || name.equals("SHA256SUMS", ignoreCase = true)
        }
        val sumsUrl = sumsAsset?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
        var apkSha256: String? = null
        if (sumsUrl != null) {
            apkSha256 = try {
                fetchSha256FromSums(sumsUrl, apkName)
            } catch (e: Exception) {
                Timber.w(e, "Could not fetch SHA256SUMS.txt")
                null
            }
        }

        LatestRelease(version, releaseUrl, apkUrl, apkSha256, sumsUrl, apkSize, notes)
    }

    private suspend fun fetchSha256FromSums(sumsUrl: String, apkName: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(sumsUrl)
            .header("User-Agent", "T-Bone-Android-Updater")
            .get().build()
        val text = githubClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("SHA256SUMS download failed HTTP ${resp.code}")
            resp.body?.string() ?: return@use null
        } ?: return@withContext null
        parseChecksum(text, apkName)
    }

    /** Download, verify package identity and signer compatibility, then cache the APK. */
    fun downloadUpdate() {
        if (BuildConfig.IS_BETA) return
        scope.launch(Dispatchers.IO) {
            // Serialize checks and downloads so release metadata cannot change mid-download.
            if (!checkMutex.tryLock()) return@launch
            try {
            val snapshot = _state.value
            if (snapshot.apkUrl.isNullOrBlank() || snapshot.isDownloading || snapshot.isChecking) return@launch
            _state.update { it.copy(isDownloading = true, downloadProgress = 0, error = null) }
            val partial = File(updateDirectory, "latest.apk.part")
            try {
                updateDirectory.mkdirs()
                partial.delete()
                // Never silently bypass Tor; user can change their privacy setting explicitly.
                downloadTo(partial, snapshot.apkUrl)
                verifyDownloadedApk(partial, snapshot.latestVersion ?: error("Release version was lost"), snapshot.apkSha256)
                if (apkFile.exists()) apkFile.delete()
                if (!partial.renameTo(apkFile)) {
                    // renameTo can fail on different filesystems / SELinux
                    partial.copyTo(apkFile, overwrite = true)
                    partial.delete()
                    if (!apkFile.isFile) error("Couldn't save the downloaded APK")
                }
                preferences.edit().putString(KEY_APK_VERSION, snapshot.latestVersion).apply()
                _state.update {
                    it.copy(isDownloading = false, downloadProgress = 100, apkReady = true, error = null)
                }
            } catch (error: Exception) {
                partial.delete()
                Timber.w(error, "Update download/verification failed")
                val msg = when {
                    error.message?.contains("different app", ignoreCase = true) == true ->
                        "This APK has a different package ID and cannot update this app. Open the GitHub release for details."
                    error.message?.contains("signing certificate", ignoreCase = true) == true ->
                        "Signing certificate mismatch. Android cannot update this installation with this APK. Open the GitHub release for details."
                    error.message?.contains("SHA-256", ignoreCase = true) == true ->
                        "Download checksum mismatch — file corrupted. Try again; if it repeats, open GitHub release to check SHA256SUMS."
                    else -> error.message?.take(320)
                }
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        apkReady = false,
                        error = msg ?: "Couldn't download the update.",
                    )
                }
            }
            } finally {
                checkMutex.unlock()
            }
        }
    }

    private suspend fun downloadTo(destination: File, url: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.android.package-archive")
            .header("User-Agent", "T-Bone-Android-Updater")
            .get()
            .build()
        githubClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("APK download failed (HTTP ${response.code})")
            val body = response.body ?: error("GitHub returned an empty APK")
            val expectedBytes = body.contentLength()
            if (expectedBytes > MAX_UPDATE_APK_BYTES) error("APK download is too large (${expectedBytes / 1024 / 1024} MB)")
            var copied = 0L
            var lastProgress = -1
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_UPDATE_APK_BYTES) error("APK download is too large")
                        output.write(buffer, 0, count)
                        if (expectedBytes > 0L) {
                            val progress = ((copied * 100L) / expectedBytes).toInt().coerceIn(0, 99)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                _state.update { it.copy(downloadProgress = progress) }
                            }
                        } else {
                            // Indeterminate: show spinner-ish by pulsing progress text
                            if (copied % (512 * 1024) == 0L) {
                                _state.update { it.copy(downloadProgress = null) }
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (copied == 0L) error("GitHub returned an empty APK")
            if (expectedBytes > 0 && copied != expectedBytes) {
                Timber.w("APK download size mismatch: expected $expectedBytes got $copied (continuing — GitHub may use chunked)")
            }
        }
    }

    private fun verifyDownloadedApk(file: File, expectedVersion: String, expectedSha256: String?) {
        // Hash detects corruption; the installed signing certificate authenticates the update.
        if (!expectedSha256.isNullOrBlank()) {
            val actualSha = sha256(file)
            if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                error("APK SHA-256 mismatch: expected ${expectedSha256.take(12)}… got ${actualSha.take(12)}… — file may be corrupted")
            }
        }
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("The downloaded file isn't a valid Android APK")
        // 1) packageName — allow debug suffix to give friendly error but fail verification
        if (archive.packageName != context.packageName) {
            // debug builds are social.tbone.fork.debug vs social.tbone.fork — explain
            if (archive.packageName == "social.tbone.fork" && context.packageName == "social.tbone.fork.debug") {
                error("The update is for a different app (${archive.packageName}) — you're on a debug build (${context.packageName}). In-app installation is blocked.")
            }
            error("The update is for a different app (${archive.packageName} vs ${context.packageName})")
        }
        if (archive.versionCode <= BuildConfig.VERSION_CODE) {
            error("The downloaded APK version code ${archive.versionCode} isn't newer than installed ${BuildConfig.VERSION_CODE} — check that GitHub release is actually newer")
        }
        val archiveVersion = archive.versionName?.removePrefix("v")?.removePrefix("V")
        if (archiveVersion != null && normalizeVersion(archiveVersion) != normalizeVersion(expectedVersion)) {
            error("The APK versionName (${archiveVersion}) doesn't match GitHub tag (${expectedVersion}) — release may be mis-tagged")
        }

        val archiveSigners = signingCertificates(archive)
        if (archiveSigners.isEmpty()) error("The APK has no readable signing certificate")
        val compatible = if (Build.VERSION.SDK_INT >= 28) {
            archiveSigners.any { signer ->
                context.packageManager.hasSigningCertificate(
                    context.packageName,
                    signer,
                    PackageManager.CERT_INPUT_RAW_X509,
                )
            }
        } else {
            @Suppress("DEPRECATION")
            val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            val installedSigners = signingCertificates(installed).map(::sha256).toSet()
            archiveSigners.map(::sha256).toSet() == installedSigners
        }
        if (!compatible) {
            error("Update signing certificate doesn't match this installation; in-app update blocked")
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo): List<ByteArray> {
        val modern = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        else null
        return modern?.takeIf { it.isNotEmpty() }
            ?: info.signatures?.map { it.toByteArray() }.orEmpty()
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageInfo(context.packageName, flags)
    }

    /** Start Android's system package installer using the verified, cached APK. */
    fun installDownloadedUpdate(activityContext: Context): UpdateInstallResult {
        if (!_state.value.apkReady || !apkFile.isFile) return UpdateInstallResult.NOT_READY
        // Re-verify before launching installer (defense in depth)
        try {
            val v = _state.value.latestVersion ?: ""
            verifyDownloadedApk(apkFile, v, _state.value.apkSha256)
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message?.take(320), apkReady = false) }
            return UpdateInstallResult.FAILED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activityContext.packageManager.canRequestPackageInstalls()
        ) return UpdateInstallResult.NEEDS_PERMISSION

        return try {
            val uri: Uri = FileProvider.getUriForFile(
                activityContext,
                "${activityContext.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activityContext.startActivity(intent)
            UpdateInstallResult.STARTED
        } catch (error: Exception) {
            Timber.w(error, "Could not open Android's package installer")
            _state.update { it.copy(error = error.message?.take(320) ?: "Couldn't open the package installer") }
            UpdateInstallResult.FAILED
        }
    }

    /** Save the verified APK to Downloads via MediaStore (or legacy external). */
    fun saveToDownloads(activityContext: Context): SaveToDownloadsResult {
        if (!_state.value.apkReady || !apkFile.isFile) return SaveToDownloadsResult.NOT_READY
        return try {
            val version = _state.value.latestVersion ?: "update"
            val fileName = "T-Bone-fork-v${version}-release.apk"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = activityContext.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore refused the download")
                resolver.openOutputStream(uri)?.use { out ->
                    apkFile.inputStream().use { inp -> inp.copyTo(out) }
                } ?: error("Could not open Downloads file")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                // also verify saved file size
                Timber.i("Saved APK to Downloads: $fileName")
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloads.mkdirs()
                val dest = File(downloads, fileName)
                apkFile.copyTo(dest, overwrite = true)
                // Notify media scanner
                activityContext.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest)))
            }
            SaveToDownloadsResult.SAVED
        } catch (e: Exception) {
            Timber.w(e, "Save to Downloads failed")
            _state.update { it.copy(error = e.message?.take(320) ?: "Couldn't save to Downloads") }
            SaveToDownloadsResult.FAILED
        }
    }

    private fun readPersistedState(): UpdateState {
        val version = preferences.getString(KEY_VERSION, null)
        val ready = apkFile.isFile && preferences.getString(KEY_APK_VERSION, null) == version
        val size = preferences.getLong(KEY_APK_SIZE, -1L).takeIf { it >= 0L }
        return UpdateState(
            latestVersion = version,
            releaseUrl = preferences.getString(KEY_RELEASE_URL, RELEASES_PAGE) ?: RELEASES_PAGE,
            apkUrl = preferences.getString(KEY_APK_URL, null),
            apkSha256 = preferences.getString(KEY_APK_SHA256, null),
            apkSizeBytes = size,
            sha256sUrl = preferences.getString(KEY_SHA256S_URL, null),
            releaseNotes = preferences.getString(KEY_NOTES, null),
            lastCheckedAt = preferences.getLong(KEY_LAST_CHECKED_AT, 0L),
            apkReady = ready,
        )
    }

    private suspend fun githubClient(): okhttp3.OkHttpClient {
        appSettings.initialized.first { it }
        val useTor = appSettings.torEnabled.value
        val connected = if (useTor) OrbotHelper.getStatus(context) as? OrbotStatus.InstalledAndConnected else null
        return RelayConnection.buildClient(
            useTor = useTor,
            proxyType = connected?.proxyType ?: java.net.Proxy.Type.HTTP,
            torPort = connected?.port ?: 8118,
        ).newBuilder()
            .callTimeout(45, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val KEY_VERSION = "latest_version"
        private const val KEY_RELEASE_URL = "release_url"
        private const val KEY_APK_URL = "apk_url"
        private const val KEY_APK_SHA256 = "apk_sha256"
        private const val KEY_SHA256S_URL = "sha256s_url"
        private const val KEY_APK_SIZE = "apk_size"
        private const val KEY_NOTES = "release_notes"
        private const val KEY_LAST_CHECKED_AT = "last_checked_at"
        private const val KEY_APK_VERSION = "apk_version"

        fun parseChecksum(text: String, apkName: String): String? =
            text.lineSequence().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1].removePrefix("*") == apkName) parts[0].lowercase() else null
            }.firstOrNull()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

        fun normalizeVersion(version: String): String = version.trim().removePrefix("v").removePrefix("V")

        private fun parseVersion(version: String): List<Int>? {
            // Raw string avoids accidentally double-escaping \d: a literal
            // backslash regex silently disabled update checks for every tag.
            val parts = Regex("""\d+""").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
            return parts.takeIf { it.isNotEmpty() }
        }

        fun isNewerVersion(remote: String, installed: String): Boolean {
            val remoteParts = parseVersion(remote) ?: return false
            val installedParts = parseVersion(installed) ?: return false
            for (index in 0 until maxOf(remoteParts.size, installedParts.size)) {
                val remotePart = remoteParts.getOrElse(index) { 0 }
                val installedPart = installedParts.getOrElse(index) { 0 }
                if (remotePart != installedPart) return remotePart > installedPart
            }
            return false
        }
    }
}
