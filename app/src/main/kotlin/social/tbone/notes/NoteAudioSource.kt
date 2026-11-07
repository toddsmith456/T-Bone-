package social.tbone.notes

import android.media.MediaDataSource

/** MediaPlayer reads decrypted attachment bytes without creating a cache file. */
internal class NoteAudioSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun getSize(): Long = bytes.size.toLong()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= bytes.size) return -1
        val count = minOf(size, bytes.size - position.toInt())
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }
    override fun close() = Unit
}
