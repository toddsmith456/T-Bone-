package social.tbone.media.audio

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import social.tbone.di.ImageClientProvider
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Bounded range-only metadata reads, using the same Tor-aware client as media playback.
 * Servers without byte ranges fall back to imeta/embedded playback metadata; never
 * download an entire lossless album just to display one row.
 */
object AudioMetadataReader {
    data class Metadata(val title: String?, val artist: String?, val album: String?, val artwork: ByteArray?)
    private val permits = Semaphore(2)

    suspend fun read(url: String): Metadata? = permits.withPermit {
        withContext(Dispatchers.IO) {
            val source = RangeSource(url)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source)
                Metadata(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    retriever.embeddedPicture?.takeIf { it.size <= 256 * 1024 },
                )
            } catch (_: Exception) { null }
            finally { runCatching { retriever.release() }; source.close() }
        }
    }

    private class RangeSource(private val url: String) : MediaDataSource() {
        private val client = ImageClientProvider.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        private var length = -1L
        private var transferred = 0
        private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        private val pages = LinkedHashMap<Long, ByteArray>()
        private var closed = false

        @Synchronized
        private fun page(index: Long): ByteArray {
            pages[index]?.let { return it }
            if (closed || transferred >= 2 * 1024 * 1024 || System.nanoTime() > deadline) throw IOException("Metadata read limit")
            val start = index * 65536L
            val request = Request.Builder().url(url).header("Range", "bytes=$start-${start + 65535}")
                .header("Accept-Encoding", "identity").build()
            val bytes = client.newCall(request).execute().use { response ->
                if (response.code != 206) throw IOException("Metadata requires byte-range support")
                length = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: throw IOException("Missing media length")
                val input = response.body?.byteStream() ?: throw IOException("Empty media body")
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (output.size() <= 65536) {
                    val n = input.read(chunk, 0, minOf(chunk.size, 65537 - output.size()))
                    if (n < 0) break
                    output.write(chunk, 0, n)
                }
                val data = output.toByteArray()
                if (data.size > 65536) throw IOException("Invalid byte range")
                data
            }
            transferred += bytes.size
            if (pages.size >= 8) pages.remove(pages.keys.first())
            pages[index] = bytes
            return bytes
        }

        override fun getSize(): Long { if (length < 0) page(0); return length }
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || size < 0) return -1
            if (size == 0) return 0
            if (position >= getSize()) return -1
            val bytes = page(position / 65536L)
            val start = (position % 65536L).toInt()
            val count = minOf(size, bytes.size - start)
            if (count <= 0) return -1
            bytes.copyInto(buffer, offset, start, start + count)
            return count
        }
        @Synchronized override fun close() { closed = true; pages.clear() }
    }
}
