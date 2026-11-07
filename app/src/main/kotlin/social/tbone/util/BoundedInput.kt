package social.tbone.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Read at most [limit] bytes, rejecting larger input rather than allocating without bound. */
internal fun InputStream.readBounded(limit: Int): ByteArray {
    require(limit >= 0) { "negative input limit" }
    val out = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    var emptyReads = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) {
            if (++emptyReads > 16) throw java.io.IOException("input stream made no progress")
            continue
        }
        emptyReads = 0
        require(count <= limit - total) { "file is too large" }
        out.write(buffer, 0, count)
        total += count
    }
    return out.toByteArray()
}
