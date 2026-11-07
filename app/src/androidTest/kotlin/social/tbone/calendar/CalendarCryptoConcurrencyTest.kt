package social.tbone.calendar

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated alias: does not modify real calendar keys. */
@RunWith(AndroidJUnit4::class)
class CalendarCryptoConcurrencyTest {
    @Test fun concurrentFirstUseRetainsAllCiphertexts() {
        val alias = "tbone_calendar_test_" + UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 8).map { n ->
                pool.submit<Pair<ByteArray, ByteArray>> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    CalendarCrypto(alias).encrypt("event-$n")
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val encrypted = jobs.map { it.get(30, TimeUnit.SECONDS) }
            val crypto = CalendarCrypto(alias)
            encrypted.forEachIndexed { n, value -> assertEquals("event-$n", crypto.decrypt(value.first, value.second)) }
        } finally {
            start.countDown(); pool.shutdownNow()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
