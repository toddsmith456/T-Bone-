package social.tbone.updates

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind

class AmberZapstoreAssetTest {
    private val publisher = "a".repeat(64)
    private val appId = "com.greenart7c3.nostrsigner"
    private val hash = "b".repeat(64)
    private val certificate = "c".repeat(64)

    @Test
    fun parsesAndPrioritizesContentAddressedZapstoreUrl() {
        val asset = AmberZapstoreAsset.fromEvent(
            event(
                version = "v6.6.9",
                versionCode = 211,
                platforms = listOf("android-arm64-v8a"),
                urls = listOf(
                    "https://github.com/greenart7c3/Amber/releases/download/v6.6.9/amber.apk",
                    "https://cdn.zapstore.dev/$hash",
                ),
            ),
            publisher,
            appId,
        )

        assertNotNull(asset)
        assertEquals("6.6.9", asset?.version)
        assertEquals(211L, asset?.versionCode)
        assertEquals(hash, asset?.sha256)
        assertEquals("https://cdn.zapstore.dev/$hash", asset?.downloadUrls?.first())
        assertEquals(setOf("android-arm64-v8a"), asset?.platformTags)
        assertTrue(asset?.certificateSha256?.contains(certificate) == true)
    }

    @Test
    fun rejectsMissingPublisherCertificateOrUntrustedUrl() {
        val unsignedCertificate = event(
            version = "6.6.9",
            versionCode = 211,
            platforms = listOf("android-arm64-v8a"),
            urls = listOf("https://cdn.zapstore.dev/$hash"),
            includeCertificate = false,
        )
        assertNull(AmberZapstoreAsset.fromEvent(unsignedCertificate, publisher, appId))

        val untrustedUrl = event(
            version = "6.6.9",
            versionCode = 211,
            platforms = listOf("android-arm64-v8a"),
            urls = listOf("http://cdn.zapstore.dev/$hash"),
        )
        assertNull(AmberZapstoreAsset.fromEvent(untrustedUrl, publisher, appId))

        val wrongAuthor = event(
            version = "6.6.9",
            versionCode = 211,
            platforms = listOf("android-arm64-v8a"),
            urls = listOf("https://cdn.zapstore.dev/$hash"),
        ).copy(pubkey = "d".repeat(64))
        assertNull(AmberZapstoreAsset.fromEvent(wrongAuthor, publisher, appId))
    }

    @Test
    fun newestSelectorDoesNotDowngradeForAnUnsupportedLatestAbi() {
        val arm64 = AmberZapstoreAsset.fromEvent(
            event("6.6.8", 210, listOf("android-arm64-v8a"), listOf("https://cdn.zapstore.dev/$hash")),
            publisher,
            appId,
        )!!
        val x86Latest = AmberZapstoreAsset.fromEvent(
            event("6.6.9", 211, listOf("android-x86_64"), listOf("https://cdn.zapstore.dev/$hash")),
            publisher,
            appId,
        )!!

        assertNull(AmberZapstoreAsset.newestCompatible(listOf(arm64, x86Latest), setOf("android-arm64-v8a")))
        assertEquals(x86Latest, AmberZapstoreAsset.newestCompatible(listOf(arm64, x86Latest), setOf("android-x86_64")))
    }

    private fun event(
        version: String,
        versionCode: Long,
        platforms: List<String>,
        urls: List<String>,
        includeCertificate: Boolean = true,
    ): Event {
        val tags = buildList {
            add(tag("i", appId))
            add(tag("x", hash))
            add(tag("version", version))
            add(tag("version_code", versionCode.toString()))
            add(tag("m", AmberZapstoreAsset.APK_MIME_TYPE))
            add(tag("size", "123456"))
            platforms.forEach { add(tag("f", it)) }
            urls.forEach { add(tag("url", it)) }
            if (includeCertificate) add(tag("apk_certificate_hash", certificate))
        }
        return Event(
            id = "e".repeat(64),
            pubkey = publisher,
            createdAt = 1_790_000_000,
            kind = EventKind.SOFTWARE_ASSET,
            tags = tags,
            content = "",
            sig = "f".repeat(128),
        )
    }

    private fun tag(vararg values: String) = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
