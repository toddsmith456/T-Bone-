package social.tbone.notifications

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import social.tbone.account.AccountRepository
import social.tbone.calendar.CalendarRepository
import social.tbone.db.EventRepository
import social.tbone.db.NotificationDao
import social.tbone.db.NotificationEntity
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.Nip88
import social.tbone.nostr.NostrJson
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.replyEventId
import social.tbone.profile.ProfileRepository
import social.tbone.settings.AppSettings
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The full Wisp-style notification mechanism, kept alive app-wide.
 *
 * - Holds long-lived relay subscriptions for the active account (p-tags, plus
 *   an e-tag subscription over your own recent notes), re-sent by RelayPool on
 *   every reconnect.
 * - Caches the last 24 hours in Room so the tab opens instantly with real
 *   content (no endless spinner); older history loads on demand via [loadMore].
 * - Groups reactions/reposts per referenced note (emoji → actor lists),
 *   replies/quotes/mentions per event, follows per actor; keeps a flat list,
 *   a 24h summary and an unread flag.
 * - Debounces rebuilds (16 ms) and dedupes with a seen-events cache.
 * - Ownership: reactions/reposts only notify when the referenced note is yours.
 */
@Singleton
class NotificationsRepository @Inject constructor(
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
    private val profileRepository: ProfileRepository,
    private val eventRepository: EventRepository,
    private val appSettings: AppSettings,
    private val notificationDao: NotificationDao,
    private val calendarRepository: CalendarRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val seenEvents = LruCache<String, Boolean>(4000)
    private val lock = Any()
    private val groupMap = mutableMapOf<String, NotifGroup>()
    private val flatItems = mutableListOf<FlatNotifItem>()
    private val flatItemIds = mutableSetOf<String>()
    // id -> created_at, insertion-ordered so we can prune the oldest.
    private val myOwnEventIds = LinkedHashMap<String, Long>()

    @Volatile private var currentPubkey: String? = null
    @Volatile private var latestNotifTs = 0L
    @Volatile private var lastReadTs = 0L
    @Volatile private var settled = false

    private var notifSubId: String? = null
    private var ownRefSubId: String? = null
    private var ownRefResubJob: Job? = null
    private var eoseTimeoutJob: Job? = null
    private val refSubIds = ConcurrentHashMap.newKeySet<String>()
    private val profileSubIds = ConcurrentHashMap.newKeySet<String>()

    // "Load more" pagination state
    private var pagedSubId: String? = null
    private val pagedBuffer = mutableListOf<Event>()
    // Day-window pagination: we fetch one 24h window at a time going back.
    @Volatile private var pagedUntil: Long = 0L
    @Volatile private var pagedWindowStart: Long = 0L
    @Volatile private var emptyDaySkips = 0
    private val refSubToIds = ConcurrentHashMap<String, List<String>>()
    private val refFetchAttempts = ConcurrentHashMap<String, Int>()
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val rebuildSignals = Channel<Unit>(Channel.CONFLATED)

    private val _groups = MutableStateFlow<List<NotifGroup>>(emptyList())
    val groups: StateFlow<List<NotifGroup>> = _groups.asStateFlow()

    private val _flat = MutableStateFlow<List<FlatNotifItem>>(emptyList())

    /**
     * Calendar events surface in the notification tab exactly like any other
     * notification: upcoming / in-progress events become CALENDAR rows,
     * merged with the Nostr feed and sorted newest-first.
     */
    val flat: Flow<List<FlatNotifItem>> = combine(
        _flat,
        calendarRepository.events.map { events ->
            val now = System.currentTimeMillis()
            // Calendar events surface only from 10 minutes before an
            // occurrence starts (and stay while one is starting) — same rule
            // as the OS alarms. Repeating events contribute each occurrence.
            val windowStart = now - 10L * 60L * 1000L
            val windowEnd = now + 10L * 60L * 1000L
            events.mapNotNull { event ->
                val next = event.nextOccurrenceAtOrAfter(windowStart) ?: return@mapNotNull null
                if (next > windowEnd) null
                else FlatNotifItem(
                    id = "cal_${event.id}_$next",
                    type = NotifType.CALENDAR,
                    actorPubkey = "calendar",
                    timestamp = next,
                    calendarEventId = event.id,
                    calendarTitle = event.title,
                )
            }
        },
    ) { nostr, cal ->
        (nostr + cal).sortedByDescending { it.timestamp }
    }

    private val _summary = MutableStateFlow(NotifSummary())
    val summary: StateFlow<NotifSummary> = _summary.asStateFlow()

    private val _hasUnread = MutableStateFlow(false)
    val hasUnread: StateFlow<Boolean> = _hasUnread.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _referencedNotes = MutableStateFlow<Map<String, Event>>(emptyMap())
    val referencedNotes: StateFlow<Map<String, Event>> = _referencedNotes.asStateFlow()

    companion object {
        /** The on-disk cache window: 24 hours of notifications. */
        private const val CACHE_WINDOW_SECONDS = 86_400L
        private const val PAGE_SIZE = 50
    }

    init {
        scope.launch {
            appSettings.lastViewedNotificationsAt.collect { ts ->
                lastReadTs = ts
                updateUnread()
            }
        }
        scope.launch {
            accountRepository.activeAccount.collect { account ->
                if (account != null) start(account.pubkey) else stop()
            }
        }
        scope.launch {
            for (signal in rebuildSignals) {
                delay(16)
                while (rebuildSignals.tryReceive().isSuccess) Unit
                rebuild()
            }
        }
        scope.launch {
            pool.messages.collect { poolMessage ->
                val msg = poolMessage.message
                when (msg) {
                    is RelayMessage.EventMessage -> {
                        val event = msg.event
                        if (!event.verify()) return@collect
                        when {
                            msg.subscriptionId in refSubIds -> {
                                _referencedNotes.update { it + (event.id to event) }
                                scope.launch { eventRepository.save(event, "") }
                            }
                            event.kind == EventKind.METADATA -> profileRepository.processEvent(event)
                            msg.subscriptionId == pagedSubId -> ingestPaged(event)
                            msg.subscriptionId == notifSubId -> ingest(event, fromOwnRefSub = false)
                            msg.subscriptionId == ownRefSubId -> ingest(event, fromOwnRefSub = true)
                            else -> {
                                val me = currentPubkey
                                if (me != null && event.pubkey == me) {
                                    rememberOwnEvent(event)
                                }
                            }
                        }
                    }
                    is RelayMessage.EndOfStoredEvents -> {
                        when {
                            msg.subscriptionId == notifSubId && !settled -> {
                                settled = true
                                _isLoading.value = false
                                rebuildSignals.trySend(Unit)
                            }
                            msg.subscriptionId == pagedSubId -> finishPaged(msg.subscriptionId)
                            msg.subscriptionId in refSubIds -> {
                                val ids = refSubToIds.remove(msg.subscriptionId)
                                refSubIds.remove(msg.subscriptionId)
                                pool.unsubscribe(msg.subscriptionId)
                                // Parent notes sometimes lag on relays; retry a
                                // few times with a delay before giving up.
                                val stillMissing = ids?.filter { it !in _referencedNotes.value }.orEmpty()
                                if (stillMissing.isNotEmpty() &&
                                    stillMissing.any { (refFetchAttempts[it] ?: 0) < 3 }
                                ) {
                                    scope.launch {
                                        delay(6_000)
                                        ensureReferencedNotes()
                                    }
                                }
                            }
                            msg.subscriptionId in profileSubIds -> {
                                profileSubIds.remove(msg.subscriptionId)
                                pool.unsubscribe(msg.subscriptionId)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private suspend fun start(pubkey: String) {
        stop()
        currentPubkey = pubkey
        latestNotifTs = 0L
        settled = false
        _isLoading.value = true

        // Restore the 24h cache instantly so the tab opens with real content.
        val cached = notificationDao.getRecent(pubkey, now() - CACHE_WINDOW_SECONDS, 1000)
        synchronized(lock) {
            groupMap.clear()
            flatItems.clear()
            flatItemIds.clear()
            cached.forEach { entity ->
                runCatching { NostrJson.decodeFromString<FlatNotifItem>(entity.json) }
                    .getOrNull()?.let { item ->
                        flatItems.add(item)
                        flatItemIds.add(item.id)
                    }
            }
            flatItems.sortByDescending { it.timestamp }
            rebuildGroupsFromFlatLocked()
        }
        _hasMore.value = synchronized(lock) { flatItems.isNotEmpty() }
        rebuild()

        // Drop anything older than the cache window, keep history tidy.
        scope.launch { notificationDao.deleteOlderThan(pubkey, now() - CACHE_WINDOW_SECONDS) }

        // Seed "events I wrote" from the local cache (feed cache includes other
        // people's notes — keep only this account's events).
        scope.launch {
            val mine = eventRepository.getRecentFeedEvents(pubkey).filter { it.pubkey == pubkey }
            synchronized(lock) {
                mine.forEach { myOwnEventIds[it.id] = it.createdAt }
                pruneOwnEventIdsLocked()
            }
            resubscribeOwnRefs()
        }

        subscribeNotifications(pubkey)

        // Safety net: never leave the spinner spinning if a relay never sends
        // EOSE. Show what we have (cached or empty) after a few seconds.
        eoseTimeoutJob?.cancel()
        eoseTimeoutJob = scope.launch {
            delay(8_000)
            if (!settled) {
                settled = true
                _isLoading.value = false
                rebuildSignals.trySend(Unit)
            }
        }
    }

    private fun stop() {
        currentPubkey = null
        notifSubId?.let { pool.unsubscribe(it) }
        notifSubId = null
        ownRefSubId?.let { pool.unsubscribe(it) }
        ownRefSubId = null
        ownRefResubJob?.cancel()
        ownRefResubJob = null
        pagedSubId?.let { pool.unsubscribe(it) }
        pagedSubId = null
        eoseTimeoutJob?.cancel()
        eoseTimeoutJob = null
        refSubIds.forEach { pool.unsubscribe(it) }
        refSubIds.clear()
        profileSubIds.forEach { pool.unsubscribe(it) }
        profileSubIds.clear()
    }

    private fun subscribeNotifications(pubkey: String) {
        val since = now() - CACHE_WINDOW_SECONDS
        notifSubId = pool.subscribe(
            listOf(Filter.notifications(pubkey, limit = 50).copy(since = since)),
            label = "notifications",
        )
    }

    /** Pull-to-refresh: drop live state, keep cache, re-ask relays. */
    fun refresh() {
        val me = currentPubkey ?: return
        synchronized(lock) {
            seenEvents.evictAll()
            groupMap.clear()
            flatItems.clear()
            flatItemIds.clear()
        }
        _groups.value = emptyList()
        _flat.value = emptyList()
        _isLoading.value = true
        settled = false
        notifSubId?.let { pool.unsubscribe(it) }
        subscribeNotifications(me)
        eoseTimeoutJob?.cancel()
        eoseTimeoutJob = scope.launch {
            delay(8_000)
            if (!settled) {
                settled = true
                _isLoading.value = false
                rebuildSignals.trySend(Unit)
            }
        }
    }

    /** Marks everything seen as of right now. */
    fun markRead() {
        scope.launch {
            appSettings.setLastViewedNotificationsAt(System.currentTimeMillis() / 1000)
        }
    }

    // ── Ingestion ─────────────────────────────────────────────────────────────

    private suspend fun ingest(event: Event, fromOwnRefSub: Boolean) {
        val me = currentPubkey ?: return
        if (event.pubkey == me) {
            rememberOwnEvent(event)
            return
        }
        // Reposts may omit the p-tag; e-tag-subscription finds are by definition
        // about one of your notes.
        val hasPTag = event.parsedTags.any { it.name == "p" && it.value() == me }
        if (!hasPTag && !fromOwnRefSub && event.kind != EventKind.REPOST) return
        synchronized(lock) {
            if (seenEvents.get(event.id) != null) return
            seenEvents.put(event.id, true)
        }
        val item = mergeEvent(event)
        if (item != null) {
            noteSeen(event)
            persist(item)
        }
    }

    /** Older, on-demand page — merge without touching unread/latest markers. */
    private fun ingestPaged(event: Event) {
        val me = currentPubkey ?: return
        if (event.pubkey == me) return
        synchronized(lock) {
            if (seenEvents.get(event.id) != null) return
            seenEvents.put(event.id, true)
            pagedBuffer.add(event)
        }
    }

    private suspend fun mergeEvent(event: Event): FlatNotifItem? {
        val me = currentPubkey ?: return null
        return when (event.kind) {
            EventKind.REACTION, EventKind.REPOST -> {
                val refId = event.parsedTags.lastOrNull { it.name == "e" }?.value() ?: return null
                val cached = eventRepository.getById(refId)
                val isOwn = cached?.pubkey == me ||
                    synchronized(lock) { refId in myOwnEventIds }
                if (!isOwn) return null
                synchronized(lock) {
                    if (event.kind == EventKind.REACTION) mergeReactionLocked(event, refId)
                    else mergeRepostLocked(event, refId)
                }
            }
            EventKind.TEXT_NOTE -> {
                val quoteId = event.parsedTags.firstOrNull { it.name == "q" }?.value()
                val replyId = event.parsedTags.replyEventId
                val item = synchronized(lock) {
                    when {
                        quoteId != null -> mergeQuoteLocked(event, quoteId)
                        replyId != null -> mergeReplyLocked(event, replyId)
                        else -> mergeMentionLocked(event)
                    }
                }
                scope.launch { eventRepository.save(event, "") }
                item
            }
            EventKind.POLL_RESPONSE -> {
                // Only notify when the poll being voted on is ours.
                val pollId = Nip88.getPollEventId(event) ?: return null
                val cached = eventRepository.getById(pollId)
                val isOwn = cached?.pubkey == me ||
                    synchronized(lock) { pollId in myOwnEventIds }
                if (!isOwn) return null
                synchronized(lock) { mergeVoteLocked(event, pollId) }
            }
            else -> null
        }
    }

    private fun noteSeen(event: Event) {
        if (event.createdAt > latestNotifTs) {
            latestNotifTs = event.createdAt
        }
        rebuildSignals.trySend(Unit)
        updateUnread()
    }

    /** Persist a merged notification to the 24h cache. */
    private fun persist(item: FlatNotifItem) {
        val me = currentPubkey ?: return
        scope.launch {
            runCatching {
                notificationDao.upsert(
                    NotificationEntity(
                        id = item.id,
                        pubkey = me,
                        type = item.type.name,
                        json = NostrJson.encodeToString(FlatNotifItem.serializer(), item),
                        createdAt = item.timestamp,
                    )
                )
            }
        }
    }

    // ── "Load more" pagination ────────────────────────────────────────────────

    /**
     * Fetches an older page of notifications from the relays, before the
     * oldest one currently loaded. Cached items older than the 24h window are
     * loaded on demand this way; the button disappears when relays return
     * nothing new.
     */
    /**
     * Loads ONE full day (24h window) of older notifications, ending just
     * before the oldest thing we have. If that day has nothing, it skips
     * ahead to the previous day automatically. The "load older" button stays
     * visible after every non-empty day, and hides once relays return nothing
     * for several consecutive days (or we're more than 90 days back).
     */
    fun loadMore() {
        val me = currentPubkey ?: return
        if (_loadingMore.value) return
        if (pagedUntil <= 0L) {
            pagedUntil = synchronized(lock) { flatItems.minOfOrNull { it.timestamp } } ?: return
            if (pagedUntil <= 0L) return
        }
        val windowStart = pagedUntil - 86_400L
        pagedWindowStart = windowStart
        _loadingMore.value = true
        pagedBuffer.clear()
        val subId = pool.subscribe(
            listOf(
                Filter.notifications(me, limit = 200)
                    .copy(until = pagedUntil - 1, since = windowStart)
            ),
            label = "notif-older",
        )
        pagedSubId = subId
    }

    private fun finishPaged(subId: String) {
        pool.unsubscribe(subId)
        pagedSubId = null
        val events = synchronized(lock) {
            val batch = pagedBuffer.toList()
            pagedBuffer.clear()
            batch
        }
        val windowStart = pagedWindowStart
        if (events.isNotEmpty()) {
            scope.launch {
                var merged = 0
                for (event in events) {
                    val item = mergeEvent(event)
                    if (item != null) {
                        merged++
                        persist(item)
                    }
                }
                synchronized(lock) {
                    flatItems.sortByDescending { it.timestamp }
                    rebuildGroupsFromFlatLocked()
                }
                rebuild()
                emptyDaySkips = 0
                // Next page continues before this day's window.
                pagedUntil = windowStart
                _hasMore.value = merged > 0
                _loadingMore.value = false
            }
        } else {
            // Empty day — skip it and try the previous day automatically.
            emptyDaySkips++
            pagedUntil = windowStart
            val tooFarBack = (System.currentTimeMillis() / 1000 - windowStart) > 90L * 86_400L
            if (emptyDaySkips >= 3 || tooFarBack) {
                _hasMore.value = false
                _loadingMore.value = false
            } else {
                _loadingMore.value = false
                loadMore()
            }
        }
    }

    // ── Merge (all called under lock) ─────────────────────────────────────────

    private fun mergeReactionLocked(event: Event, refId: String): FlatNotifItem {
        val key = "reactions:$refId"
        val existing = groupMap[key] as? NotifGroup.ReactionGroup
        val reactions = (existing?.reactions ?: emptyMap()).toMutableMap()
        val timestamps = (existing?.reactionTimestamps ?: emptyMap()).toMutableMap()
        val emoji = event.content.trim().ifEmpty { "❤️" }
        val current = reactions[emoji].orEmpty()
        if (event.pubkey !in current) {
            reactions[emoji] = current + event.pubkey
            timestamps[event.pubkey] = event.createdAt
        }
        groupMap[key] = NotifGroup.ReactionGroup(
            groupId = key,
            referencedEventId = refId,
            reactions = reactions,
            reactionTimestamps = timestamps,
            repostPubkeys = existing?.repostPubkeys ?: emptyList(),
            repostTimestamps = existing?.repostTimestamps ?: emptyMap(),
            latestTimestamp = maxOf(existing?.latestTimestamp ?: 0L, event.createdAt),
        )
        val item = FlatNotifItem(
            id = "reaction:$refId:${event.pubkey}:${emoji.hashCode()}",
            type = NotifType.REACTION,
            actorPubkey = event.pubkey,
            timestamp = event.createdAt,
            referencedEventId = refId,
            emoji = emoji,
        )
        addFlatLocked(item)
        return item
    }

    private fun mergeRepostLocked(event: Event, refId: String): FlatNotifItem {
        val key = "reactions:$refId"
        val existing = groupMap[key] as? NotifGroup.ReactionGroup
        val reposts = (existing?.repostPubkeys ?: emptyList()).toMutableList()
        val repostTimestamps = (existing?.repostTimestamps ?: emptyMap()).toMutableMap()
        if (event.pubkey !in reposts) {
            reposts.add(event.pubkey)
            repostTimestamps[event.pubkey] = event.createdAt
        }
        groupMap[key] = NotifGroup.ReactionGroup(
            groupId = key,
            referencedEventId = refId,
            reactions = existing?.reactions ?: emptyMap(),
            reactionTimestamps = existing?.reactionTimestamps ?: emptyMap(),
            repostPubkeys = reposts,
            repostTimestamps = repostTimestamps,
            latestTimestamp = maxOf(existing?.latestTimestamp ?: 0L, event.createdAt),
        )
        val item = FlatNotifItem(
            id = "repost:$refId:${event.pubkey}",
            type = NotifType.REPOST,
            actorPubkey = event.pubkey,
            timestamp = event.createdAt,
            referencedEventId = refId,
        )
        addFlatLocked(item)
        return item
    }

    private fun mergeReplyLocked(event: Event, replyTarget: String): FlatNotifItem {
        val key = "reply:${event.id}"
        groupMap[key] = NotifGroup.ReplyGroup(
            groupId = key,
            senderPubkey = event.pubkey,
            replyEventId = event.id,
            referencedEventId = replyTarget,
            latestTimestamp = event.createdAt,
        )
        val item = FlatNotifItem(
            id = key,
            type = NotifType.REPLY,
            actorPubkey = event.pubkey,
            timestamp = event.createdAt,
            referencedEventId = replyTarget,
            replyEventId = event.id,
            note = event,
        )
        addFlatLocked(item)
        return item
    }

    private fun mergeQuoteLocked(event: Event, quotedId: String): FlatNotifItem {
        val key = "quote:${event.id}"
        groupMap[key] = NotifGroup.QuoteGroup(
            groupId = key,
            senderPubkey = event.pubkey,
            quoteEventId = event.id,
            referencedEventId = quotedId,
            latestTimestamp = event.createdAt,
        )
        val item = FlatNotifItem(
            id = key,
            type = NotifType.QUOTE,
            actorPubkey = event.pubkey,
            timestamp = event.createdAt,
            referencedEventId = quotedId,
            quoteEventId = event.id,
            note = event,
        )
        addFlatLocked(item)
        return item
    }

    private fun mergeMentionLocked(event: Event): FlatNotifItem {
        val key = "mention:${event.id}"
        groupMap[key] = NotifGroup.MentionGroup(
            groupId = key,
            senderPubkey = event.pubkey,
            eventId = event.id,
            latestTimestamp = event.createdAt,
        )
        val item = FlatNotifItem(
            id = key,
            type = NotifType.MENTION,
            actorPubkey = event.pubkey,
            timestamp = event.createdAt,
            referencedEventId = event.id,
            note = event,
        )
        addFlatLocked(item)
        return item
    }


    private fun mergeVoteLocked(event: Event, pollId: String): FlatNotifItem {
        val key = "vote:${event.id}"
        groupMap[key] = NotifGroup.VoteGroup(
            groupId = key,
            senderPubkey = event.pubkey,
            voteEventId = event.id,
            referencedEventId = pollId,
            latestTimestamp = event.createdAt,
        )
        val item = FlatNotifItem(
            id = key,
            type = NotifType.VOTE,
            actorPubkey = event.pubkey,
            timestamp = event.createdAt,
            referencedEventId = pollId,
            voteOptionIds = Nip88.getResponseOptionIds(event),
        )
        addFlatLocked(item)
        return item
    }

    /** Insert or refresh a flat row (refresh keeps follows' timestamps fresh). */
    private fun addFlatLocked(item: FlatNotifItem) {
        val idx = flatItems.indexOfFirst { it.id == item.id }
        if (idx >= 0) flatItems[idx] = item else flatItems.add(item)
        flatItemIds.add(item.id)
        if (flatItemIds.size > 3000) {
            flatItemIds.clear()
            flatItemIds.addAll(flatItems.map { it.id })
        }
    }

    // ── Rebuild ───────────────────────────────────────────────────────────────

    private fun rebuild() {
        synchronized(lock) {
            val cutoff = now() - 86_400L

            _groups.value = groupMap.values
                .sortedByDescending { it.latestTimestamp }
                .take(200)

            _flat.value = flatItems
                .sortedByDescending { it.timestamp }
                .take(500)

            var replies = 0
            var reactions = 0
            var reposts = 0
            var mentions = 0
            var quotes = 0
            var votes = 0
            for (group in groupMap.values) {
                if (group.latestTimestamp < cutoff) continue
                when (group) {
                    is NotifGroup.ReplyGroup -> replies++
                    is NotifGroup.MentionGroup -> mentions++
                    is NotifGroup.QuoteGroup -> quotes++
                    is NotifGroup.VoteGroup -> votes++
                    is NotifGroup.ReactionGroup -> {
                        reactions += group.reactions.values.sumOf { pks ->
                            pks.count { (group.reactionTimestamps[it] ?: 0L) >= cutoff }
                        }
                        reposts += group.repostPubkeys.count {
                            (group.repostTimestamps[it] ?: 0L) >= cutoff
                        }
                    }
                    else -> Unit
                }
            }
            _summary.value = NotifSummary(
                replyCount = replies,
                reactionCount = reactions,
                repostCount = reposts,
                mentionCount = mentions,
                quoteCount = quotes,
                voteCount = votes,
            )
            _isLoading.value = false
        }
        updateUnread()
        val actors = _flat.value.map { it.actorPubkey }.distinct()
        fetchProfiles(actors)
        ensureReferencedNotes()
    }

    /** Rebuilds the group map from the flat list (used after cache restore). */
    private fun rebuildGroupsFromFlatLocked() {
        groupMap.clear()
        val reactionRefs = flatItems
            .filter { it.type == NotifType.REACTION || it.type == NotifType.REPOST }
            .filter { !it.referencedEventId.isNullOrBlank() }
            .groupBy { it.referencedEventId!! }
        for ((refId, items) in reactionRefs) {
            val reactions = mutableMapOf<String, MutableList<String>>()
            val rts = mutableMapOf<String, Long>()
            val reposts = mutableListOf<String>()
            val repTs = mutableMapOf<String, Long>()
            var latest = 0L
            for (it in items) {
                latest = maxOf(latest, it.timestamp)
                if (it.type == NotifType.REACTION) {
                    val emoji = it.emoji ?: "❤️"
                    reactions.getOrPut(emoji) { mutableListOf() }.add(it.actorPubkey)
                    rts[it.actorPubkey] = it.timestamp
                } else {
                    if (it.actorPubkey !in reposts) {
                        reposts.add(it.actorPubkey)
                        repTs[it.actorPubkey] = it.timestamp
                    }
                }
            }
            groupMap["reactions:$refId"] = NotifGroup.ReactionGroup(
                groupId = "reactions:$refId",
                referencedEventId = refId,
                reactions = reactions,
                reactionTimestamps = rts,
                repostPubkeys = reposts,
                repostTimestamps = repTs,
                latestTimestamp = latest,
            )
        }
        for (it in flatItems) {
            when (it.type) {
                NotifType.REPLY -> groupMap[it.id] = NotifGroup.ReplyGroup(
                    it.id, it.actorPubkey, it.replyEventId ?: it.id,
                    it.referencedEventId ?: "", it.timestamp,
                )
                NotifType.QUOTE -> groupMap[it.id] = NotifGroup.QuoteGroup(
                    it.id, it.actorPubkey, it.quoteEventId ?: it.id,
                    it.referencedEventId ?: "", it.timestamp,
                )
                NotifType.MENTION -> groupMap[it.id] = NotifGroup.MentionGroup(
                    it.id, it.actorPubkey, it.referencedEventId ?: it.id, it.timestamp,
                )
                NotifType.VOTE -> groupMap[it.id] = NotifGroup.VoteGroup(
                    it.id, it.actorPubkey, it.id, it.referencedEventId ?: "", it.timestamp,
                )
                else -> Unit
            }
        }
    }

    private fun updateUnread() {
        _hasUnread.value = latestNotifTs > lastReadTs
    }

    // ── Own-event tracking + e-tag subscription ───────────────────────────────

    private fun rememberOwnEvent(event: Event) {
        synchronized(lock) {
            myOwnEventIds[event.id] = event.createdAt
            pruneOwnEventIdsLocked()
        }
        ownRefResubJob?.cancel()
        ownRefResubJob = scope.launch {
            delay(1_500)
            resubscribeOwnRefs()
        }
    }

    private fun pruneOwnEventIdsLocked() {
        while (myOwnEventIds.size > 600) {
            val oldest = myOwnEventIds.keys.firstOrNull() ?: break
            myOwnEventIds.remove(oldest)
        }
    }

    private fun resubscribeOwnRefs() {
        val ids = synchronized(lock) { myOwnEventIds.keys.toList().takeLast(300) }
        ownRefSubId?.let { pool.unsubscribe(it) }
        ownRefSubId = null
        if (ids.isEmpty()) return
        val since = now() - CACHE_WINDOW_SECONDS
        ownRefSubId = pool.subscribe(
            listOf(
                Filter(
                    eTags = ids,
                    kinds = listOf(EventKind.TEXT_NOTE, EventKind.REACTION, EventKind.REPOST),
                    since = since,
                    limit = 100,
                )
            ),
            label = "notif-own-refs",
        )
    }

    // ── Referenced notes + profiles ───────────────────────────────────────────

    private fun ensureReferencedNotes() {
        val known = _referencedNotes.value
        val ids = _flat.value.mapNotNull { it.referencedEventId }
            .filter { it !in known }
            .distinct()
        if (ids.isEmpty()) return
        scope.launch {
            val cached = eventRepository.getByIds(ids).associateBy { it.id }
            if (cached.isNotEmpty()) {
                _referencedNotes.update { it + cached }
                fetchProfiles(cached.values.map { e -> e.pubkey })
            }
            val missing = ids.filter { it !in cached }
            if (missing.isEmpty()) return@launch
            val subId = pool.subscribe(
                listOf(Filter(ids = missing, kinds = listOf(EventKind.TEXT_NOTE))),
                label = "notif-ref",
            )
            refSubIds.add(subId)
            refSubToIds[subId] = missing
            missing.forEach { refFetchAttempts[it] = (refFetchAttempts[it] ?: 0) + 1 }
        }
    }

    private fun fetchProfiles(pubkeys: List<String>) {
        val unknown = pubkeys.distinct()
            .filter { profileRepository.profiles.value[it] == null }
        if (unknown.isEmpty()) return
        val subId = pool.subscribe(
            listOf(Filter(authors = unknown, kinds = listOf(EventKind.METADATA), limit = unknown.size)),
            label = "notif-profiles",
        )
        profileSubIds.add(subId)
    }

    private fun now(): Long = System.currentTimeMillis() / 1000
}
