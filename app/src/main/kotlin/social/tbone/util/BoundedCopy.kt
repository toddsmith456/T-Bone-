package social.tbone.util

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Stream at most [limit] bytes, reject stalled inputs, and observe cancellation. */
internal suspend fun InputStream.copyBounded(output: OutputStream, limit: Long, tooLarge: String): Long {
    require(limit >= 0)
    val buffer = ByteArray(8192)
    var total = 0L
    var emptyReads = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer)
        if (count < 0) return total
        if (count == 0) {
            if (++emptyReads > 16) throw IOException("input stream made no progress")
            continue
        }
        emptyReads = 0
        require(count.toLong() <= limit - total) { tooLarge }
        output.write(buffer, 0, count)
        total += count
    }
}
