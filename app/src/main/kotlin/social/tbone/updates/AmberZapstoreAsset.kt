package social.tbone.updates

import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import java.net.URI

/** Verified publisher-signed NIP-82/NIP-94 Android asset metadata. */
data class AmberZapstoreAsset(
    val eventId: String,
    val version: String,
    val versionCode: Long,
    val sha256: String,
    val sizeBytes: Long?,
    val certificateSha256: Set<String>,
    val downloadUrls: List<String>,
    val platformTags: Set<String>,
    val variant: String?,
    val createdAt: Long,
) {
    fun supports(platforms: Set<String>): Boolean =
        platformTags.any { it in platforms || it in UNIVERSAL_ANDROID_PLATFORMS }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private val UNIVERSAL_ANDROID_PLATFORMS = setOf("android", "android-all", "android-universal")
        private val ALLOWED_DOWNLOAD_HOSTS = setOf("cdn.zapstore.dev", "github.com")

        /** Rejects unsigned, mismatched, incomplete, or non-HTTPS asset metadata. */
        fun fromEvent(event: Event, publisherPubkey: String, appId: String): AmberZapstoreAsset? {
            if (event.kind !in setOf(EventKind.SOFTWARE_ASSET, EventKind.FILE_METADATA)) return null
            if (event.pubkey != publisherPubkey) return null
            val tags = event.parsedTags
            fun values(name: String): List<String> = tags
                .asSequence()
                .filter { it.name == name }
                .mapNotNull { it.value()?.trim()?.takeIf(String::isNotEmpty) }
                .toList()

            if (appId !in values("i")) return null
            if (values("m").none { it == APK_MIME_TYPE }) return null

            val version = values("version").firstOrNull()?.removePrefix("v")?.takeIf(String::isNotBlank)
                ?: return null
            val versionCode = values("version_code").firstOrNull()?.toLongOrNull()?.takeIf { it > 0L }
                ?: return null
            val hash = values("x").firstOrNull()?.normalizeSha256() ?: return null
            val certificateHashes = values("apk_certificate_hash")
                .mapNotNull { it.normalizeSha256() }
                .toSet()
            if (certificateHashes.isEmpty()) return null

            val urls = values("url")
                .distinct()
                .filter(::isAllowedDownloadUrl)
                .sortedBy { url ->
                    when (runCatching { URI(url).host?.lowercase() }.getOrNull()) {
                        "cdn.zapstore.dev" -> 0
                        "github.com" -> 1
                        else -> 2
                    }
                }
            if (urls.isEmpty()) return null

            val platforms = values("f").toSet()
            if (platforms.isEmpty() || platforms.none { it.startsWith("android") }) return null
            val sizeBytes = values("size").firstOrNull()?.toLongOrNull()?.takeIf { it > 0L }
            val variant = values("variant").firstOrNull()

            return AmberZapstoreAsset(
                eventId = event.id,
                version = version,
                versionCode = versionCode,
                sha256 = hash,
                sizeBytes = sizeBytes,
                certificateSha256 = certificateHashes,
                downloadUrls = urls,
                platformTags = platforms,
                variant = variant,
                createdAt = event.createdAt,
            )
        }

        /** Choose the newest published Android version, never a stale ABI-specific build. */
        fun newestCompatible(
            assets: List<AmberZapstoreAsset>,
            supportedPlatforms: Set<String>,
        ): AmberZapstoreAsset? {
            val newestCode = assets.maxOfOrNull { it.versionCode } ?: return null
            return assets.asSequence()
                .filter { it.versionCode == newestCode && it.supports(supportedPlatforms) }
                .sortedWith(
                    compareBy<AmberZapstoreAsset> { if (it.variant.isNullOrBlank()) 0 else 1 }
                        .thenByDescending { it.createdAt },
                )
                .firstOrNull()
        }

        private fun isAllowedDownloadUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase() in ALLOWED_DOWNLOAD_HOSTS &&
                uri.userInfo == null &&
                uri.fragment == null
        }.getOrDefault(false)

        private fun String.normalizeSha256(): String? {
            val normalized = trim()
                .removePrefix("sha256:")
                .removePrefix("SHA256:")
                .replace(":", "")
                .replace(" ", "")
                .lowercase()
            return normalized.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        }
    }
}
