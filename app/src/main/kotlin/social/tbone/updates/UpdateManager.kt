package social.tbone.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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

/**
 * Checks the public GitHub release once per day, retains the latest result so
 * the Settings badge survives restarts, and downloads only a package whose
 * application ID, version code, and signing certificate match this install.
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

    init {
        // Keep checking daily while the process is alive; if Android stops the
        // process, the persisted timestamp makes the next launch check when due.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val dueAt = _state.value.lastCheckedAt + UPDATE_CHECK_INTERVAL_MS
                val waitMs = (dueAt - System.currentTimeMillis()).coerceAtLeast(0L)
                if (waitMs > 0L) delay(waitMs)
                val completed = performCheck()
                // A manual request may have won the mutex; avoid spinning if so.
                if (!completed) {
                    delay(60_000L)
                } else if (_state.value.lastCheckedAt + UPDATE_CHECK_INTERVAL_MS > System.currentTimeMillis()) {
                    delay(UPDATE_CHECK_INTERVAL_MS)
                }
            }
        }
    }

    fun checkNow() {
        scope.launch(Dispatchers.IO) { performCheck() }
    }

    private suspend fun performCheck(): Boolean {
        if (!checkMutex.tryLock()) return false
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
                .putString(KEY_NOTES, release.notes)
                .putLong(KEY_LAST_CHECKED_AT, attemptedAt)
                .apply()
            val ready = apkFile.isFile && preferences.getString(KEY_APK_VERSION, null) == release.version
            _state.value = UpdateState(
                latestVersion = release.version,
                releaseUrl = release.releaseUrl,
                apkUrl = release.apkUrl,
                releaseNotes = release.notes,
                lastCheckedAt = attemptedAt,
                isChecking = false,
                apkReady = ready,
            )
        } catch (error: Exception) {
            Timber.w(error, "GitHub release check failed")
            preferences.edit().putLong(KEY_LAST_CHECKED_AT, attemptedAt).apply()
            _state.update {
                it.copy(
                    lastCheckedAt = attemptedAt,
                    isChecking = false,
                    error = error.message?.take(240) ?: "Couldn't check GitHub for updates.",
                )
            }
        } finally {
            checkMutex.unlock()
        }
        return true
    }

    private data class LatestRelease(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
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
            if (!response.isSuccessful) error("GitHub returned HTTP ${response.code}")
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
        val notes = json["body"]?.jsonPrimitive?.contentOrNull?.take(8_000)
        val assets = json["assets"]?.jsonArray.orEmpty()
        val asset = assets.firstOrNull { element ->
            val name = element.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.startsWith("T-Bone-fork-") && name.endsWith("-release.apk", ignoreCase = true)
        } ?: assets.firstOrNull { element ->
            element.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                ?.endsWith(".apk", ignoreCase = true) == true
        }
        val apkUrl = asset?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
        LatestRelease(version, releaseUrl, apkUrl, notes)
    }

    /** Download, verify package identity and signer compatibility, then cache the APK. */
    fun downloadUpdate() {
        val snapshot = _state.value
        if (!snapshot.updateAvailable || snapshot.apkUrl.isNullOrBlank() || snapshot.isDownloading) return
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(isDownloading = true, downloadProgress = 0, error = null) }
            val partial = File(updateDirectory, "latest.apk.part")
            try {
                updateDirectory.mkdirs()
                partial.delete()
                downloadTo(partial, snapshot.apkUrl)
                verifyDownloadedApk(partial, snapshot.latestVersion ?: error("Release version was lost"))
                if (apkFile.exists()) apkFile.delete()
                if (!partial.renameTo(apkFile)) error("Couldn't save the downloaded APK")
                preferences.edit().putString(KEY_APK_VERSION, snapshot.latestVersion).apply()
                _state.update {
                    it.copy(isDownloading = false, downloadProgress = 100, apkReady = true, error = null)
                }
            } catch (error: Exception) {
                partial.delete()
                Timber.w(error, "Update download/verification failed")
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        apkReady = false,
                        error = error.message?.take(240) ?: "Couldn't download the update.",
                    )
                }
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
            if (expectedBytes > MAX_UPDATE_APK_BYTES) error("APK download is too large")
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
                        }
                    }
                    output.fd.sync()
                }
            }
            if (copied == 0L) error("GitHub returned an empty APK")
        }
    }

    private fun verifyDownloadedApk(file: File, expectedVersion: String) {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("The downloaded file isn't a valid Android APK")
        if (archive.packageName != context.packageName) {
            error("The update is for a different app (${archive.packageName})")
        }
        if (archive.versionCode <= BuildConfig.VERSION_CODE) {
            error("The downloaded APK isn't newer than this installation")
        }
        val archiveVersion = archive.versionName?.removePrefix("v")?.removePrefix("V")
        if (archiveVersion != null && normalizeVersion(archiveVersion) != normalizeVersion(expectedVersion)) {
            error("The APK version doesn't match the GitHub release tag")
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

    /** Start Android's system package installer using the verified, cached APK. */
    fun installDownloadedUpdate(activityContext: Context): UpdateInstallResult {
        if (!_state.value.apkReady || !apkFile.isFile) return UpdateInstallResult.NOT_READY
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
            _state.update { it.copy(error = error.message?.take(240) ?: "Couldn't open the package installer") }
            UpdateInstallResult.FAILED
        }
    }

    private fun readPersistedState(): UpdateState {
        val version = preferences.getString(KEY_VERSION, null)
        val ready = apkFile.isFile && preferences.getString(KEY_APK_VERSION, null) == version
        return UpdateState(
            latestVersion = version,
            releaseUrl = preferences.getString(KEY_RELEASE_URL, RELEASES_PAGE) ?: RELEASES_PAGE,
            apkUrl = preferences.getString(KEY_APK_URL, null),
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
        private const val KEY_NOTES = "release_notes"
        private const val KEY_LAST_CHECKED_AT = "last_checked_at"
        private const val KEY_APK_VERSION = "apk_version"

        fun normalizeVersion(version: String): String = version.trim().removePrefix("v").removePrefix("V")

        private fun parseVersion(version: String): List<Int>? {
            val parts = Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
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
