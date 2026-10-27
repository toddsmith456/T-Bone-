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
import okhttp3.OkHttpClient
import social.tbone.di.ImageClientProvider
import okhttp3.Request
import org.json.JSONObject

/**
 * Follower list — who follows you.
 *
 * Monostr-style: followers are the largest answer among relays (lower-bound).
 * Each relay only knows the contact lists it stores, so the largest set wins.
 * For T-Bone we also try Primal's centralized index (https://primal.net) via HTTP,
 * but the relay result remains the fallback and wins when larger.
 *
 * Disabled by default (AppSettings.followersEnabled). When enabled a one-time
 * popup warns that the list comes from Primal's centralized cache.
 */
@Singleton
class FollowerListRepository @Inject constructor(
    private val pool: RelayPool,
    private val okHttp: OkHttpClient, // central: if Tor enabled, ImageClientProvider.client is Tor-aware; injected client should be ImageClientProvider.client (see NetworkModule)
) {
    data class FollowersState(
        val followers: List<String>? = null, // null = still loading / unknown
        val loading: Boolean = true,
        val error: String? = null,
        val source: String = "relays",
    )

    private val known = HashMap<String, FollowersState>()
    private val mutex = Mutex()

    // Primal's public caching service: followers list via HTTP.
    // Primal web app uses wss://cache2.primal.net/v1 with method "contacts_list";
    // the public read endpoint is documented as https://primal.net/api/v1/people/<npub>/followers
    // We probe both the primal relay (NIP-45 COUNT is not enough — we need the actual pubkeys) and
    // the HTTP endpoint. Either may be unavailable; we return whatever we get.
    private suspend fun fetchPrimalHttp(pubkeyHex: String): List<String>? {
        return try {
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
                    okHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) continue
                        val body = resp.body?.string() ?: continue
                        val list = parsePrimalBody(body)
                        if (list != null && list.isNotEmpty()) return list
                    }
                } catch (_: Exception) { continue }
            }
            null
        } catch (_: Exception) { null }
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
     * Asked of every count relay + user's own relays at once. Largest set wins.
     */
    private suspend fun fetchRelayFollowers(pubkeyHex: String, seedRelays: List<String>): List<String> {
        val relays = (COUNT_RELAYS + seedRelays).map { it.trim().trimEnd('/') }.distinct()
        // Ensure relays are attached for the query
        relays.forEach { withContext(NonCancellable) { pool.acquireRelay(it) } }
        val filter = Filter(
            kinds = listOf(EventKind.FOLLOW_LIST),
            pTags = listOf(pubkeyHex),
            limit = 1000,
        )
        val subId = pool.subscribeTo(listOf(filter), label = "followers:$pubkeyHex", relays = relays.toSet())
        val authors = mutableSetOf<String>()
        try {
            kotlinx.coroutines.withTimeoutOrNull(Duration.ofSeconds(8).toMillis()) {
                pool.messages.collect { pm ->
                    val msg = pm.message
                    if (msg is RelayMessage.EventMessage && msg.subscriptionId == subId) {
                        val e = msg.event
                        if (e.kind == EventKind.FOLLOW_LIST && e.verify()) {
                            // The author of this kind 3 is a follower (they list pubkeyHex as p)
                            if (e.parsedTags.any { it.name == "p" && it.value()?.lowercase() == pubkeyHex.lowercase() }) {
                                authors.add(e.pubkey)
                            }
                        }
                    }
                    if (msg is RelayMessage.EndOfStoredEvents && msg.subscriptionId == subId) {
                        // Don't break immediately — keep listening for delayed relays
                    }
                }
            }
        } catch (_: CancellationException) { throw _ as CancellationException } catch (_: Exception) {}
        finally {
            pool.unsubscribe(subId)
            relays.forEach { pool.releaseRelay(it) }
        }
        return authors.toList()
    }

    fun followers(pubkeyHex: String, seedRelays: List<String> = emptyList()): Flow<FollowersState> = flow {
        val memo = synchronized(known) { known[pubkeyHex] }
        if (memo != null && memo.followers != null && !memo.loading) {
            emit(memo)
            return@flow
        }
        emit(FollowersState(loading = true))
        // Fetch both sources in parallel (channelFlow like Monostr)
        val updates = channelFlow {
            var current: List<String>? = null
            var currentSource = "relays"
            fun publish(next: List<String>, source: String) {
                if (next.size > (current?.size ?: -1)) {
                    current = next
                    currentSource = source
                    trySend(FollowersState(followers = next, loading = false, source = source))
                }
            }
            launch {
                try {
                    val relayList = fetchRelayFollowers(pubkeyHex, seedRelays)
                    if (relayList.isNotEmpty()) publish(relayList, "relays")
                } catch (e: Exception) { Timber.w(e, "Relay followers fetch failed") }
            }
            launch {
                try {
                    val primal = fetchPrimalHttp(pubkeyHex)
                    if (!primal.isNullOrEmpty()) publish(primal, "primal.net")
                } catch (e: Exception) { Timber.w(e, "Primal followers fetch failed") }
            }
        }
        var last: FollowersState? = null
        updates.collect { state ->
            synchronized(known) { known[pubkeyHex] = state }
            if (state != last) {
                last = state
                emit(state)
            }
        }
        // If nothing arrived, emit empty
        if (last == null) {
            val empty = FollowersState(followers = emptyList(), loading = false)
            synchronized(known) { known[pubkeyHex] = empty }
            emit(empty)
        }
    }

    fun invalidate(pubkeyHex: String) {
        synchronized(known) { known.remove(pubkeyHex) }
    }

    fun invalidateAll() {
        synchronized(known) { known.clear() }
    }

    companion object {
        // Monostr COUNT_RELAYS — reused for follower list (they know the most contact lists)
        val COUNT_RELAYS = listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://nos.lol")
    }
}
