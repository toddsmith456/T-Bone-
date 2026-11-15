package social.tbone.media.blossom

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import social.tbone.account.signer.NostrSigner
import social.tbone.nostr.Event
import social.tbone.nostr.NostrJson
import social.tbone.nostr.UnsignedEvent
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that selecting a single Blossom server on the Write tab ALWAYS
 * overrides Settings rules (Beaver Mode, default server, failover pool) and
 * uploads strictly to that server with a single-server BUD-11 auth token.
 */
class BlossomUploaderOverrideTest {

    private class FakeSigner(override val pubkey: String = "a".repeat(64)) : NostrSigner {
        override suspend fun signEvent(event: UnsignedEvent): Result<Event> = Result.success(
            Event(
                id = event.computeId(),
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
                sig = "b".repeat(128),
            ),
        )

        override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String) = Result.success(plaintext)
        override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String) = Result.success(ciphertext)
    }

    private lateinit var chosenServer: MockWebServer
    private lateinit var settingsServer: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var file: File

    @Before
    fun setUp() {
        chosenServer = MockWebServer().apply { start() }
        settingsServer = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
        file = File.createTempFile("tbone-override-test", ".jpg").apply {
            writeBytes("explicit-override-image-bytes".toByteArray())
        }
    }

    @After
    fun tearDown() {
        file.delete()
        chosenServer.shutdown()
        settingsServer.shutdown()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    private fun MockWebServer.baseUrl(): String = url("/").toString().trimEnd('/')

    @Test
    fun `explicit Write-tab selection bypasses Beaver Mode and Settings default and sends single server tag`() = runBlocking {
        val chosenUrl = chosenServer.baseUrl()
        val settingsUrl = settingsServer.baseUrl()
        val hash = BlossomClient.sha256Hex(file)
        val beaverFetchCount = AtomicInteger(0)

        chosenServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"url":"$chosenUrl/$hash.jpg","sha256":"$hash","size":${file.length()},"type":"image/jpeg"}""",
            ),
        )
        settingsServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"url":"$settingsUrl/$hash.jpg","sha256":"$hash","size":${file.length()},"type":"image/jpeg"}""",
            ),
        )

        val candidates = resolveUploadCandidates(
            preferredServer = chosenUrl,
            beaverMode = true,
            localPool = listOf(settingsUrl, chosenUrl),
            defaultServer = settingsUrl,
            fetchPublished = {
                beaverFetchCount.incrementAndGet()
                listOf(settingsUrl)
            },
        )
        assertEquals(0, beaverFetchCount.get())
        assertEquals(listOf(chosenUrl), candidates)

        val uploadedUrl = executeBlossomUpload(
            file = file,
            mime = "image/jpeg",
            candidates = candidates,
            signer = FakeSigner(),
            extension = "jpg",
            resolveTransport = { BlossomUploadTransport(client) },
        )

        assertEquals("$chosenUrl/$hash.jpg", uploadedUrl)
        assertEquals(1, chosenServer.requestCount)
        assertEquals(0, settingsServer.requestCount)

        val req = chosenServer.takeRequest(2, TimeUnit.SECONDS)!!
        val authHeader = req.getHeader("Authorization")!!
        assertTrue(authHeader.startsWith(BlossomAuth.HEADER_SCHEME))
        val decoded = String(
            Base64.getDecoder().decode(authHeader.removePrefix(BlossomAuth.HEADER_SCHEME)),
            Charsets.UTF_8,
        )
        val authEvent = NostrJson.decodeFromString(Event.serializer(), decoded)
        val serverTags = authEvent.tags
            .filter { (it.firstOrNull() as? JsonPrimitive)?.content == "server" }
            .mapNotNull { (it.getOrNull(1) as? JsonPrimitive)?.content }
        assertEquals(listOf(BlossomServerUrl.domain(chosenUrl)), serverTags)
    }

    @Test
    fun `explicit Write-tab selection fails clearly instead of silently uploading to a Settings server`() = runBlocking {
        val chosenUrl = chosenServer.baseUrl()
        val settingsUrl = settingsServer.baseUrl()
        val hash = BlossomClient.sha256Hex(file)

        chosenServer.enqueue(
            MockResponse().setResponseCode(413)
                .setHeader(BlossomServerUrl.REASON_HEADER, "Max file size exceeded"),
        )
        settingsServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"url":"$settingsUrl/$hash.jpg","sha256":"$hash","size":${file.length()},"type":"image/jpeg"}""",
            ),
        )

        val candidates = resolveUploadCandidates(
            preferredServer = chosenUrl,
            beaverMode = false,
            localPool = listOf(settingsUrl, chosenUrl),
            defaultServer = settingsUrl,
            fetchPublished = { error("should not be called") },
        )
        assertEquals(listOf(chosenUrl), candidates)

        try {
            executeBlossomUpload(
                file = file,
                mime = "image/jpeg",
                candidates = candidates,
                signer = FakeSigner(),
                extension = "jpg",
                resolveTransport = { BlossomUploadTransport(client) },
            )
            fail("Expected BlossomUploadException when explicit server refuses upload")
        } catch (e: BlossomUploadException) {
            assertEquals(1, e.failures.size)
            assertTrue(e.message!!.contains("file too large"))
            assertFalse(
                "Single-server error should not claim every server failed",
                e.message!!.startsWith("Upload failed on every server"),
            )
        }

        assertEquals(1, chosenServer.requestCount)
        assertEquals(0, settingsServer.requestCount)
    }

    @Test
    fun `auto mode uses Beaver Mode published servers and fails over across them`() = runBlocking {
        val firstUrl = chosenServer.baseUrl()
        val secondUrl = settingsServer.baseUrl()
        val hash = BlossomClient.sha256Hex(file)
        val beaverFetchCount = AtomicInteger(0)

        chosenServer.enqueue(MockResponse().setResponseCode(413))
        settingsServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"url":"$secondUrl/$hash.jpg","sha256":"$hash","size":${file.length()},"type":"image/jpeg"}""",
            ),
        )

        val candidates = resolveUploadCandidates(
            preferredServer = null,
            beaverMode = true,
            localPool = listOf("https://ignored.local.example"),
            defaultServer = "https://ignored.local.example",
            fetchPublished = {
                beaverFetchCount.incrementAndGet()
                listOf(firstUrl, secondUrl)
            },
            shuffle = { it },
        )
        assertEquals(1, beaverFetchCount.get())
        assertEquals(listOf(firstUrl, secondUrl), candidates)

        val uploadedUrl = executeBlossomUpload(
            file = file,
            mime = "image/jpeg",
            candidates = candidates,
            signer = FakeSigner(),
            extension = "jpg",
            resolveTransport = { BlossomUploadTransport(client) },
        )

        assertEquals("$secondUrl/$hash.jpg", uploadedUrl)
        assertEquals(1, chosenServer.requestCount)
        assertEquals(1, settingsServer.requestCount)
    }
}
