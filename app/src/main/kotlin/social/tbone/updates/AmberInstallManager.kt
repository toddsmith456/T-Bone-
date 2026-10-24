package social.tbone.updates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.relay.PoolMessage
import social.tbone.nostr.relay.RelayConnection
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
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

private const val AMBER_APP_ID = "com.greenart7c3.nostrsigner"
// Publisher key decoded from the Amber application naddr supplied by the user.
private const val AMBER_PUBLISHER_PUBKEY = "7579076d9aff0a4cfdefa7e2045f2486c7e5d8bc63bfc6b45397233e1bbfcb19"
private const val ZAPSTORE_RELAY = "wss://relay.zapstore.dev"
private const val AMBER_LISTING_URL =
    "https://zapstore.dev/apps/naddr1qqdkxmmd9enhyet9deshyaphvvejumn0wd68yumfvahx2usprpmhxue69uhhyetvv9uju7npwpehgmmjv5hxgetkqgs827g8dkd07zjvlhh60csytujgd3l9mz7x807xk3fewge7rwlukxgrqsqqqlstrfax9d"
private const val AMBER_APK_MAX_BYTES = 150L * 1024L * 1024L
private const val ZAPSTORE_QUERY_TIMEOUT_MS = 20_000L

/** Amber download/install progress for the login screen. */
data class AmberInstallState(
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int? = null,
    val apkReady: Boolean = false,
    val version: String? = null,
    val installLaunched: Boolean = false,
    val needsInstallPermission: Boolean = false,
    val error: String? = null,
)

enum class AmberInstallResult { STARTED, NEEDS_PERMISSION, NOT_READY, ALREADY_INSTALLED, FAILED }

/**
 * Resolves Amber's publisher-signed Zapstore asset, verifies its event, APK
 * digest, package identity, version and signing certificate, then hands it to
 * Android's system installer. Android still requires the user's confirmation.
 */
