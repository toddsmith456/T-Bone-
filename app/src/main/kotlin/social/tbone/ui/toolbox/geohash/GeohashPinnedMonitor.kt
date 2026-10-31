package social.tbone.ui.toolbox.geohash

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.nostr.Filter
import social.tbone.nostr.geohash.GeohashRelays
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.settings.AppSettings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Live watchers run while the app process is alive, not after Android kills it. */
@Singleton
class GeohashPinnedMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pool: RelayPool,
    private val appSettings: AppSettings,
    private val cache: GeohashMessageCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()
    private val subIds = ConcurrentHashMap<String, String>()
    private val channelRelays = ConcurrentHashMap<String, Set<String>>()
    private val collectorJobs = ConcurrentHashMap<String, Job>()
    private val seenPerChannel = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var openChannel: String? = null

    init {
        scope.launch {
            appSettings.geohashChannels.collect { channels ->
                val home = channels.filter { it.pinnedToHome }.map { it.code }.toSet()
                subIds.keys.filterNot { it in home }.forEach { stopWatch(it) }
                home.filterNot { subIds.containsKey(it) }.forEach { startWatch(it) }
            }
        }
    }

    private fun prefs() = context.getSharedPreferences("tbone_geohash", Context.MODE_PRIVATE)
    private fun lastSeen(code: String) = prefs().getLong("last_seen_$code", 0L)

    private fun startWatch(code: String) {
        if (subIds.containsKey(code)) return
        val seen = ConcurrentHashMap.newKeySet<String>()
        seenPerChannel[code] = seen
        val relays = GeohashRelays.closestRelays(code).toSet()
        channelRelays[code] = relays
        relays.forEach(pool::acquireRelay)
        val subId = UUID.randomUUID().toString().take(8)
        subIds[code] = subId
        collectorJobs[code] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pool.messages.onSubscription {
                pool.subscribeTo(listOf(Filter(kinds = listOf(20000), gTags = GeohashRelays.watchGeohashes(code),
                    since = lastSeen(code).takeIf { it > 0 })), id = subId, label = "geohash_home_$code", relays = relays)
            }.collect { poolMessage ->
                val msg = poolMessage.message as? RelayMessage.EventMessage ?: return@collect
                if (msg.subscriptionId != subId) return@collect
                val event = msg.event
                if (event.kind != 20000 || !event.matchesGeohashChannel(code) || !event.verify()) return@collect
                if (!seen.add(event.id)) return@collect
                if (seen.size > 4096) { seen.clear(); seen.add(event.id) }
                val own = event.pubkey in cache.ownPubkeys(code)
                cache.save(code, GeohashMessage(event.id, event.pubkey, event.createdAt, event.content,
                    event.parsedTags.firstOrNull { it.name == "n" }?.value(), own))
                if (openChannel == code) {
                    markSeen(code)
                } else if (!own && event.createdAt > lastSeen(code)) {
                    _unread.update { it + (code to ((it[code] ?: 0) + 1)) }
                }
            }
        }
    }

    private fun stopWatch(code: String) {
        subIds.remove(code)?.let(pool::unsubscribe)
        collectorJobs.remove(code)?.cancel()
        channelRelays.remove(code)?.forEach(pool::releaseRelay)
        seenPerChannel.remove(code)
        _unread.update { it - code }
    }

    fun open(code: String) { openChannel = code; markSeen(code) }
    fun close(code: String) { if (openChannel == code) { markSeen(code); openChannel = null } }
    fun markSeen(code: String) {
        prefs().edit().putLong("last_seen_$code", System.currentTimeMillis() / 1000L).apply()
        _unread.update { it - code }
    }
}
