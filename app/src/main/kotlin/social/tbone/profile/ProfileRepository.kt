package social.tbone.profile

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.Tunables
import social.tbone.account.AccountRepository
import social.tbone.db.ProfileDao
import social.tbone.db.ProfileEntity
import social.tbone.db.buildProfileSearchText
import social.tbone.db.toProfileContent
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import social.tbone.settings.AppSettings
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exponential backoff state for pubkeys that returned no kind-0 from relays.
 * Stops T-Bone from re-querying profile-less pubkeys every 60 seconds while
 * still recovering automatically if they publish metadata later.
 */
internal data class NegativeLookupEntry(
    val lastAttemptMs: Long,
    val attempts: Int,
) {
    fun cooldownMs(): Long = cooldownForAttempt(attempts)

    fun isCoolingDown(nowMs: Long): Boolean = nowMs - lastAttemptMs < cooldownMs()

    companion object {
        private val BACKOFF_STEPS_MS = longArrayOf(
            60_000L,           // 1st miss: 1 minute
            5L * 60_000L,      // 2nd miss: 5 minutes
            30L * 60_000L,     // 3rd miss: 30 minutes
            2L * 60 * 60_000L, // 4th+ miss: 2 hours
        )

        fun cooldownForAttempt(attempts: Int): Long {
            val idx = (attempts - 1).coerceIn(0, BACKOFF_STEPS_MS.lastIndex)
            return BACKOFF_STEPS_MS[idx]
        }
    }
}

