package social.tbone.profile

import android.util.LruCache
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.Tunables
import social.tbone.db.ProfileDao
import social.tbone.db.ProfileEntity
import social.tbone.db.toProfileContent
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.ProfileContent
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(private val dao: ProfileDao) {

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
                _profiles.update { current ->
                    if (current.size >= Tunables.PROFILE_CACHE_SIZE && event.pubkey !in current) {
                        cache.snapshot()
                    } else {
                        current + (event.pubkey to content)
                    }
                }
                true
            }
        }
        if (!accepted) return

        // DAO writes are independently ordered by createdAt as well, because IO
        // scheduling need not preserve the order of events accepted above.
        scope.launch {
            runCatching {
                dao.upsertIfNewer(ProfileEntity(event.pubkey, event.content, event.createdAt))
            }.onFailure {
                Timber.w(it, "Failed saving profile %s", event.pubkey.take(8))
            }
        }
    }

    /** Trims ancient cached profiles on startup so long-lived databases stay lean. */
    suspend fun pruneOld(maxKeep: Int = Tunables.MAX_CACHED_PROFILES): Int =
        dao.keepLatest(maxKeep)

    fun getProfile(pubkey: String): ProfileContent? = synchronized(profileLock) { cache.get(pubkey) }
}
