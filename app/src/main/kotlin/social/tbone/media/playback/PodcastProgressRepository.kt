package social.tbone.media.playback

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import social.tbone.media.audio.AudioCachePolicy
import social.tbone.notes.NoteWriteCoordinator
import social.tbone.notes.NotesCrypto
import javax.inject.Inject
import javax.inject.Singleton

data class PodcastProgress(val position: Long = 0, val duration: Long = 0, val speed: Float = 1f, val played: Boolean = false)
fun podcastResumePosition(progress: PodcastProgress): Long =
    if (progress.played || progress.position < 5_000 || (progress.duration > 0 && progress.position >= progress.duration - 5_000)) 0L
    else progress.position.coerceAtMost(if (progress.duration > 0) progress.duration else Long.MAX_VALUE)
fun podcastSpeed(speed: Float): Float = if (speed.isFinite()) speed.coerceIn(0.5f, 3f) else 1f

/** Account-scoped listening history: encrypted like Notes, bounded, and covered by the Notes wipe gate. */
@Singleton
class PodcastProgressRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val crypto: NotesCrypto,
    private val writes: NoteWriteCoordinator,
) {
    private val prefs = context.getSharedPreferences("tbone_podcast_progress", Context.MODE_PRIVATE)
    private val clock = java.util.concurrent.atomic.AtomicLong(0)
    fun timestamp(): Long = clock.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision
    private fun key(owner: String, mediaKey: String) = AudioCachePolicy.key("$owner:$mediaKey")
    private fun decode(raw: String?): JSONObject? = runCatching {
        val parts = raw?.split(':', limit = 2) ?: return null
        JSONObject(crypto.decrypt(Base64.decode(parts[1], Base64.NO_WRAP), Base64.decode(parts[0], Base64.NO_WRAP)))
    }.getOrNull()
    suspend fun read(owner: String, mediaKey: String): PodcastProgress = withContext(Dispatchers.IO) {
        if (!writes.available.value) return@withContext PodcastProgress()
        val row = decode(prefs.getString(key(owner, mediaKey), null)) ?: return@withContext PodcastProgress()
        PodcastProgress(row.optLong("position").coerceAtLeast(0), row.optLong("duration").coerceAtLeast(0),
            podcastSpeed(row.optDouble("speed", 1.0).toFloat()), row.optBoolean("played"))
    }
    suspend fun save(owner: String, mediaKey: String, progress: PodcastProgress, played: Boolean? = null, recordedAt: Long = timestamp()) {
        val epoch = writes.currentWipeEpoch()
        writes.submitAtEpoch(epoch) {
            val id = key(owner, mediaKey)
            val existing = decode(prefs.getString(id, null))
            if ((existing?.optLong("updated") ?: 0) > recordedAt) return@submitAtEpoch
            val row = JSONObject().put("position", progress.position.coerceAtLeast(0)).put("duration", progress.duration.coerceAtLeast(0))
                .put("speed", podcastSpeed(progress.speed)).put("played", played ?: existing?.optBoolean("played") ?: false)
                .put("updated", recordedAt)
            val (bytes, iv) = crypto.encrypt(row.toString())
            val edit = prefs.edit().putString(id, Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP))
            val old = prefs.all.entries.filter { it.key != id }
            if (old.size >= 500) old.sortedBy { decode(it.value as? String)?.optLong("updated") ?: 0 }
                .take(old.size - 499).forEach { edit.remove(it.key) }
            check(edit.commit()) { "Could not save podcast progress" }
            _revision.value++
        }.await()
    }
}