@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val pool: RelayPool,
    private val appSettings: AppSettings,
    private val accountRepository: AccountRepository,
    /** Optional diagnostics sink (see StartupMetrics); null in unit tests. */
    private val startupMetrics: social.tbone.logging.StartupMetrics? = null,
) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught exception in ProfileRepository")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // Cache, timestamp guard, fetchedAt guard and observable map are changed together
    // so two overlapping relay responses cannot let an older kind-0 event win a race.
    private val profileLock = Any()

    @Volatile
    private var currentRamLimit: Int = appSettings.cachePreset.value.profileRamLimit

    // L1 RAM hot cache — dynamically resizes with CachePreset (500 → 6,000).
    private val cache = LruCache<String, ProfileContent>(currentRamLimit)

    // Tracks the kind-0 createdAt of the newest event processed per pubkey.
    private val latestTimestamp = LruCache<String, Long>(currentRamLimit)

    // Tracks local epoch ms when metadata was last fetched/confirmed from relays (SWR).
    private val lastFetchedAt = LruCache<String, Long>(currentRamLimit)

    private val _profiles = MutableStateFlow<Map<String, ProfileContent>>(emptyMap())
    val profiles: StateFlow<Map<String, ProfileContent>> = _profiles.asStateFlow()

    // Emits (pubkey, newPictureUrl) whenever a profile's avatar URL changes so
    // MediaPrefetcher can warm the new avatar in the background.
    private val _updatedAvatarUrls = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val updatedAvatarUrls: SharedFlow<Pair<String, String>> = _updatedAvatarUrls.asSharedFlow()

    // Completes as soon as the initial warm Room read finishes so startup feed
    // requests never race ahead of cached disk profiles and fire redundant REQs.
    private val dbHydrated = CompletableDeferred<Unit>()

    // Own account pubkeys + followed pubkeys (pinned for warm-up and protected from eviction).
    private val priorityPubkeys = ConcurrentHashMap.newKeySet<String>()
    private val extraOfflineFollows = ConcurrentHashMap.newKeySet<String>()

    // 50ms conflated UI settle channel coalesces bursty kind-0 arrivals.
    private val uiDirty = Channel<Unit>(Channel.CONFLATED)
    private val pendingUiUpdates = LinkedHashMap<String, ProfileContent>()

    // 250ms write-behind batcher commits bursts of profiles in one SQLite transaction.
    private val dbWriteLock = Any()
    private val pendingDbWrites = LinkedHashMap<String, ProfileEntity>()
    private val dbFlushDebounced = Channel<Unit>(Channel.CONFLATED)
    private val dbFlushImmediate = Channel<Unit>(Channel.CONFLATED)

    // Debounced lastAccessedAt updater (2s window) keeps LRU order fresh without SQLite churn.
    private val touchLock = Any()
    private val pendingTouches = LinkedHashSet<String>()
    private val touchFlush = Channel<Unit>(Channel.CONFLATED)

    // 100ms debounced metadata request batcher with exponential backoff on misses.
    private val requestLock = Any()
    private val pendingMetadataRequests = LinkedHashSet<String>()
    private val recentMetadataAttempts = LruCache<String, NegativeLookupEntry>(4_000)
    private val metadataFlushDebounced = Channel<Unit>(Channel.CONFLATED)
    private val metadataFlushImmediate = Channel<Unit>(Channel.CONFLATED)

    init {
        // Hydrate L1 RAM from Room ordered by (isFollowed DESC, lastAccessedAt DESC, createdAt DESC).
        scope.launch {
            try {
                val warmLimit = appSettings.cachePreset.value.profileRamLimit
                val cached = runCatching { dao.getWarmProfiles(warmLimit) }
                    .onFailure { Timber.w(it, "Failed loading cached profiles") }
                    .getOrDefault(emptyList())
                seedFromEntities(cached, touchDb = false)
            } finally {
                dbHydrated.complete(Unit)
            }
            // One-time lazy backfill of normalized searchText for upgraded v19 rows.
            backfillMissingSearchText()
        }

        // Resize RAM caches dynamically whenever the user switches CachePreset.
        scope.launch {
            appSettings.cachePreset.collect { preset ->
                val target = preset.profileRamLimit.coerceAtLeast(100)
                synchronized(profileLock) {
                    if (target != currentRamLimit) {
                        currentRamLimit = target
                        runCatching { cache.resize(target) }
                        runCatching { latestTimestamp.resize(target) }
                        runCatching { lastFetchedAt.resize(target) }
                    }
                }
            }
        }

        // Sync follow-graph + own account priority flags into Room so friends are
        // never evicted by Global/Suggested strangers.
        scope.launch {
            accountRepository.accounts
                .map { accounts ->
                    buildSet {
                        for (acct in accounts) {
                            add(acct.pubkey)
                            addAll(acct.follows)
                        }
                    }
                }
                .distinctUntilChanged()
                .collect { accountPriority ->
                    val merged = LinkedHashSet<String>(accountPriority.size + extraOfflineFollows.size)
                    merged.addAll(accountPriority)
                    merged.addAll(extraOfflineFollows)
                    priorityPubkeys.clear()
                    priorityPubkeys.addAll(merged)
                    dbHydrated.await()
                    runCatching { dao.syncFollowedFlags(merged) }
                        .onFailure { Timber.w(it, "Failed syncing followed profile flags") }
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
        scope.launch {
            for (signal in touchFlush) {
                delay(TOUCH_DEBOUNCE_MS)
                flushPendingTouches()
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

    /** Registers additional priority pubkeys (e.g. from offline follow lists). */
    fun updateOfflinePriorityPubkeys(pubkeys: Set<String>) {
        if (pubkeys == extraOfflineFollows) return
        extraOfflineFollows.clear()
        extraOfflineFollows.addAll(pubkeys)
        val merged = LinkedHashSet<String>(priorityPubkeys.size + pubkeys.size).apply {
            addAll(priorityPubkeys)
            addAll(pubkeys)
        }
        priorityPubkeys.addAll(pubkeys)
        scope.launch {
            dbHydrated.await()
            runCatching { dao.syncFollowedFlags(merged) }
        }
    }

    /**
     * Requests kind-0 metadata for [pubkeys]:
     *  1. Awaits initial Room warm-up (`dbHydrated`) to prevent startup races.
     *  2. Checks L1 RAM; if present and fresh, skips network completely.
     *  3. For RAM misses, performs a fast batch L2 Room lookup (`dao.getByPubkeys`)
     *     so ALL 1,000–75,000 cached disk profiles resolve locally with zero network!
     *  4. For stale entries (past the preset's SWR TTL) or true misses, queues a
     *     batched kind-0 relay REQ with exponential backoff on repeated misses.
     */
    fun requestProfiles(pubkeys: Collection<String>, forceRefresh: Boolean = false) {
        if (pubkeys.isEmpty()) return
        val cleaned = pubkeys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (cleaned.isEmpty()) return

        scope.launch {
            dbHydrated.await()
            resolveProfilesInternal(cleaned, forceRefresh)
        }
    }

    private suspend fun resolveProfilesInternal(pubkeys: List<String>, forceRefresh: Boolean) {
        val nowMs = System.currentTimeMillis()
        val swrTtlMs = appSettings.cachePreset.value.profileSwrTtlMs

        val ramMisses = mutableListOf<String>()
        val needNetwork = mutableListOf<String>()
        val accessed = mutableListOf<String>()

        synchronized(profileLock) {
            for (pk in pubkeys) {
                val cached = cache.get(pk)
                if (cached != null) {
                    accessed += pk
                    val fetched = lastFetchedAt.get(pk) ?: 0L
                    if (forceRefresh || isStale(fetched, nowMs, swrTtlMs)) {
                        needNetwork += pk
                    }
                } else {
                    ramMisses += pk
                }
            }
        }

        // L2 Room lookup for every RAM miss before hitting any relay.
        if (ramMisses.isNotEmpty()) {
            val diskEntities = runCatching {
                ramMisses.chunked(SQLITE_IN_BATCH).flatMap { chunk -> dao.getByPubkeys(chunk) }
            }.getOrDefault(emptyList())

            val foundPubkeys = HashSet<String>(diskEntities.size)
            if (diskEntities.isNotEmpty()) {
                seedFromEntities(diskEntities, touchDb = false)
                for (entity in diskEntities) {
                    foundPubkeys += entity.pubkey
                    accessed += entity.pubkey
                    if (forceRefresh || isStale(entity.fetchedAt, nowMs, swrTtlMs)) {
                        needNetwork += entity.pubkey
                    }
                }
            }
            for (pk in ramMisses) {
                if (pk !in foundPubkeys) {
                    needNetwork += pk
                }
            }
        }

        if (accessed.isNotEmpty()) {
            scheduleTouch(accessed)
        }
        if (needNetwork.isNotEmpty()) {
            enqueueNetworkRequests(needNetwork, forceRefresh, nowMs)
        }
    }

    private fun isStale(fetchedAtMs: Long, nowMs: Long, swrTtlMs: Long): Boolean {
        if (fetchedAtMs <= 0L) return true
        return nowMs - fetchedAtMs >= swrTtlMs
    }

    private fun enqueueNetworkRequests(pubkeys: List<String>, forceRefresh: Boolean, nowMs: Long) {
        var added = false
        var shouldFlushNow = false
        synchronized(requestLock) {
            for (pk in pubkeys) {
                val prev = recentMetadataAttempts.get(pk)
                if (!forceRefresh && prev != null && prev.isCoolingDown(nowMs)) {
                    continue
                }
                val nextAttempts = if (forceRefresh) 1 else (prev?.attempts ?: 0) + 1
                recentMetadataAttempts.put(pk, NegativeLookupEntry(nowMs, nextAttempts))
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

    private fun seedFromEntities(entities: List<ProfileEntity>, touchDb: Boolean) {
        if (entities.isEmpty()) return
        val touched = mutableListOf<String>()
        synchronized(profileLock) {
            entities.forEach { entity ->
                val content = entity.toProfileContent() ?: return@forEach
                val current = latestTimestamp.get(entity.pubkey) ?: -1L
                if (entity.createdAt > current) {
                    latestTimestamp.put(entity.pubkey, entity.createdAt)
                    cache.put(entity.pubkey, content)
                    if (entity.fetchedAt > 0L) {
                        lastFetchedAt.put(entity.pubkey, entity.fetchedAt)
                    }
                    touched += entity.pubkey
                } else if (entity.createdAt == current && entity.fetchedAt > (lastFetchedAt.get(entity.pubkey) ?: 0L)) {
                    lastFetchedAt.put(entity.pubkey, entity.fetchedAt)
                }
            }
            val snapshot = cache.snapshot()
            _profiles.update { current ->
                val merged = current + snapshot
                if (merged.size > currentRamLimit) snapshot else merged
            }
        }
        if (touchDb && touched.isNotEmpty()) {
            scheduleTouch(touched)
        }
    }

    private fun scheduleTouch(pubkeys: Collection<String>) {
        synchronized(touchLock) {
            pendingTouches.addAll(pubkeys)
        }
        touchFlush.trySend(Unit)
    }

    private suspend fun flushPendingTouches() {
        val batch = synchronized(touchLock) {
            if (pendingTouches.isEmpty()) return
            pendingTouches.toList().also { pendingTouches.clear() }
        }
        val nowMs = System.currentTimeMillis()
        runCatching {
            for (chunk in batch.chunked(SQLITE_IN_BATCH)) {
                dao.touchAccessed(chunk, nowMs)
            }
        }.onFailure { Timber.w(it, "Failed updating profile lastAccessedAt") }
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
            val batchStartedAt = System.currentTimeMillis()
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
                startupMetrics?.metadataBatch(System.currentTimeMillis() - batchStartedAt)
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
            if (merged.size > currentRamLimit) fallbackSnapshot else merged
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
        val nowMs = System.currentTimeMillis()
        val isPriority = event.pubkey in priorityPubkeys

        // Successful kind-0 response resets any negative-lookup backoff.
        synchronized(requestLock) {
            recentMetadataAttempts.remove(event.pubkey)
        }

        var avatarChangedTo: String? = null
        val acceptedNewer: Boolean
        val confirmedSame: Boolean

        synchronized(profileLock) {
            val existingTs = latestTimestamp.get(event.pubkey) ?: -1L
            val previousPicture = cache.get(event.pubkey)?.picture?.trim()
            when {
                event.createdAt > existingTs -> {
                    latestTimestamp.put(event.pubkey, event.createdAt)
                    lastFetchedAt.put(event.pubkey, nowMs)
                    cache.put(event.pubkey, content)
                    pendingUiUpdates[event.pubkey] = content
                    val nextPicture = content.picture?.trim()
                    if (!nextPicture.isNullOrBlank() && nextPicture != previousPicture && isPriority) {
                        avatarChangedTo = nextPicture
                    }
                    acceptedNewer = true
                    confirmedSame = false
                }
                event.createdAt == existingTs -> {
                    // SWR confirmation: profile didn't change, refresh fetchedAt so
                    // we don't keep polling it again until the next TTL window.
                    lastFetchedAt.put(event.pubkey, nowMs)
                    acceptedNewer = false
                    confirmedSame = true
                }
                else -> {
                    acceptedNewer = false
                    confirmedSame = false
                }
            }
        }

        if (!acceptedNewer && !confirmedSame) return

        if (acceptedNewer) {
            uiDirty.trySend(Unit)
            avatarChangedTo?.let { url ->
                _updatedAvatarUrls.tryEmit(event.pubkey to url)
            }
        }

        val entity = ProfileEntity(
            pubkey = event.pubkey,
            contentJson = event.content,
            createdAt = event.createdAt,
            lastAccessedAt = nowMs,
            fetchedAt = nowMs,
            isFollowed = isPriority,
            searchText = buildProfileSearchText(content),
        )
        val shouldFlushDbNow = synchronized(dbWriteLock) {
            val queued = pendingDbWrites[event.pubkey]
            if (queued == null || event.createdAt >= queued.createdAt) {
                pendingDbWrites[event.pubkey] = entity
            }
            pendingDbWrites.size >= Tunables.DB_WRITE_BATCH_MAX
        }
        if (shouldFlushDbNow) {
            dbFlushImmediate.trySend(Unit)
        } else {
            dbFlushDebounced.trySend(Unit)
        }
    }

    /**
     * Full offline search across BOTH RAM and the entire local Room `profiles`
     * table (up to 75,000 profiles on Turbo), ranked with followed accounts first.
     */
    suspend fun searchCachedProfiles(
        query: String,
        limit: Int = 30,
    ): List<Pair<String, ProfileContent>> = withContext(Dispatchers.IO) {
        val normalized = ProfileSearch.normalize(query)
        if (normalized.isBlank()) return@withContext emptyList()

        val merged = LinkedHashMap<String, ProfileContent>()

        // 1. Instant RAM scan.
        for ((pk, profile) in _profiles.value) {
            if (ProfileSearch.matches(query, profile)) {
                merged[pk] = profile
            }
        }

        // 2. Full local Room table scan across all persisted profiles.
        val likePattern = "%${escapeLikePattern(normalized)}%"
        val diskRows = runCatching { dao.searchByText(likePattern, limit * 2) }
            .getOrDefault(emptyList())
        if (diskRows.isNotEmpty()) {
            seedFromEntities(diskRows, touchDb = false)
            for (row in diskRows) {
                val parsed = row.toProfileContent() ?: continue
                if (ProfileSearch.matches(query, parsed)) {
                    merged.putIfAbsent(row.pubkey, parsed)
                }
            }
        }

        merged.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, ProfileContent>> { it.key in priorityPubkeys }
                    .thenBy { !(it.value.bestName?.startsWith(query, ignoreCase = true) == true) }
                    .thenBy { it.value.bestName?.lowercase() ?: it.key },
            )
            .take(limit)
            .map { it.key to it.value }
    }

    private suspend fun backfillMissingSearchText() {
        runCatching {
            while (true) {
                val rows = dao.getRowsMissingSearchText(250)
                if (rows.isEmpty()) break
                val updates = rows.map { row ->
                    val text = row.toProfileContent()?.let(::buildProfileSearchText)
                        ?.ifBlank { " " } // Sentinel space prevents infinite re-scan of blank profiles.
                        ?: " "
                    row.pubkey to text
                }
                dao.updateSearchTexts(updates)
                if (rows.size < 250) break
                delay(50)
            }
        }.onFailure { Timber.w(it, "Background profile searchText backfill failed") }
    }

    /** Trims ancient unfollowed cached profiles on startup according to active CachePreset. */
    suspend fun pruneOld(maxKeep: Int = appSettings.cachePreset.value.maxCachedProfiles): Int =
        dao.keepLatest(maxKeep.coerceAtLeast(500))

    /** Removes cached unfollowed profiles (keeps followed & own accounts intact). */
    suspend fun clearUnfollowedCache(): Int {
        val removed = dao.deleteUnfollowed()
        val warm = runCatching { dao.getWarmProfiles(currentRamLimit) }.getOrDefault(emptyList())
        synchronized(profileLock) {
            cache.evictAll()
            latestTimestamp.evictAll()
            lastFetchedAt.evictAll()
            pendingUiUpdates.clear()
        }
        seedFromEntities(warm, touchDb = false)
        return removed
    }

    /** Clears all in-memory profile caches immediately (used during duress wipe). */
    fun clearMemory() {
        synchronized(profileLock) {
            cache.evictAll()
            latestTimestamp.evictAll()
            lastFetchedAt.evictAll()
            pendingUiUpdates.clear()
            _profiles.value = emptyMap()
        }
        synchronized(dbWriteLock) { pendingDbWrites.clear() }
        synchronized(touchLock) { pendingTouches.clear() }
        synchronized(requestLock) {
            pendingMetadataRequests.clear()
            recentMetadataAttempts.evictAll()
        }
        priorityPubkeys.clear()
        extraOfflineFollows.clear()
    }

    fun getProfile(pubkey: String): ProfileContent? = synchronized(profileLock) { cache.get(pubkey) }

    fun getAvatarUrl(pubkey: String): String? = getProfile(pubkey)?.picture?.takeIf { it.isNotBlank() }

    companion object {
        private const val SQLITE_IN_BATCH = 500
        private const val TOUCH_DEBOUNCE_MS = 2_000L

        internal fun escapeLikePattern(value: String): String =
            value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")

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
