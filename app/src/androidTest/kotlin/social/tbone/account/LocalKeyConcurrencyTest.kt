package social.tbone.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.account.signer.LocalKeySigner

/** Uses a unique test alias and random keys; never touches an actual account. */
@RunWith(AndroidJUnit4::class)
class LocalKeyConcurrencyTest {
    @Test fun concurrentGenerationDoesNotReplaceEncryptionKey() {
        val alias = "tbone_signer_test_" + UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(6)
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 6).map {
                pool.submit<ByteArray> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    LocalKeySigner.generateWithAlias(alias).second
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val blobs = jobs.map { it.get(45, TimeUnit.SECONDS) }
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
            blobs.forEach { blob ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
                assertEquals(32, cipher.doFinal(blob.copyOfRange(12, blob.size)).size)
            }
        } finally {
            start.countDown(); pool.shutdownNow()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
