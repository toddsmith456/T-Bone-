package social.tbone.lists

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import timber.log.Timber
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import social.tbone.settings.AppSettings
import social.tbone.settings.OrbotHelper
import social.tbone.settings.OrbotStatus
import social.tbone.nostr.relay.RelayConnection
import okhttp3.Request
import social.tbone.util.readBounded
import org.json.JSONObject

/**
 * Follower list — who follows you.
 *
 * Collects bounded, potentially stale samples from relays and Primal's centralized
 * index. A merged sample is not a verified current count or complete list.
 *
 * Disabled by default (AppSettings.followersEnabled). When enabled a one-time
 * popup warns that the list comes from Primal's centralized cache.
 */
@Singleton
class FollowerListRepository @Inject constructor(
    private val pool: RelayPool,
    private val appSettings: AppSettings,
    @ApplicationContext private val context: Context,
) {
    data class FollowersState(
        val followers: List<String>? = null, // null = still loading / unknown
        val loading: Boolean = true,
        val error: String? = null,
        val source: String = "relays",
    )

    private val known = FollowerSnapshotCache()

    // Primal's public caching service: followers list via HTTP.
    // Primal web app uses wss://cache2.primal.net/v1 with method "contacts_list";
    // the public read endpoint is documented as https://primal.net/api/v1/people/<npub>/followers
    // We probe both the primal relay (NIP-45 COUNT is not enough — we need the actual pubkeys) and
    // the HTTP endpoint. Either may be unavailable; we return whatever we get.
    private suspend fun fetchPrimalHttp(pubkeyHex: String): List<String>? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        appSettings.initialized.first { it }
        try {
            val useTor = appSettings.torEnabled.value
            val guard = social.tbone.di.TorCallPolicy.guard
            val transportEpoch = guard.snapshot(useTor)
            val proxy = if (useTor) OrbotHelper.getStatus(context) as? OrbotStatus.InstalledAndConnected else null
            val okHttp = RelayConnection.buildClient(useTor = useTor,
                proxyType = proxy?.proxyType ?: java.net.Proxy.Type.HTTP,
                torPort = proxy?.port ?: 8118)
            // Primal's caching API: POST https://cache2.primal.net/v1 with ["contacts_list", pubkey]
            // The simpler public endpoint is https://primal.net/api/v1/contacts/followers?pubkey=...
            // Try the documented primal.net cache endpoint first.
            val urls = listOf(
                "https://cache2.primal.net/api/v1/followers?pubkey=$pubkeyHex",
                "https://primal.net/api/v1/people/$pubkeyHex/followers",
                "https://primal.net/api/v1/contacts/followers?pubkey=$pubkeyHex",
            )
            for (url in urls) {
                try {
                    val req = Request.Builder().url(url).get().header("Accept", "application/json").build()
                    val call = okHttp.newCall(req)
                    guard.registerAtEpoch(call, useTor, transportEpoch)
                    try {
                        val body = call.awaitBoundedFollowerBody(4 * 1024 * 1024)
                        val list = body?.let(::parsePrimalBody)
                        if (!list.isNullOrEmpty()) return@withContext list
                    } finally {
                        guard.unregister(call)
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            }
            null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private fun parsePrimalBody(body: String): List<String>? {
        return try {
            // Try JSON object with "followers" array, or array of pubkeys, or array of objects with pubkey
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.get(i)
                    when (v) {
                        is String -> if (isHex64(v)) out.add(v.lowercase())
                        is JSONObject -> {
                            val pk = v.optString("pubkey").takeIf { it.isNotBlank() }
                                ?: v.optString("pubkeyHex").takeIf { it.isNotBlank() }
                            if (pk != null && isHex64(pk)) out.add(pk.lowercase())
                        }
                    }
                }
                out.takeIf { it.isNotEmpty() }
            } else {
                val obj = JSONObject(trimmed)
                // Try various keys
                val arr = obj.optJSONArray("followers")
                    ?: obj.optJSONArray("pubkeys")
                    ?: obj.optJSONArray("data")
                if (arr != null) {
                    val out = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val v = arr.get(i)
                        when (v) {
                            is String -> if (isHex64(v)) out.add(v.lowercase())
                            is JSONObject -> {
                                val pk = v.optString("pubkey").takeIf { it.isNotBlank() }
                                if (pk != null && isHex64(pk)) out.add(pk.lowercase())
                            }
                        }
                    }
                    out.takeIf { it.isNotEmpty() }
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun isHex64(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * Relay-based followers: all kind 3 events that contain p = pubkey.
     * Asked of every count relay + user's own relays at once, with a per-relay limit.
     */
    private suspend fun fetchRelayFollowers(pubkeyHex: String, seedRelays: List<String>): List<String> = withContext(kotlinx.coroutines.Dispatchers.Default) {
        val relays = (COUNT_RELAYS + seedRelays).map { it.trim().trimEnd('/') }.distinct()
        val acquired = mutableListOf<String>()
        val authors = mutableSetOf<String>()
        val subId = "followers-" + java.util.UUID.randomUUID().toString()
        try {
            relays.forEach { pool.acquireRelay(it); acquired.add(it) }
            val filter = Filter(kinds = listOf(EventKind.FOLLOW_LIST), pTags = listOf(pubkeyHex), limit = 1000)
            kotlinx.coroutines.coroutineScope {
                // Install the collector before sending REQ; a fast relay may
                // otherwise answer before SharedFlow has a subscriber.
                val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(Duration.ofSeconds(8).toMillis()) {
                        pool.messages.collect { pm ->
                            val msg = pm.message
                            if (msg is RelayMessage.EventMessage && msg.subscriptionId == subId) {
                                val e = msg.event
                                if (e.kind == EventKind.FOLLOW_LIST && e.verify() &&
                                    e.parsedTags.any { it.name == "p" && it.value()?.equals(pubkeyHex, ignoreCase = true) == true }) {
                                    if (authors.size < 5_000) authors.add(e.pubkey)
                                }
                            }
                        }
                    }
                }
                try {
                    pool.subscribeTo(listOf(filter), id = subId, label = "followers:$pubkeyHex", relays = relays.toSet())
                    collector.join()
                } finally { collector.cancel() }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        finally {
            pool.unsubscribe(subId)
            acquired.forEach { pool.releaseRelay(it) }
        }
        authors.toList()
    }

    fun followers(pubkeyHex: String, seedRelays: List<String> = emptyList()): Flow<FollowersState> = flow {
        val generation = known.generation()
        val memo = known.get(pubkeyHex, seedRelays)
        if (memo != null && memo.followers != null && !memo.loading) {
            emit(memo)
            return@flow
        }
        emit(FollowersState(loading = true))
        // Fetch both sources in parallel and merge their bounded samples.
        val updates = channelFlow {
            val samples = FollowerSamples()
            fun publish(next: List<String>, source: String) {
                synchronized(samples) { samples.merge(next, source)?.let { trySend(it) } }
            }
            launch {
                try {
                    val relayList = fetchRelayFollowers(pubkeyHex, seedRelays)
                    if (relayList.isNotEmpty()) publish(relayList, "relays")
                } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.w(e, "Relay followers fetch failed") }
            }
            launch {
                try {
                    val primal = fetchPrimalHttp(pubkeyHex)
                    if (!primal.isNullOrEmpty()) publish(primal, "primal.net")
                } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.w(e, "Primal followers fetch failed") }
            }
        }
        var last: FollowersState? = null
        updates.collect { state ->
            if (state != last) {
                last = state
                emit(state)
            }
        }
        // If nothing arrived, emit empty
        if (last == null) {
            val empty = FollowersState(followers = emptyList(), loading = false,
                error = "No results; sources may be unavailable or incomplete", source = "no results")
            last = empty
            emit(empty)
        }
        // Cache only after both source jobs finish; otherwise a second screen
        // could read the first partial emission and never see the other source.
        last?.let { known.put(pubkeyHex, seedRelays, it, generation) }
    }

    fun invalidate(pubkeyHex: String) {
        known.invalidate(pubkeyHex)
    }

    fun invalidateAll() {
        known.clear()
    }

    companion object {
        // Monostr COUNT_RELAYS — reused for follower list (they know the most contact lists)
        val COUNT_RELAYS = listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://nos.lol")
    }
}