@Singleton
class AmberInstallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pool: RelayPool,
    private val appSettings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val installDirectory = File(context.cacheDir, "updates")
    private val apkFile = File(installDirectory, "amber-latest.apk")
    private val partialFile = File(installDirectory, "amber-latest.apk.part")
    private val downloadMutex = Mutex()
    private val _state = MutableStateFlow(AmberInstallState(isInstalled = isAmberInstalled()))
    val state = _state.asStateFlow()

    fun isAmberInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(AMBER_APP_ID, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (error: Exception) {
        Timber.w(error, "Could not check whether Amber is installed")
        false
    }

    fun refreshInstalled(): Boolean {
        val installed = isAmberInstalled()
        _state.update { it.copy(isInstalled = installed) }
        return installed
    }

    /** Look up the newest compatible Amber asset and stage a verified APK. */
    fun downloadLatestAmber() {
        if (_state.value.isInstalled || _state.value.isDownloading) return
        scope.launch(Dispatchers.IO) {
            if (!downloadMutex.tryLock()) return@launch
            var relayAcquired = false
            _state.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = 0,
                    apkReady = false,
                    installLaunched = false,
                    needsInstallPermission = false,
                    error = null,
                )
            }
            try {
                pool.acquireRelay(ZAPSTORE_RELAY)
                relayAcquired = true

                // The naddr identifies this replaceable application coordinate;
                // verify the live app event before trusting any binary event.
                val appEvent = fetchAmberAppEvent()
                check(appEvent.pubkey == AMBER_PUBLISHER_PUBKEY &&
                    appEvent.parsedTags.any { it.name == "d" && it.value() == AMBER_APP_ID }
                ) { "Amber's Zapstore application record did not match its listing." }

                val assetEvents = fetchAmberAssetEvents()
                val assets = assetEvents.mapNotNull { event ->
                    if (!event.verify()) return@mapNotNull null
                    AmberZapstoreAsset.fromEvent(event, AMBER_PUBLISHER_PUBKEY, AMBER_APP_ID)
                }
                val latest = AmberZapstoreAsset.newestCompatible(assets, supportedPlatforms())
                    ?: if (assets.isEmpty()) {
                        error("No verified Amber APK was returned by Zapstore. Open the listing to check it: $AMBER_LISTING_URL")
                    } else {
                        error("Zapstore has no latest Amber APK compatible with this device. Open the listing: $AMBER_LISTING_URL")
                    }
                check((latest.sizeBytes ?: 0L) <= AMBER_APK_MAX_BYTES) { "Amber's APK is larger than the safe download limit." }

                installDirectory.mkdirs()
                partialFile.delete()
                downloadAndVerifyAsset(latest, partialFile)
                if (apkFile.exists()) apkFile.delete()
                if (!partialFile.renameTo(apkFile)) {
                    partialFile.copyTo(apkFile, overwrite = true)
                    partialFile.delete()
                }
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = 100,
                        apkReady = true,
                        version = latest.version,
                        installLaunched = false,
                        needsInstallPermission = false,
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                partialFile.delete()
                _state.update { it.copy(isDownloading = false, downloadProgress = null) }
                throw cancelled
            } catch (error: Exception) {
                partialFile.delete()
                Timber.w(error, "Amber Zapstore download/verification failed")
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        apkReady = false,
                        installLaunched = false,
                        error = error.message?.take(320) ?: "Couldn't download Amber from Zapstore.",
                    )
                }
            } finally {
                if (relayAcquired) pool.releaseRelay(ZAPSTORE_RELAY)
                downloadMutex.unlock()
            }
        }
    }

    /** Starts the Android package installer for the verified cached APK. */
    fun installDownloadedAmber(activityContext: Context): AmberInstallResult {
        if (refreshInstalled()) return AmberInstallResult.ALREADY_INSTALLED
        if (!_state.value.apkReady || !apkFile.isFile) return AmberInstallResult.NOT_READY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activityContext.packageManager.canRequestPackageInstalls()
        ) {
            _state.update {
                it.copy(
                    needsInstallPermission = true,
                    installLaunched = false,
                    error = "Allow installs from T-Bone to continue with Amber.",
                )
            }
            return AmberInstallResult.NEEDS_PERMISSION
        }

        return try {
            val uri: Uri = FileProvider.getUriForFile(
                activityContext,
                "${activityContext.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, AmberZapstoreAsset.APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (activityContext !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(intent)
            _state.update {
                it.copy(
                    needsInstallPermission = false,
                    installLaunched = true,
                    error = null,
                )
            }
            AmberInstallResult.STARTED
        } catch (error: Exception) {
            Timber.w(error, "Could not open Android's Amber package installer")
            _state.update {
                it.copy(error = error.message?.take(240) ?: "Couldn't open Android's installer.")
            }
            AmberInstallResult.FAILED
        }
    }

    fun noteInstallPermissionStillMissing() {
        _state.update {
            it.copy(
                needsInstallPermission = true,
                error = "Enable ‘Allow from this source’ for T-Bone, then try again.",
            )
        }
    }

    private suspend fun fetchAmberAppEvent(): Event {
        val exact = fetchEvents(
            Filter(
                kinds = listOf(EventKind.SOFTWARE_APPLICATION),
                authors = listOf(AMBER_PUBLISHER_PUBKEY),
                dTags = listOf(AMBER_APP_ID),
                limit = 10,
            ),
            label = "amber-zapstore-app",
        )
        val matching = exact.firstOrNull(::isAmberAppEvent)
            ?: fetchEvents(
                Filter(
                    kinds = listOf(EventKind.SOFTWARE_APPLICATION),
                    authors = listOf(AMBER_PUBLISHER_PUBKEY),
                    limit = 100,
                ),
                label = "amber-zapstore-app-fallback",
            ).firstOrNull(::isAmberAppEvent)
        return matching ?: error("Amber's signed Zapstore application record could not be verified.")
    }

    private fun isAmberAppEvent(event: Event): Boolean =
        event.kind == EventKind.SOFTWARE_APPLICATION &&
            event.pubkey == AMBER_PUBLISHER_PUBKEY &&
            event.verify() &&
            event.parsedTags.any { it.name == "d" && it.value() == AMBER_APP_ID }

    private suspend fun fetchAmberAssetEvents(): List<Event> {
        val indexed = fetchEvents(
            Filter(
                kinds = listOf(EventKind.SOFTWARE_ASSET, EventKind.FILE_METADATA),
                authors = listOf(AMBER_PUBLISHER_PUBKEY),
                iTags = listOf(AMBER_APP_ID),
                limit = MAX_ASSET_EVENTS,
            ),
            label = "amber-zapstore-assets",
        )
        val indexedAssets = indexed.mapNotNull { event ->
            if (!event.verify()) return@mapNotNull null
            AmberZapstoreAsset.fromEvent(event, AMBER_PUBLISHER_PUBKEY, AMBER_APP_ID)
        }
        if (indexedAssets.isNotEmpty()) return indexed

        // Some relays have not indexed the NIP-82 `i` tag yet; only then fetch
        // a bounded publisher slice and validate the identifier locally.
        return fetchEvents(
            Filter(
                kinds = listOf(EventKind.SOFTWARE_ASSET, EventKind.FILE_METADATA),
                authors = listOf(AMBER_PUBLISHER_PUBKEY),
                limit = MAX_ASSET_EVENTS,
            ),
            label = "amber-zapstore-assets-fallback",
        )
    }

    private suspend fun fetchEvents(filter: Filter, label: String): List<Event> {
        val subscriptionId = pool.subscribeTo(
            filters = listOf(filter),
            label = label,
            relays = setOf(ZAPSTORE_RELAY),
        )
        val events = linkedMapOf<String, Event>()
        try {
            val eose = withTimeoutOrNull(ZAPSTORE_QUERY_TIMEOUT_MS) {
                pool.messages
                    .onEach { poolMessage -> collectVerifiedEvent(poolMessage, subscriptionId, events) }
                    .first { poolMessage ->
                        poolMessage.relayUrl == ZAPSTORE_RELAY &&
                            (poolMessage.message as? RelayMessage.EndOfStoredEvents)?.subscriptionId == subscriptionId
                    }
            }
            if (eose == null) error("Timed out while reading Amber's signed Zapstore listing.")
        } finally {
            pool.unsubscribe(subscriptionId)
        }
        return events.values.toList()
    }

    private fun collectVerifiedEvent(
        poolMessage: PoolMessage,
        subscriptionId: String,
        events: MutableMap<String, Event>,
    ) {
        if (poolMessage.relayUrl != ZAPSTORE_RELAY) return
        val message = poolMessage.message as? RelayMessage.EventMessage ?: return
        if (message.subscriptionId != subscriptionId) return
        val event = message.event
        if (event.verify()) events.putIfAbsent(event.id, event)
    }

    private suspend fun downloadAndVerifyAsset(asset: AmberZapstoreAsset, destination: File) {
        var lastError: Exception? = null
        for (url in asset.downloadUrls) {
            try {
                downloadFromUrl(asset, url, destination)
                verifyDownloadedApk(destination, asset)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                destination.delete()
                lastError = error
                Timber.w(error, "Amber APK download/verification failed from $url")
            }
        }
        throw lastError ?: IllegalStateException("Amber's Zapstore listing had no usable download URL.")
    }

    private suspend fun downloadFromUrl(
        asset: AmberZapstoreAsset,
        url: String,
        destination: File,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", AmberZapstoreAsset.APK_MIME_TYPE)
            .header("User-Agent", "T-Bone-Android-Amber-Installer")
            .get()
            .build()
        val response = downloadClient().newCall(request).execute()
        response.use {
            if (!it.isSuccessful) error("Amber download returned HTTP ${it.code}")
            val body = it.body ?: error("Amber download returned an empty response")
            val reportedSize = asset.sizeBytes ?: body.contentLength().takeIf { size -> size > 0L }
            if ((reportedSize ?: 0L) > AMBER_APK_MAX_BYTES) error("Amber's APK exceeds the safe download limit")
            var copied = 0L
            var lastProgress = -1
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > AMBER_APK_MAX_BYTES) error("Amber's APK exceeds the safe download limit")
                        output.write(buffer, 0, count)
                        val total = reportedSize
                        if (total != null && total > 0L) {
                            val progress = ((copied * 100L) / total).toInt().coerceIn(0, 99)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                _state.update { it.copy(downloadProgress = progress) }
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (copied == 0L) error("Amber download was empty")
            if (asset.sizeBytes != null && copied != asset.sizeBytes) {
                error("Amber APK size did not match Zapstore's signed metadata")
            }
        }
    }

    private fun verifyDownloadedApk(file: File, asset: AmberZapstoreAsset) {
        val actualHash = sha256(file)
        check(actualHash.equals(asset.sha256, ignoreCase = true)) {
            "Amber APK SHA-256 did not match the signed Zapstore asset."
        }

        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("The downloaded file is not a valid Android APK")
        check(archive.packageName == AMBER_APP_ID) {
            "Zapstore's APK was for ${archive.packageName}, not Amber."
        }
        @Suppress("DEPRECATION")
        val legacyVersionCode = archive.versionCode.toLong()
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode
        else legacyVersionCode
        check(archiveVersionCode == asset.versionCode) {
            "Amber APK version code did not match Zapstore's signed metadata."
        }
        val archiveVersion = archive.versionName?.removePrefix("v")?.removePrefix("V")
        check(archiveVersion != null && archiveVersion.equals(asset.version, ignoreCase = true)) {
            "Amber APK version did not match Zapstore's signed metadata."
        }
        val signerHashes = signingCertificates(archive).toSet()
        check(signerHashes.isNotEmpty() && signerHashes.any { it in asset.certificateSha256 }) {
            "Amber APK signing certificate did not match Zapstore's signed publisher record."
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo): List<String> {
        val modern = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }
        } else null
        return modern?.takeIf { it.isNotEmpty() }
            ?: info.signatures?.map { sha256(it.toByteArray()) }.orEmpty()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun supportedPlatforms(): Set<String> {
        val platforms = Build.SUPPORTED_ABIS.mapNotNull { abi ->
            when (abi.lowercase()) {
                "arm64-v8a" -> "android-arm64-v8a"
                "armeabi-v7a" -> "android-armeabi-v7a"
                "armeabi" -> "android-armeabi"
                "x86_64" -> "android-x86_64"
                "x86" -> "android-x86"
                else -> null
            }
        }.toMutableSet()
        platforms += setOf("android", "android-all", "android-universal")
        return platforms
    }

    private suspend fun downloadClient(): OkHttpClient {
        appSettings.initialized.first { it }
        val useTor = appSettings.torEnabled.value
        val connected = if (useTor) OrbotHelper.getStatus(context) as? OrbotStatus.InstalledAndConnected else null
        return RelayConnection.buildClient(
            useTor = useTor,
            proxyType = connected?.proxyType ?: java.net.Proxy.Type.HTTP,
            torPort = connected?.port ?: 8118,
        ).newBuilder()
            .callTimeout(2, TimeUnit.MINUTES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        const val MAX_ASSET_EVENTS = 200
    }
}
