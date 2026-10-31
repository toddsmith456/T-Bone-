package social.tbone.ui.toolbox.geohash

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.db.AppDatabase
import social.tbone.nostr.Event
import social.tbone.nostr.NostrJson
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.geohash.GeohashChannelEntry
import social.tbone.nostr.geohash.GeohashKeyDerivation
import social.tbone.nostr.geohash.GeohashRelays
import social.tbone.nostr.hexToBytes
import social.tbone.nostr.relay.RelayConnection
import social.tbone.nostr.relay.RelayPool
import social.tbone.settings.AppSettings
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class GeohashLiveTest {
    @Test fun pinnedChannelReceivesCachesAndClearsBadge() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("tbone_geohash", 0).edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "test-${System.nanoTime()}.preferences_pb")
        val settings = AppSettings(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val cache = GeohashMessageCache(database.geohashMessageDao(), settings, scope)
        val server = MockWebServer()
        val subscriptions = CopyOnWriteArrayList<Pair<WebSocket, String>>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        repeat(10) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
                    if (array.firstOrNull()?.jsonPrimitive?.content == "REQ") {
                        val id = array[1].jsonPrimitive.content
                        webSocket.send("[\"EOSE\",\"$id\"]")
                        subscriptions.add(webSocket to id)
                    }
                }
            }))
        }
        server.start()
        val socketUrl = server.url("/").toString().replaceFirst("http://", "ws://")
        val client = OkHttpClient.Builder().build()
        val pool = RelayPool(scope) { RelayConnection(socketUrl, client) }
        val monitor = GeohashPinnedMonitor(context, pool, settings, cache)
        val code = "9q8yyk"
        try {
            withTimeout(15_000) { settings.initialized.first { it } }
            settings.setGeohashCacheMillis(1_800_000)
            settings.setGeohashChannels(listOf(GeohashChannelEntry(code, pinnedToHome = true)))
            withTimeout(20_000) { while (subscriptions.isEmpty()) delay(50) }
            assertEquals(GeohashRelays.closestRelays(code).toSet(), pool.relayUrls())
            delay(500) // allow EOSE to reach the monitor
            fun signed(seed: ByteArray, content: String): Event {
                val privateKey = GeohashKeyDerivation.derivePrivateKey(seed, code)
                val pub = GeohashKeyDerivation.pubkeyBytesFromPrivkey(privateKey).joinToString("") { "%02x".format(it) }
                val tag = buildJsonArray { add(JsonPrimitive("g")); add(JsonPrimitive(code)) }
                val unsigned = UnsignedEvent(pubkey = pub, kind = 20000, tags = listOf(tag), content = content,
                    createdAt = System.currentTimeMillis() / 1000 - 20)
                val id = unsigned.computeId()
                return Event(id, pub, unsigned.createdAt, 20000, unsigned.tags, content,
                    GeohashKeyDerivation.schnorrSign(id.hexToBytes(), privateKey))
            }
            fun send(event: Event) {
                val (socket, sub) = subscriptions.first()
                socket.send("[\"EVENT\",\"$sub\",${NostrJson.encodeToString(event)}]")
            }
            val first = signed(ByteArray(32) { 7 }, "first")
            send(first)
            withTimeout(15_000) { monitor.unread.first { (it[code] ?: 0) > 0 } }
            withTimeout(15_000) { while (cache.load(code).none { it.id == first.id }) delay(50) }
            monitor.markSeen(code)
            assertNull(monitor.unread.value[code])
            val second = signed(ByteArray(32) { 8 }, "second")
            send(second)
            withTimeout(15_000) { monitor.unread.first { (it[code] ?: 0) > 0 } }
            val own = signed(settings.geohashIdentitySeed(), "own")
            send(own)
            withTimeout(15_000) { while (cache.load(code).none { it.id == own.id && it.own }) delay(50) }
            settings.setGeohashCacheMillis(0)
            cache.prune()
            assertTrue(cache.load(code).isEmpty())
            settings.setGeohashChannels(emptyList())
            withTimeout(10_000) { while (pool.relayUrls().isNotEmpty()) delay(50) }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            sockets.forEach { runCatching { it.close(1000, "test complete") } }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            // Do not mask an assertion failure if a mock socket is still closing.
            runCatching { server.shutdown() }
            client.dispatcher.executorService.shutdownNow()
            database.close()
            file.delete()
        }
    }
}
