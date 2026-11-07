package social.tbone.lists

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.EventKind
import social.tbone.nostr.relay.RelayConnection
import social.tbone.nostr.relay.RelayPool
import java.util.concurrent.CopyOnWriteArrayList

class RelayListFetcherTest {
    @Test fun rejectionDoesNotCountAsSuccessfulRead() = exercise(listOf("EOSE", "CLOSED")) { result ->
        assertEquals(1, result.eoseRelays)
        assertEquals(2, result.targetRelays)
        assertFalse(result.allRelaysAnswered)
        assertEquals(OnlineFetch.Unreachable, classifyOnlineFetch(result, "1".repeat(64)))
    }

    @Test fun allRelaysEoseAllowsEmptyResult() = exercise(listOf("EOSE", "EOSE")) { result ->
        assertEquals(2, result.eoseRelays)
        assertTrue(result.allRelaysAnswered)
        assertEquals(OnlineFetch.Empty, classifyOnlineFetch(result, "1".repeat(64)))
    }

    private fun exercise(replies: List<String>, checkResult: (RelayListFetcher.Result) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = OkHttpClient()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val servers = replies.map { reply ->
            MockWebServer().apply {
                enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
                        if (array.firstOrNull()?.jsonPrimitive?.content != "REQ") return
                        val id = array[1].jsonPrimitive.content
                        webSocket.send(if (reply == "EOSE") "[\"EOSE\",\"$id\"]" else "[\"CLOSED\",\"$id\",\"rejected\"]")
                    }
                }))
                start()
            }
        }
        try {
            val urls = servers.map { it.url("/").toString().replaceFirst("http://", "ws://") }
            val pool = RelayPool(scope) { url -> RelayConnection(url, client) }
            val result = RelayListFetcher(pool).fetchLatest("1".repeat(64), EventKind.FOLLOW_LIST, urls, timeoutMs = 5_000)
            checkResult(result)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            sockets.forEach { it.cancel() }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            servers.forEach { runCatching { it.shutdown() } }
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
