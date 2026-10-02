package social.tbone.media.blossom

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Wire-level tests for the upload request: the exact method, path, headers and
 * bytes that leave the device. Previously the request never got past the
 * servers' auth check, so these assertions pin down what "correct" looks like.
 */
class BlossomClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BlossomClient
    private lateinit var file: File
    private val payload = "T-Bone blossom payload".toByteArray()

    private val hash: String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BlossomClient(
            OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
        file = File.createTempFile("tbone-blossom-test", ".bin").apply { writeBytes(payload) }
    }

    @After
    fun tearDown() {
        file.delete()
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun `sends an authenticated PUT to the upload endpoint`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"url":"${baseUrl()}/$hash.bin","sha256":"$hash","size":${payload.size},"type":"application/octet-stream"}""",
            ),
        )

        val result = client.upload(
            file = file,
            mime = "application/octet-stream",
            serverBaseUrl = baseUrl(),
            authHeader = "Nostr TOKEN",
            hash = hash,
            extension = "bin",
        )

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/upload", request.path)
        assertEquals("Nostr TOKEN", request.getHeader("Authorization"))
        assertEquals("application/octet-stream", request.getHeader("Content-Type"))
        // BUD-06: servers that bind the body to the token's `x` tag need this up front.
        assertEquals(hash, request.getHeader("X-SHA-256"))
        assertEquals(payload.size.toString(), request.getHeader("Content-Length"))
        assertEquals(payload.toList(), request.body.readByteArray().toList())

        assertEquals("${baseUrl()}/$hash.bin", result.url)
        assertEquals(hash, result.sha256)
    }

    @Test
    fun `falls back to the content-addressed url when the server sends no body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.upload(
            file = file,
            mime = "image/jpeg",
            serverBaseUrl = baseUrl(),
            authHeader = "Nostr TOKEN",
            hash = hash.uppercase(),
            extension = "jpg",
        )

        assertEquals("${baseUrl()}/$hash.jpg", result.url)
        assertEquals(hash, result.sha256)
        assertEquals(payload.size.toLong(), result.size)
    }

    @Test
    fun `surfaces the server's own X-Reason instead of a bare status code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(413)
                .setHeader(BlossomServerUrl.REASON_HEADER, "File too large. Max allowed size is 100MB."),
        )

        try {
            client.upload(file, "video/mp4", baseUrl(), "Nostr TOKEN", hash)
            fail("expected BlossomException")
        } catch (e: BlossomException) {
            assertEquals(413, e.status)
            assertEquals("file too large — File too large. Max allowed size is 100MB.", e.shortReason)
            assertTrue(
                "message names the host",
                e.message!!.startsWith(BlossomServerUrl.domain(baseUrl())),
            )
        }
    }

    @Test
    fun `a rejected token is reported as an authorization problem`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("Auth event must be kind 24242"),
        )

        try {
            client.upload(file, "image/jpeg", baseUrl(), "Nostr WRONG", hash)
            fail("expected BlossomException")
        } catch (e: BlossomException) {
            assertEquals(401, e.status)
            assertTrue(e.shortReason.startsWith("authorization rejected"))
            assertTrue("body text is kept", e.shortReason.contains("kind 24242"))
        }
    }

    @Test
    fun `a 402 is reported as payment required`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(402).setHeader(BlossomServerUrl.X_LIGHTNING_HEADER, "lnbc1..."),
        )
        try {
            client.upload(file, "image/jpeg", baseUrl(), "Nostr TOKEN", hash)
            fail("expected BlossomException")
        } catch (e: BlossomException) {
            assertEquals(402, e.status)
            assertEquals("payment required for this file", e.shortReason)
        }
    }

    @Test
    fun `sha256 of a file matches the reference digest`() {
        assertEquals(hash, BlossomClient.sha256Hex(file))
    }

    @Test
    fun `sha256Hex matches a known reference vector`() {
        // Guards the hex formatting itself: a sign-extending "%02x" over Byte
        // would produce a wrong-length string and every upload would be
        // refused by servers that verify the x tag.
        val abc = File.createTempFile("tbone-sha", ".txt").apply { writeText("abc") }
        try {
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                BlossomClient.sha256Hex(abc),
            )
        } finally {
            abc.delete()
        }
    }

    @Test
    fun `sha256 handles files larger than the streaming buffer`() {
        val big = File.createTempFile("tbone-big", ".bin")
        try {
            val bytes = ByteArray(300 * 1024) { (it % 251).toByte() }
            big.writeBytes(bytes)
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, BlossomClient.sha256Hex(big))
        } finally {
            big.delete()
        }
    }

    @Test
    fun `has() probes the bare hash url and reports false on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(client.has(hash, baseUrl()))
        assertEquals("/$hash", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(false, client.has(hash, baseUrl()))
    }

    @Test
    fun `delete() uses DELETE on the blob url with the auth header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(client.delete(hash, baseUrl(), "Nostr TOKEN", "jpg"))

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/$hash.jpg", request.path)
        assertNotNull(request.getHeader("Authorization"))
    }
}
