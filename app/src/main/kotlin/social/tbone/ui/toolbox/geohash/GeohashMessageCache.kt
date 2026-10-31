package social.tbone.ui.toolbox.geohash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import social.tbone.db.GeohashMessageDao
import social.tbone.db.GeohashMessageEntity
import social.tbone.nostr.Event
import social.tbone.nostr.geohash.GeohashKeyDerivation
import social.tbone.settings.AppSettings
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A device-local cache of messages actually received; ephemeral relays cannot recover missed messages. */
@Singleton
class GeohashMessageCache @Inject constructor(
    private val dao: GeohashMessageDao,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()

    init {
        scope.launch(Dispatchers.IO) {
            settings.initialized.first { it }
            settings.geohashCacheMillis.collect { prune() }
        }
        scope.launch(Dispatchers.IO) {
            settings.initialized.first { it }
            while (true) { delay(60_000); prune() }
        }
    }

    fun enqueue(channel: String, msg: GeohashMessage) {
        scope.launch(Dispatchers.IO) { save(channel, msg) }
    }

    suspend fun clear() = lock.withLock { dao.deleteAll() }

    suspend fun prune() = lock.withLock {
        try {
            val duration = settings.geohashCacheMillis.value
            if (duration == 0L) dao.deleteAll()
            else dao.deleteOlderThan(System.currentTimeMillis() - duration)
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; Timber.e(e, "Could not prune geohash cache") }
    }

    suspend fun save(channel: String, msg: GeohashMessage) = lock.withLock {
        if (settings.geohashCacheMillis.value == 0L) return@withLock
        try {
            dao.insert(GeohashMessageEntity(msg.id, channel, msg.pubkey, msg.createdAt,
                msg.content, msg.nickname, msg.own, System.currentTimeMillis()))
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; Timber.e(e, "Could not cache geohash message") }
    }

    suspend fun load(channel: String): List<GeohashMessage> = lock.withLock {
        val duration = settings.geohashCacheMillis.value
        if (duration == 0L) return@withLock emptyList()
        try {
            dao.getForChannel(channel, System.currentTimeMillis() - duration).map {
                GeohashMessage(it.id, it.pubkey, it.createdAt, it.content, it.nickname, it.own)
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; Timber.e(e, "Could not load geohash cache"); emptyList() }
    }

    /** Match all saved channel identities: one event can carry several geohash tags. */
    suspend fun ownPubkeys(extraCode: String): Set<String> = withContext(Dispatchers.Default) {
        val seed = settings.geohashIdentitySeed()
        (settings.geohashChannels.value.map { it.code } + extraCode).distinct().map { code ->
            val priv = GeohashKeyDerivation.derivePrivateKey(seed, code)
            GeohashKeyDerivation.pubkeyBytesFromPrivkey(priv).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

/** Check all g tags, not just the first: other clients can include multiple levels. */
internal fun Event.matchesGeohashChannel(code: String): Boolean =
    parsedTags.any { it.name == "g" && it.value()?.let { gh -> gh.isNotEmpty() && code.startsWith(gh.lowercase()) } == true }
