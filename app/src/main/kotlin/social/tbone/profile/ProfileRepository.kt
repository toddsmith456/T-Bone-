package social.tbone.profile

import android.util.LruCache
import social.tbone.Tunables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.db.ProfileDao
import social.tbone.db.ProfileEntity
import social.tbone.db.toProfileContent
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.ProfileContent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(private val dao: ProfileDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // LruCache is the authoritative in-memory store; capped at 1000 entries.
    // The StateFlow emits snapshots of it so Compose can observe changes.
    private val cache = LruCache<String, ProfileContent>(Tunables.PROFILE_CACHE_SIZE)

    // Tracks the createdAt of the newest event processed per pubkey (in-memory guard).
    // Capped to match the profile cache — old entries evicted together.
    private val latestTimestamp = LruCache<String, Long>(Tunables.PROFILE_CACHE_SIZE)

    private val _profiles = MutableStateFlow<Map<String, ProfileContent>>(emptyMap())
    val profiles: StateFlow<Map<String, ProfileContent>> = _profiles.asStateFlow()

    init {
        scope.launch {
            val cached = dao.getAll()
            if (cached.isEmpty()) return@launch
            cached.forEach { entity ->
                val content = entity.toProfileContent() ?: return@forEach
                latestTimestamp.put(entity.pubkey, entity.createdAt)
                cache.put(entity.pubkey, content)
            }
            // Bulk load on init: snapshot once is acceptable; per-event updates are targeted below.
            _profiles.update { it + cache.snapshot() }
        }
    }

    fun processEvent(event: Event) {
        if (event.kind != EventKind.METADATA) return
        val content = ProfileContent.parse(event.content) ?: return

        synchronized(latestTimestamp) {
            val existing = latestTimestamp.get(event.pubkey) ?: -1L
            if (event.createdAt <= existing) return
            latestTimestamp.put(event.pubkey, event.createdAt)
        }

        cache.put(event.pubkey, content)
        // Targeted update: add/replace the single changed entry rather than snapshotting
        // the entire LruCache (O(n)) on every kind-0 event.
        _profiles.update { it + (event.pubkey to content) }

        scope.launch {
            dao.upsert(ProfileEntity(event.pubkey, event.content, event.createdAt))
        }
    }

    fun getProfile(pubkey: String): ProfileContent? = cache.get(pubkey)
}
