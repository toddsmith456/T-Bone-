package social.tbone.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a unique test alias; never deletes the app's notes key. */
@RunWith(AndroidJUnit4::class)
class NotesCryptoConcurrencyTest {
    @Test fun concurrentFirstUseDoesNotReplaceTheKey() {
        val alias = "tbone_test_" + UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { index ->
                pool.submit<Pair<ByteArray, ByteArray>> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    NotesCrypto(alias).encrypt("message-$index")
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val encrypted = futures.map { it.get(30, TimeUnit.SECONDS) }
            val crypto = NotesCrypto(alias)
            encrypted.forEachIndexed { index, value ->
                assertEquals("message-$index", crypto.decrypt(value.first, value.second))
            }
        } finally {
            start.countDown()
            pool.shutdownNow()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
