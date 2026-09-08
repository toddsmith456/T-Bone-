package social.tbone.ui.toolbox.geohash

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.nostr.Filter
import social.tbone.nostr.geohash.GeohashRelays
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.settings.AppSettings
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps home-pinned location channels connected to THEIR OWN relays in the
 * background and counts new messages per channel, so the home strip can show
 * a small unread badge. Only channels explicitly pinned to home are watched —
 * idle channels never connect.
 */
@Singleton
class GeohashPinnedMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pool: RelayPool,
    private val appSettings: AppSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    private val subIds = ConcurrentHashMap<String, String>() // code -> subId
    private val addedRelays = ConcurrentHashMap.newKeySet<String>()
    private val seenPerChannel = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        scope.launch {
            appSettings.geohashChannels.collect { channels ->
                val home = channels.filter { it.pinnedToHome }.map { it.code }.toSet()
                // Stop watching channels no longer pinned to home.
                subIds.keys.filterNot { it in home }.forEach { stopWatch(it) }
                // Start watching newly pinned channels.
                home.filterNot { it in subIds.keys }.forEach { startWatch(it) }
            }
        }
    }

    private fun prefs() = context.getSharedPreferences("tbone_geohash", Context.MODE_PRIVATE)

    private fun lastSeen(code: String): Long = prefs().getLong("last_seen_$code", 0L)

    private fun startWatch(code: String) {
        if (subIds.containsKey(code)) return
        seenPerChannel[code] = ConcurrentHashMap.newKeySet()
        scope.launch {
            val relays = GeohashRelays.closestRelays(code).toSet()
            relays.forEach {
                if (it !in pool.relayUrls()) {
                    pool.addRelay(it)
                    addedRelays.add(it)
                }
            }
            val since = lastSeen(code)
            val subId = pool.subscribeTo(
                listOf(Filter(
                    kinds = listOf(20000),
                    gTags = social.tbone.nostr.geohash.GeohashRelays.watchGeohashes(code),
                    since = since.takeIf { it > 0 },
                )),
                label = "geohash_home_$code",
                relays = relays,
            )
            subIds[code] = subId
        }
        scope.launch {
            pool.messages.collect { poolMessage ->
                val msg = poolMessage.message
                if (msg !is RelayMessage.EventMessage) return@collect
                if (msg.subscriptionId != subIds[code]) return@collect
                val event = msg.event
                if (event.kind != 20000) return@collect
                val gh = event.parsedTags.firstOrNull { it.name == "g" }?.value() ?: return@collect
                if (!code.startsWith(gh)) return@collect
                val seen = seenPerChannel[code] ?: return@collect
                if (!seen.add(event.id)) return@collect
                if (event.createdAt > lastSeen(code)) {
                    _unread.update { it + (code to ((it[code] ?: 0) + 1)) }
                }
            }
        }
    }

    private fun stopWatch(code: String) {
        subIds.remove(code)?.let { pool.unsubscribe(it) }
        seenPerChannel.remove(code)
        _unread.update { it - code }
    }

    /** Called when the user opens a channel — its badge clears. */
    fun markSeen(code: String) {
        prefs().edit().putLong("last_seen_$code", System.currentTimeMillis() / 1000L).apply()
        _unread.update { it - code }
        seenPerChannel.remove(code)?.clear()
    }
}
