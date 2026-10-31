package social.tbone.ui.toolbox.geohash

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.*
import org.junit.Assert.*
import social.tbone.db.GeohashMessageDao
import social.tbone.db.GeohashMessageEntity
import social.tbone.nostr.Event
import social.tbone.settings.AppSettings
import java.io.File

class GeohashCacheTest {
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var settings: AppSettings
    private lateinit var dao: FakeDao
    private lateinit var cache: GeohashMessageCache
    @Before fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("geo-test", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        settings = AppSettings(store, scope)
        runBlocking { settings.initialized.first { it } }
        dao = FakeDao(); cache = GeohashMessageCache(dao, settings, scope)
    }
    @After fun cleanup() { scope.cancel(); file.delete() }

    @Test fun `retention is local receipt time and duplicate ids can be stored in multiple channels`() = runBlocking {
        settings.setGeohashCacheMillis(1_800_000L)
        val oldEventTime = GeohashMessage("event", "key", 1L, "message", own = true)
        cache.save("abc", oldEventTime); cache.save("abcd", oldEventTime)
        assertEquals(listOf(oldEventTime), cache.load("abc"))
        assertEquals(listOf(oldEventTime), cache.load("abcd"))
        settings.setGeohashCacheMillis(0L); cache.prune()
        assertTrue(cache.load("abc").isEmpty()); assertTrue(dao.rows.isEmpty())
        cache.save("abc", oldEventTime); assertTrue(dao.rows.isEmpty())
        Unit
    }
    @Test fun `identity seed is stable under concurrent first use`() = runBlocking {
        val seeds = (1..20).map { async(Dispatchers.IO) { settings.geohashIdentitySeed().toList() } }.awaitAll()
        assertEquals(1, seeds.distinct().size)
        Unit
    }
    @Test fun `multiple geohash tags match any watched prefix`() {
        fun tag(value: String) = buildJsonArray { add(JsonPrimitive("g")); add(JsonPrimitive(value)) }
        val e = Event("id", "key", 0, 20000, listOf(tag("other"), tag("9q8yy")), "", "")
        assertTrue(e.matchesGeohashChannel("9q8yyk")); assertFalse(e.matchesGeohashChannel("u09tv"))
    }
    @Test fun `bitchat hues are deterministic and avoid the self orange region`() {
        val hues = (1..200).map { geohashPeerHue(it.toString()) }
        assertTrue(hues.distinct().size > 10)
        assertTrue(hues.all { kotlin.math.abs(it - 30f) >= 18f })
        assertEquals(geohashPeerHue("ABC"), geohashPeerHue("abc"))
    }
    class FakeDao : GeohashMessageDao {
        val rows = mutableMapOf<Pair<String,String>,GeohashMessageEntity>()
        override suspend fun insert(msg: GeohashMessageEntity) { rows.putIfAbsent(msg.id to msg.channelCode, msg) }
        override suspend fun getForChannel(code: String, cutoff: Long) = rows.values.filter { it.channelCode == code && it.cachedAt >= cutoff }.sortedBy { it.createdAt }
        override suspend fun deleteAll() { rows.clear() }
        override suspend fun deleteOlderThan(cutoff: Long) { rows.entries.removeIf { it.value.cachedAt < cutoff } }
    }
}
