package social.tbone.util

import android.content.Context
import java.io.File

/**
 * Option A hygiene: removes leaked cacheDir transients that the app wrote
 * but never cleaned (staging copies, share cards, voice drafts, etc.).
 *
 * - Safe to run on every cold start (I/O-light, only deletes files older than
 *   a grace window so in-flight uploads are not touched).
 * - Also removes stale `*.part` files from the OTA update dir.
 * - Coil's image_cache is managed by Coil's own LRU — we don't delete it
 *   here (the lightweight toggle caps it instead).
 */
object CacheCleaner {

    // Files newer than this are left alone (upload may still be running).
    private const val GRACE_MS = 6 * 60 * 60 * 1000L // 6h
    private const val SHARE_CARDS_TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7d
    private const val UPDATES_PART_TTL_MS = 24 * 60 * 60 * 1000L // 24h

    fun cleanup(context: Context) {
        val cacheDir = context.cacheDir ?: return
        val now = System.currentTimeMillis()

        // 1) Share cards (PNG + MP4) — directory pruned by age
        cleanDir(File(cacheDir, "share_cards"), now, SHARE_CARDS_TTL_MS)

        // 2) Webxdc share sheet cache
        cleanDir(File(cacheDir, "webxdc_share"), now, SHARE_CARDS_TTL_MS)

        // 3) Flat cacheDir files by known prefixes
        val prefixes = listOf(
            "tbone_media_",
            "tbone_video_",
            "tbone_video_comp_",
            "tbone_audio_",
            "tbone_audio_comp_",
            "note_voice_",
            "play_",
            "share-video-",
            "tbone_",
        )
        cacheDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val name = f.name
            // Keep Coil's own folder alone
            if (name == "image_cache") return@forEach
            val matchesPrefix = prefixes.any { name.startsWith(it) }
            val isTmp = name.endsWith(".tbbackup.tmp") || name.endsWith(".tmp")
            if ((matchesPrefix || isTmp) && isOlderThan(f, now, GRACE_MS)) {
                f.delete()
            }
        }

        // 4) Updates: remove stale .part files, keep latest.apk
        val updatesDir = File(cacheDir, "updates")
        if (updatesDir.isDirectory) {
            updatesDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".part") && isOlderThan(f, now, UPDATES_PART_TTL_MS)) {
                    f.delete()
                }
                // Also clean up any orphaned temp that is newer than grace but extremely old part
                if (f.isFile && f.name.endsWith(".part") && f.length() == 0L) {
                    f.delete()
                }
            }
        }

        // 5) Root-level orphaned share_cards remnants (some MP4s are written directly to cacheDir)
        // Already covered by prefix scan, but also clean any leftover .part in cacheDir root
        cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".part") && isOlderThan(f, now, UPDATES_PART_TTL_MS)) {
                f.delete()
            }
        }
    }

    private fun cleanDir(dir: File, now: Long, ttlMs: Long) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile && isOlderThan(f, now, ttlMs)) f.delete()
        }
        // Remove empty dir to keep cacheDir tidy (optional)
        if (dir.listFiles()?.isEmpty() == true) {
            // Keep the dir itself — Coil/FileProvider may expect it to exist
        }
    }

    private fun isOlderThan(file: File, now: Long, ttlMs: Long): Boolean {
        val last = try { file.lastModified() } catch (_: Exception) { 0L }
        // If lastModified is 0 (some filesystems), treat as old and delete
        if (last == 0L) return true
        return now - last > ttlMs
    }
}
