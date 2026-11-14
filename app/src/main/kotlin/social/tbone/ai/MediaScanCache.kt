package social.tbone.ai

import social.tbone.db.MediaScanCacheDao
import social.tbone.db.MediaScanCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-tier on-device cache for offline AI scan scores:
 *
 * - **L1 (RAM LRU, 512 entries):** Instant hit by HMAC key during scroll flings.
 * - **L1b (RAM-only perceptual dHash ring, 256 entries):** Skips redundant inference
 *   when the same image is reposted/re-encoded on a different Blossom host (Hamming <= 4).
 * - **L2 (Room `media_scan_cache`):** HMAC-SHA256 keyed persistent scores with 7-day
 *   TTL and 10,000-row cap, wiped completely on duress wipe.
 */
@Singleton
class MediaScanCache @Inject constructor(
    private val dao: MediaScanCacheDao,
    private val hasher: ScanKeyHasher,
) {
    companion object {
        private const val L1_MAX_ENTRIES = 512
        private const val DHASH_RING_SIZE = 256
        private const val DHASH_MAX_HAMMING = 4
        const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        const val CACHE_MAX_ROWS = 10_000
    }

    data class CacheHit(
        val scores: RawNsfwScores,
        val userOverrideAllow: Boolean,
    )

    private val lock = Any()

    // Synchronized LRU map in pure Kotlin so unit tests run cleanly without android.util.LruCache stubs.
    private val l1 = object : LinkedHashMap<String, CacheHit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheHit>?): Boolean =
            size > L1_MAX_ENTRIES
    }

    // RAM-only perceptual hash ring buffer (never persisted to disk).
    private val dHashRing = ArrayDeque<Pair<Long, RawNsfwScores>>(DHASH_RING_SIZE)

    /**
     * Build the stable cache identifier for an image:
     * prefers NIP-92 `imeta` SHA-256 (`x`) when present so identical uploads across
     * multiple Blossom mirrors share a single scan entry; falls back to canonical URL.
     */
    fun canonicalKey(url: String, imetaSha256: String? = null): String {
        val cleanSha = imetaSha256?.trim()?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        return if (cleanSha != null) {
            "sha256:$cleanSha"
        } else {
            "url:${url.trim()}"
        }
    }

    fun keyHmac(url: String, imetaSha256: String? = null): String =
        hasher.hmacHex(canonicalKey(url, imetaSha256))

    suspend fun get(
        url: String,
        imetaSha256: String? = null,
        requiredModelVersion: Int,
        requireStage2: Boolean = false,
    ): CacheHit? {
        val hmac = keyHmac(url, imetaSha256)
        val memHit = synchronized(lock) { l1[hmac] }
        if (memHit != null && isSatisfactory(memHit.scores, requiredModelVersion, requireStage2)) {
            return memHit
        }

        val row = runCatching { dao.get(hmac) }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        if (now - row.scannedAt > CACHE_TTL_MS || row.modelVersion != requiredModelVersion) {
            return null
        }
        val scores = RawNsfwScores(
            explicitScore = row.explicitScore,
            suggestiveScore = row.suggestiveScore,
            stageReached = row.stageReached,
            modelVersion = row.modelVersion,
        )
        if (!isSatisfactory(scores, requiredModelVersion, requireStage2)) {
            return null
        }
        val hit = CacheHit(scores = scores, userOverrideAllow = row.userOverrideAllow)
        synchronized(lock) { l1[hmac] = hit }
        return hit
    }

    fun lookupByPerceptualHash(
        dHash: Long,
        requiredModelVersion: Int,
        requireStage2: Boolean = false,
    ): RawNsfwScores? = synchronized(lock) {
        var best: RawNsfwScores? = null
        var bestDist = DHASH_MAX_HAMMING + 1
        for ((hash, scores) in dHashRing) {
            if (!isSatisfactory(scores, requiredModelVersion, requireStage2)) continue
            val dist = PerceptualHash.hammingDistance(hash, dHash)
            if (dist <= DHASH_MAX_HAMMING && dist < bestDist) {
                bestDist = dist
                best = scores
                if (dist == 0) break
            }
        }
        best
    }

    suspend fun put(
        url: String,
        imetaSha256: String? = null,
        scores: RawNsfwScores,
        dHash: Long? = null,
        userOverrideAllow: Boolean = false,
    ) {
        val hmac = keyHmac(url, imetaSha256)
        val hit = CacheHit(scores = scores, userOverrideAllow = userOverrideAllow)
        synchronized(lock) {
            l1[hmac] = hit
            if (dHash != null) {
                if (dHashRing.size >= DHASH_RING_SIZE) {
                    dHashRing.removeFirst()
                }
                dHashRing.addLast(dHash to scores)
            }
        }
        runCatching {
            dao.upsert(
                MediaScanCacheEntity(
                    keyHmac = hmac,
                    explicitScore = scores.explicitScore,
                    suggestiveScore = scores.suggestiveScore,
                    stageReached = scores.stageReached,
                    modelVersion = scores.modelVersion,
                    scannedAt = System.currentTimeMillis(),
                    userOverrideAllow = userOverrideAllow,
                ),
            )
        }
    }

    suspend fun prune(currentModelVersion: Int = AiModelCatalog.CACHE_MODEL_VERSION) {
        runCatching {
            val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
            dao.pruneExpiredOrStale(cutoffMs = cutoff, currentModelVersion = currentModelVersion)
            dao.trimToLimit(CACHE_MAX_ROWS)
        }
    }

    suspend fun clearAll() {
        clearMemory()
        runCatching { dao.clearAll() }
    }

    fun clearMemory() {
        synchronized(lock) {
            l1.clear()
            dHashRing.clear()
        }
        hasher.clearInMemoryKey()
    }

    private fun isSatisfactory(
        scores: RawNsfwScores,
        requiredModelVersion: Int,
        requireStage2: Boolean,
    ): Boolean {
        if (scores.modelVersion != requiredModelVersion) return false
        if (requireStage2 && scores.stageReached < 2) return false
        return true
    }
}
