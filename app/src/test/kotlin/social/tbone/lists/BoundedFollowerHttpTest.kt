package social.tbone.lists

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class BoundedFollowerHttpTest {
    @Test fun successfulAndOversizedBodiesAreHandled() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("hello"))
        server.enqueue(MockResponse().setBody("too large"))
        server.start()
        val client = OkHttpClient()
        try {
            assertEquals("hello", client.newCall(Request.Builder().url(server.url("/ok")).build()).awaitBoundedFollowerBody(5))
            var failed = false
            try { client.newCall(Request.Builder().url(server.url("/big")).build()).awaitBoundedFollowerBody(3) } catch (_: Exception) { failed = true }
            assertTrue(failed)
        } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow(); server.shutdown() }
    }

    @Test fun cancelingTheCallerCancelsTheNetworkCall() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); start() }
        val client = OkHttpClient()
        val call = client.newCall(Request.Builder().url(server.url("/slow")).build())
        try {
            val job = async(Dispatchers.IO) { call.awaitBoundedFollowerBody(100) }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
        } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow(); server.shutdown() }
    }
}
