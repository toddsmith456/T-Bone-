package social.tbone.home

import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Filter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Concurrency regression test for [FeedSlotEngine] + [SlotRuntime].
 *
 * Before Option 1A hardening, switching feed slots on Main while relay messages
 * updated subscriptions on Dispatchers.Default raced unsynchronized collections
 * and triggered ConcurrentModificationException / IllegalStateException in
 * `SlotRuntime.assertExclusive`.
 */
class FeedSlotEngineConcurrencyTest {

    @Test
    fun `concurrent slot activations and subscription mutations never race or throw`() {
        val engine = FeedSlotEngine()
        val slots = listOf(
            FeedSlot.of(FeedSource.Follows()),
            FeedSlot.of(FeedSource.Clock()),
            FeedSlot.of(FeedSource.Global()),
            FeedSlot.of(FeedSource.Polls()),
        )
        engine.sync(HomeLayout(slots = slots))

        val query = FeedPlan.Query(
            filters = listOf(Filter(kinds = FeedPlanner.FEED_KINDS, limit = 50)),
            pool = PoolChoice.PERSONAL,
            canPageOlder = true,
        )

        val executor = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        val done = CountDownLatch(6)
        val failure = AtomicReference<Throwable?>(null)

        try {
            repeat(6) { worker ->
                executor.submit {
                    try {
                        start.await()
                        repeat(300) { iter ->
                            val slot = slots[(worker + iter) % slots.size]
                            when (iter % 5) {
                                0 -> engine.activate(slot.id, query)
                                1 -> engine.registerSub(slot.id, "sub-$worker-$iter")
                                2 -> engine.forgetSub("sub-$worker-${(iter - 1).coerceAtLeast(0)}")
                                3 -> engine.markSettled(slot.id)
                                4 -> {
                                    engine.active()
                                    engine.all()
                                    engine.slotForSub("sub-$worker-$iter")
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue("Stress test timed out", done.await(15, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Concurrent FeedSlotEngine failure", it) }
        } finally {
            executor.shutdownNow()
        }
    }
}
