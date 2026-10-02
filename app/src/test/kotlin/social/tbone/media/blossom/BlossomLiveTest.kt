package social.tbone.media.blossom

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import social.tbone.account.signer.NostrSigner
import social.tbone.nostr.Event
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.hexToBytes
import social.tbone.nostr.toHex
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * Opt-in end-to-end check against **real** Blossom servers.
 *
 * Skipped unless explicitly enabled, so CI stays hermetic and deterministic:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests '*BlossomLiveTest*' -DblossomLive=1
 * ```
 *
 * It runs the production [BlossomAuth] + [BlossomClient] code against a live
 * server and then re-downloads the blob, so it verifies the whole chain:
 * token → HTTP request → descriptor → publicly fetchable content-addressed URL.
 *
 * This is the check that would have caught the original breakage, where every
 * server answered `400/401 Wrong event kind` because the token used NIP-98
 * kind 27235 instead of Blossom's kind 24242.
 */
class BlossomLiveTest {

    private val servers = listOf(
        "https://nostr.download",
        "https://blossom.data.haus",
        "https://blossom.ditto.pub",
    )

    /** A real BIP-340 signer, so the token carries a signature servers verify. */
    private class LiveSigner(private val privkey: ByteArray) : NostrSigner {
        private val domain = ECDomainParameters(
            SECNamedCurves.getByName("secp256k1").also {
                if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            }.curve,
            SECNamedCurves.getByName("secp256k1").g,
            SECNamedCurves.getByName("secp256k1").n,
            SECNamedCurves.getByName("secp256k1").h,
        )
        private val generator = domain.g
        private val order = domain.n
        private val secret = BigInteger(1, privkey)

        /** x-only public key (BIP-340). */
        override val pubkey: String = generator.multiply(secret).normalize()
            .affineXCoord.encoded.toHex()

        override suspend fun signEvent(event: UnsignedEvent): Result<Event> = runCatching {
            val id = event.computeId()
            Event(
                id = id,
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
                sig = schnorrSign(id.hexToBytes(), privkey),
            ).also { check(it.verify()) { "self-verification failed" } }
        }

        override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String) =
            Result.failure<String>(UnsupportedOperationException())

        override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String) =
            Result.failure<String>(UnsupportedOperationException())

        private fun schnorrSign(message: ByteArray, privkey: ByteArray): String {
            val d = BigInteger(1, privkey)
            val p = generator.multiply(d).normalize()
            val dScalar = if (p.affineYCoord.toBigInteger().mod(BigInteger.TWO) != BigInteger.ZERO) {
                order.subtract(d)
            } else {
                d
            }
            val aux = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val dBytes = dScalar.to32()
            val auxHash = taggedHash("BIP0340/aux", aux)
            val t = ByteArray(32) { i -> (dBytes[i].toInt() xor auxHash[i].toInt()).toByte() }
            val k0 = BigInteger(1, taggedHash("BIP0340/nonce", t + p.affineXCoord.encoded + message)).mod(order)
            val r = generator.multiply(k0).normalize()
            val k = if (r.affineYCoord.toBigInteger().mod(BigInteger.TWO) != BigInteger.ZERO) {
                order.subtract(k0)
            } else {
                k0
            }
            val rx = r.affineXCoord.encoded
            val e = BigInteger(1, taggedHash("BIP0340/challenge", rx + p.affineXCoord.encoded + message)).mod(order)
            return (rx + k.add(e.multiply(dScalar)).mod(order).to32()).toHex()
        }

        private fun taggedHash(tag: String, message: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            val tagHash = digest.digest(tag.toByteArray(Charsets.UTF_8))
            digest.reset()
            digest.update(tagHash)
            digest.update(tagHash)
            digest.update(message)
            return digest.digest()
        }

        private fun BigInteger.to32(): ByteArray {
            val bytes = toByteArray()
            return when {
                bytes.size == 32 -> bytes
                bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            }
        }
    }

    @Test
    fun `uploads to a live blossom server and fetches the blob back`() = runBlocking {
        assumeTrue(
            "opt-in: run with -DblossomLive=1",
            System.getProperty("blossomLive")?.isNotBlank() == true,
        )

        val client = BlossomClient(
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build(),
        )
        val signer = LiveSigner(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val payload = ByteArray(96).also { SecureRandom().nextBytes(it) }
        val file = java.io.File.createTempFile("tbone-live", ".bin").apply { writeBytes(payload) }
        val hash = BlossomClient.sha256Hex(file)

        var succeeded = 0
        val failures = mutableListOf<String>()

        for (server in servers) {
            try {
                val authHeader = BlossomAuth.header(
                    signer = signer,
                    type = BlossomAuth.TYPE_UPLOAD,
                    alt = "T-Bone live upload check",
                    hash = hash,
                    size = file.length(),
                    servers = listOf(server),
                )
                val result = client.upload(file, "application/octet-stream", server, authHeader, hash, "bin")
                val url = result.url
                assertNotNull("$server returned no url", url)
                assertTrue("$server url should live on that host: $url", url!!.startsWith(server))

                // Prove the URL is publicly fetchable and the bytes are intact.
                OkHttpClient().newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    assertEquals("GET $url", 200, response.code)
                    val received = response.body?.bytes()
                    assertNotNull(received)
                    // The blob must be byte-identical to what we sent, and its
                    // SHA-256 must equal the hash we authorized the upload with.
                    assertArrayEquals(payload, received!!)
                    val rehash = MessageDigest.getInstance("SHA-256").digest(received)
                        .joinToString("") { "%02x".format(it) }
                    assertEquals(hash, rehash)
                }
                succeeded++
            } catch (e: Exception) {
                failures += "$server: ${e.message}"
            }
        }
        file.delete()

        assertTrue(
            "no live server accepted the upload — ${failures.joinToString(" | ")}",
            succeeded > 0,
        )
    }
}
