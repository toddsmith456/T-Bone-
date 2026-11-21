package social.tbone.media.audio

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import social.tbone.cache.CacheDirectories
import social.tbone.settings.AppSettings
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Rebuildable public metadata only; never stores playlist membership, keys or audio bodies. */
@Singleton
class AudioMetadataCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {
    private val diskLock = Any()
    private val networkLocks = Array(32) { Mutex() }
    private val _generation = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val generation: kotlinx.coroutines.flow.StateFlow<Long> = _generation
    private var epoch = 0L
    private var disabled = false
    private val dir get() = File(context.cacheDir, CacheDirectories.AUDIO_DETAILS_CACHE)
    private data class Hit(val value: AudioMetadataReader.Metadata?, val fresh: Boolean)

    private fun lookup(url: String): Hit? = synchronized(diskLock) {
        if (disabled) return@synchronized null
        val file = File(dir, AudioCachePolicy.key(url))
        runCatching {
            if (!file.isFile || file.length() > AudioCachePolicy.MAX_RECORD_BYTES) return@synchronized null
            val json = JSONObject(file.readText())
            if (json.optInt("version") != 1) { file.delete(); return@synchronized null }
            val success = json.getBoolean("success")
            fun text(key: String) = json.optString(key).takeIf { it.isNotBlank() }
            val value = if (json.optBoolean("hasValue", success)) AudioMetadataReader.Metadata(text("title"), text("artist"), text("album"),
                text("art")?.let { Base64.decode(it, Base64.NO_WRAP) }) else null
            file.setLastModified(System.currentTimeMillis())
            Hit(value, AudioCachePolicy.fresh(json.getLong("saved"), System.currentTimeMillis(), success))
        }.getOrElse { file.delete(); null }
    }

    suspend fun read(url: String, allowNetwork: Boolean = true): AudioMetadataReader.Metadata? = withContext(Dispatchers.IO) {
        val generation = synchronized(diskLock) { if (disabled) return@withContext null else epoch }
        val cached = lookup(url)
        if (cached?.fresh == true || !allowNetwork) return@withContext cached?.value
        networkLocks[(url.hashCode() and Int.MAX_VALUE) % networkLocks.size].withLock {
            val again = lookup(url)
            if (again?.fresh == true) return@withLock again.value
            if (synchronized(diskLock) { disabled || generation != epoch }) return@withLock null
            val result = try { AudioMetadataReader.read(url) } catch (cancelled: CancellationException) { throw cancelled }
            synchronized(diskLock) {
                if (generation == epoch && !disabled) runCatching {
                    dir.mkdirs()
                    // Keep a stale success on a transient failure, with a short retry backoff.
                    val retained = result ?: cached?.value
                    val json = JSONObject().put("version", 1).put("saved", System.currentTimeMillis())
                        .put("success", result != null)
                    retained?.let {
                        json.put("title", it.title).put("artist", it.artist).put("album", it.album)
                        it.artwork?.let { art -> json.put("art", Base64.encodeToString(art, Base64.NO_WRAP)) }
                    }
                    // A stale success remains usable offline, but is retried after five minutes.
                    json.put("hasValue", retained != null)
                    val target = File(dir, AudioCachePolicy.key(url))
                    val tmp = File(dir, target.name + ".tmp")
                    tmp.writeText(json.toString())
                    if (tmp.length() <= AudioCachePolicy.MAX_RECORD_BYTES) {
                        if (!tmp.renameTo(target)) tmp.delete()
                    } else tmp.delete()
                    trimLocked()
                }
            }
            if (synchronized(diskLock) { disabled || generation != epoch }) null else result ?: cached?.value
        }
    }

    private fun trimLocked() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        val budget = AudioCachePolicy.diskBudget(settings.cachePreset.value)
        for (file in files) if (total > budget) { val n = file.length(); if (file.delete()) total -= n }
    }

    suspend fun trim() = withContext(Dispatchers.IO) { synchronized(diskLock) { trimLocked() } }
    fun clear(forWipe: Boolean = false) = synchronized(diskLock) {
        epoch++
        _generation.value = epoch
        if (forWipe) disabled = true
        check(!dir.exists() || dir.deleteRecursively()) { "Could not clear audio details cache" }
    }
}
