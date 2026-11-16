package social.tbone.reactions

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.tbone.db.EngagementCacheDao
import social.tbone.db.EngagementCacheEntity
import social.tbone.settings.AppSettings
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write-behind cache for per-note like & reply counts so Home feed cards show
 * their last-known engagement counts on frame 0 after a cold start.
 *
 * Automatically disabled on storage tiers where `preset.maxEngagementRows == 0`
 * (Ultra-Light & Compact).
 */
@Singleton
class EngagementCacheRepository @Inject constructor(
    private val dao: EngagementCacheDao,
    private val appSettings: AppSettings,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Timber.e(t, "Uncaught exception in EngagementCacheRepository")
        },
    )

    private val lock = Any()
    private val pending = LinkedHashMap<Pair<String, String>, PartialUpdate>()
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)

    private data class PartialUpdate(
        val likeCount: Int? = null,
        val replyCount: Int? = null,
        val likedByMe: Boolean? = null,
        val repliedByMe: Boolean? = null,
        val myReactionContent: String? = null,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    init {
        scope.launch {
            for (signal in flushSignal) {
                delay(FLUSH_DEBOUNCE_MS)
                flushPending()
            }
        }
    }

    fun recordReactionSnapshot(
        accountPubkey: String,
        eventId: String,
        likeCount: Int,
        likedByMe: Boolean,
        myReactionContent: String?,
    ) {
        if (accountPubkey.isBlank() || eventId.isBlank()) return
        if (appSettings.cachePreset.value.maxEngagementRows <= 0) return
        val key = eventId to accountPubkey
        synchronized(lock) {
            val prev = pending[key]
            pending[key] = PartialUpdate(
                likeCount = likeCount.coerceAtLeast(0),
                replyCount = prev?.replyCount,
                likedByMe = likedByMe,
                repliedByMe = prev?.repliedByMe,
                myReactionContent = myReactionContent ?: prev?.myReactionContent,
                updatedAt = System.currentTimeMillis(),
            )
        }
        flushSignal.trySend(Unit)
    }

    fun recordReplySnapshot(
        accountPubkey: String,
        eventId: String,
        replyCount: Int,
        repliedByMe: Boolean,
    ) {
        if (accountPubkey.isBlank() || eventId.isBlank()) return
        if (appSettings.cachePreset.value.maxEngagementRows <= 0) return
        val key = eventId to accountPubkey
        synchronized(lock) {
            val prev = pending[key]
            pending[key] = PartialUpdate(
                likeCount = prev?.likeCount,
                replyCount = replyCount.coerceAtLeast(0),
                likedByMe = prev?.likedByMe,
                repliedByMe = repliedByMe,
                myReactionContent = prev?.myReactionContent,
                updatedAt = System.currentTimeMillis(),
            )
        }
        flushSignal.trySend(Unit)
    }

    suspend fun loadForEvents(accountPubkey: String, eventIds: List<String>): List<EngagementCacheEntity> {
        if (accountPubkey.isBlank() || eventIds.isEmpty()) return emptyList()
        if (appSettings.cachePreset.value.maxEngagementRows <= 0) return emptyList()
        val unique = eventIds.distinct()
        return runCatching {
            unique.chunked(SQLITE_IN_BATCH).flatMap { chunk ->
                dao.getForEvents(accountPubkey, chunk)
            }
        }.getOrDefault(emptyList())
    }

    suspend fun prune(maxRows: Int = appSettings.cachePreset.value.maxEngagementRows) {
        runCatching {
            if (maxRows <= 0) {
                dao.clearAll()
            } else {
                dao.trimToLimit(maxRows)
            }
        }
    }

    suspend fun clearAll() {
        synchronized(lock) { pending.clear() }
        runCatching { dao.clearAll() }
    }

    private suspend fun flushPending() {
        val snapshot = synchronized(lock) {
            if (pending.isEmpty()) return
            pending.toMap().also { pending.clear() }
        }
        if (appSettings.cachePreset.value.maxEngagementRows <= 0) return
        runCatching {
            val byAccount = snapshot.entries.groupBy { it.key.second }
            val merged = mutableListOf<EngagementCacheEntity>()
            for ((account, entries) in byAccount) {
                val ids = entries.map { it.key.first }
                val existingById = ids.chunked(SQLITE_IN_BATCH)
                    .flatMap { dao.getForEvents(account, it) }
                    .associateBy { it.eventId }
                for ((key, delta) in entries) {
                    val eventId = key.first
                    val old = existingById[eventId]
                    merged += EngagementCacheEntity(
                        eventId = eventId,
                        accountPubkey = account,
                        likeCount = delta.likeCount ?: old?.likeCount ?: 0,
                        replyCount = delta.replyCount ?: old?.replyCount ?: 0,
                        likedByMe = delta.likedByMe ?: old?.likedByMe ?: false,
                        repliedByMe = delta.repliedByMe ?: old?.repliedByMe ?: false,
                        myReactionContent = delta.myReactionContent ?: old?.myReactionContent,
                        updatedAt = delta.updatedAt,
                    )
                }
            }
            if (merged.isNotEmpty()) {
                dao.upsertAll(merged)
            }
        }.onFailure { Timber.w(it, "Failed flushing engagement cache") }
    }

    companion object {
        private const val FLUSH_DEBOUNCE_MS = 500L
        private const val SQLITE_IN_BATCH = 500

        /**
         * Sentinel prefix for persisted count placeholders inside
         * `ReactionsRepository`'s reactor sets until real relay reactions arrive.
         */
        const val CACHED_SENTINEL_PREFIX = "__tbone_cached_like_"

        fun isCachedSentinel(token: String): Boolean = token.startsWith(CACHED_SENTINEL_PREFIX)

        /**
         * Builds an initial reactor set matching a cached count while preserving
         * exact `activePubkey` membership when the user liked the note.
         */
        fun buildCachedReactorSet(
            cachedCount: Int,
            likedByMe: Boolean,
            activePubkey: String,
        ): Set<String> {
            val total = cachedCount.coerceAtLeast(if (likedByMe) 1 else 0)
            if (total == 0) return emptySet()
            val out = LinkedHashSet<String>(total)
            if (likedByMe && activePubkey.isNotBlank()) {
                out += activePubkey
            }
            var idx = 0
            while (out.size < total) {
                out += "$CACHED_SENTINEL_PREFIX$idx"
                idx++
            }
            return out
        }

        /**
         * Merges a newly arrived real reactor pubkey into an existing set that may
         * still contain cold-start cached count sentinels: replaces one sentinel
         * so the visible count stays smooth instead of double-counting or dipping.
         */
        fun mergeRealReactor(existing: Set<String>?, realPubkey: String): Set<String> {
            if (existing == null || existing.isEmpty()) return setOf(realPubkey)
            if (realPubkey in existing) return existing
            val sentinel = existing.firstOrNull { isCachedSentinel(it) }
            return if (sentinel != null) {
                (existing - sentinel) + realPubkey
            } else {
                existing + realPubkey
            }
        }
    }
}
