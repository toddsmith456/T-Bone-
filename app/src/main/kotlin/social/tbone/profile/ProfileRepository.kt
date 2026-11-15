package social.tbone.profile

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.Tunables
import social.tbone.db.ProfileDao
import social.tbone.db.ProfileEntity
import social.tbone.db.toProfileContent
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val pool: RelayPool,
) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught exception in ProfileRepository")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // Cache, timestamp guard and observable map are changed together so two
    // overlapping relay responses cannot let an older kind-0 event win a race.
    private val profileLock = Any()

    // LruCache is the authoritative in-memory store; capped at 1000 entries.
    private val cache = LruCache<String, ProfileContent>(Tunables.PROFILE_CACHE_SIZE)

    // Tracks the createdAt of the newest event processed per pubkey.
    private val latestTimestamp = LruCache<String, Long>(Tunables.PROFILE_CACHE_SIZE)

    private val _profiles = MutableStateFlow<Map<String, ProfileContent>>(emptyMap())
    val profiles: StateFlow<Map<String, ProfileContent>> = _profiles.asStateFlow()

    // Option 2: 50ms conflated UI settle channel coalesces bursty kind-0 arrivals
    // into a single StateFlow emission instead of cloning the full map per event.
    private val uiDirty = Channel<Unit>(Channel.CONFLATED)
    private val pendingUiUpdates = LinkedHashMap<String, ProfileContent>()

    // Option 8: 250ms write-behind batcher commits bursts of profiles in one
    // SQLite transaction instead of launching a separate DAO transaction per profile.
    private val dbWriteLock = Any()
    private val pendingDbWrites = LinkedHashMap<String, ProfileEntity>()
    private val dbFlushDebounced = Channel<Unit>(Channel.CONFLATED)
    private val dbFlushImmediate = Channel<Unit>(Channel.CONFLATED)

    // Option 5: 100ms debounced metadata request batcher with per-pubkey cooldown
    // so scattered Feed/Thread/Profile/Hashtag/Notification requests collapse into
    // compact chunked kind-0 REQs that close cleanly after EOSE.
    private val requestLock = Any()
    private val pendingMetadataRequests = LinkedHashSet<String>()
    private val recentMetadataRequests = LruCache<String, Long>(Tunables.PROFILE_CACHE_SIZE * 2)
    private val metadataFlushDebounced = Channel<Unit>(Channel.CONFLATED)
    private val metadataFlushImmediate = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            val cached = runCatching { dao.getAll() }
                .onFailure { Timber.w(it, "Failed loading cached profiles") }
                .getOrDefault(emptyList())
            synchronized(profileLock) {
                cached.forEach { entity ->
                    val content = entity.toProfileContent() ?: return@forEach
                    val current = latestTimestamp.get(entity.pubkey) ?: -1L
                    // A relay can deliver a newer event while the initial DB read
                    // is still running; never seed the cache over that event.
                    if (entity.createdAt > current) {
                        latestTimestamp.put(entity.pubkey, entity.createdAt)
                        cache.put(entity.pubkey, content)
                    }
                }
                // Merge instead of replacing: events processed during the disk
                // read stay visible, and the lock prevents a stale snapshot race.
                val snapshot = cache.snapshot()
                _profiles.update { current ->
                    val merged = current + snapshot
                    if (merged.size > Tunables.PROFILE_CACHE_SIZE) snapshot else merged
                }
            }
        }
        scope.launch(Dispatchers.Default) {
            for (signal in uiDirty) {
                delay(Tunables.UI_BATCH_WINDOW_MS)
                flushUiUpdates()
            }
        }
        scope.launch {
            for (signal in dbFlushDebounced) {
                delay(Tunables.DB_WRITE_BATCH_MS)
                flushPendingDbWrites()
            }
        }
        scope.launch {
            for (signal in dbFlushImmediate) {
                flushPendingDbWrites()
            }
        }
        scope.launch(Dispatchers.Default) {
            for (signal in metadataFlushDebounced) {
                delay(Tunables.METADATA_BATCH_WINDOW_MS)
                flushMetadataRequests()
            }
        }
        scope.launch(Dispatchers.Default) {
            for (signal in metadataFlushImmediate) {
                flushMetadataRequests()
            }
        }
        // Passive collector: any verified kind-0 event arriving on the main pool
        // (including responses to batched profile-batch REQs after a screen closes)
        // updates the cache and database automatically.
        scope.launch(Dispatchers.Default) {
            pool.messages.collect { (_, msg) ->
                runCatching {
                    if (msg is RelayMessage.EventMessage
                        && msg.event.kind == EventKind.METADATA
                        && msg.event.verify()
                    ) {
                        processEvent(msg.event)
                    }
                }.onFailure { t ->
                    if (t is CancellationException) throw t
                    Timber.w(t, "ProfileRepository passive metadata collector failed")
                }
            }
        }
    }

    /**
     * Option 5: Requests kind-0 metadata for any unknown pubkeys in [pubkeys],
     * coalescing bursts over [Tunables.METADATA_BATCH_WINDOW_MS] into compact
     * chunked subscriptions (≤[Tunables.METADATA_BATCH_MAX] authors each) and
     * suppressing repeated requests for missing profiles for
     * [Tunables.METADATA_RETRY_COOLDOWN_MS].
     */
    fun requestProfiles(pubkeys: Collection<String>, forceRefresh: Boolean = false) {
        if (pubkeys.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        var added = false
        var shouldFlushNow = false
        synchronized(requestLock) {
            for (raw in pubkeys) {
                val pk = raw.trim()
                if (pk.isEmpty()) continue
                if (!forceRefresh && hasCachedProfile(pk)) continue
                if (!forceRefresh) {
                    val lastAttempt = recentMetadataRequests.get(pk)
                    if (lastAttempt != null && nowMs - lastAttempt < Tunables.METADATA_RETRY_COOLDOWN_MS) {
                        continue
                    }
                }
                recentMetadataRequests.put(pk, nowMs)
                if (pendingMetadataRequests.add(pk)) added = true
            }
            shouldFlushNow = pendingMetadataRequests.size >= Tunables.METADATA_BATCH_MAX
        }
        if (!added) return
        if (shouldFlushNow) {
            metadataFlushImmediate.trySend(Unit)
        } else {
            metadataFlushDebounced.trySend(Unit)
        }
    }

    private fun hasCachedProfile(pubkey: String): Boolean = synchronized(profileLock) {
        cache.get(pubkey) != null
    }

    private fun flushMetadataRequests() {
        val batch = synchronized(requestLock) {
            if (pendingMetadataRequests.isEmpty()) return
            pendingMetadataRequests.toList().also { pendingMetadataRequests.clear() }
        }
        val chunks = chunkMetadataAuthors(batch, Tunables.METADATA_BATCH_MAX)
        for (authors in chunks) {
            openMetadataBatch(authors)
        }
    }

    private fun openMetadataBatch(authors: List<String>) {
        if (authors.isEmpty()) return
        val subId = pool.subscribe(
            listOf(Filter(authors = authors, kinds = listOf(EventKind.METADATA))),
            label = "profile-batch",
        )
        scope.launch(Dispatchers.Default) {
            try {
                val answeredRelays = mutableSetOf<String>()
                val expectedRelays = pool.relayStatuses.value
                    .count { it.value == RelayStatus.CONNECTED }
                    .coerceAtLeast(1)
                withTimeoutOrNull(Tunables.METADATA_EOSE_TIMEOUT_MS) {
                    pool.messages.first { (relayUrl, msg) ->
                        if (msg !is RelayMessage.EndOfStoredEvents || msg.subscriptionId != subId) {
                            false
                        } else {
                            answeredRelays += relayUrl
                            answeredRelays.size >= expectedRelays
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Timber.w(t, "Batched metadata subscription %s failed while waiting for EOSE", subId)
            } finally {
                pool.unsubscribe(subId)
            }
        }
    }

    private fun flushUiUpdates() {
        val (delta, fallbackSnapshot) = synchronized(profileLock) {
            if (pendingUiUpdates.isEmpty()) return
            val copy = pendingUiUpdates.toMap()
            pendingUiUpdates.clear()
            copy to cache.snapshot()
        }
        _profiles.update { current ->
            val merged = current + delta
            if (merged.size > Tunables.PROFILE_CACHE_SIZE) fallbackSnapshot else merged
        }
    }

    private suspend fun flushPendingDbWrites() {
        val batch = synchronized(dbWriteLock) {
            if (pendingDbWrites.isEmpty()) return
            pendingDbWrites.values.toList().also { pendingDbWrites.clear() }
        }
        runCatching {
            dao.upsertAllIfNewer(batch)
        }.onFailure {
            Timber.w(it, "Failed batch-saving %d profile(s)", batch.size)
        }
    }

    fun processEvent(event: Event) {
        if (event.kind != EventKind.METADATA) return
        val content = ProfileContent.parse(event.content) ?: return

        val accepted = synchronized(profileLock) {
            val existing = latestTimestamp.get(event.pubkey) ?: -1L
            if (event.createdAt <= existing) {
                false
            } else {
                latestTimestamp.put(event.pubkey, event.createdAt)
                cache.put(event.pubkey, content)
                pendingUiUpdates[event.pubkey] = content
                true
            }
        }
        if (!accepted) return

        uiDirty.trySend(Unit)

        val shouldFlushDbNow = synchronized(dbWriteLock) {
            val queued = pendingDbWrites[event.pubkey]
            if (queued == null || event.createdAt > queued.createdAt) {
                pendingDbWrites[event.pubkey] = ProfileEntity(event.pubkey, event.content, event.createdAt)
            }
            pendingDbWrites.size >= Tunables.DB_WRITE_BATCH_MAX
        }
        if (shouldFlushDbNow) {
            dbFlushImmediate.trySend(Unit)
        } else {
            dbFlushDebounced.trySend(Unit)
        }
    }

    /** Trims ancient cached profiles on startup so long-lived databases stay lean. */
    suspend fun pruneOld(maxKeep: Int = Tunables.MAX_CACHED_PROFILES): Int =
        dao.keepLatest(maxKeep)

    fun getProfile(pubkey: String): ProfileContent? = synchronized(profileLock) { cache.get(pubkey) }

    companion object {
        /** Splits a pubkey batch into distinct, non-blank relay-safe chunks. */
        internal fun chunkMetadataAuthors(
            pubkeys: Collection<String>,
            maxPerFilter: Int = Tunables.METADATA_BATCH_MAX,
        ): List<List<String>> {
            val step = maxPerFilter.coerceAtLeast(1)
            return pubkeys.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .chunked(step)
                .toList()
        }
    }
}
