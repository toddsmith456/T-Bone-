package social.tbone.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Read at most [limit] bytes, rejecting larger input rather than allocating without bound. */
internal fun InputStream.readBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        require(count <= limit - total) { "file is too large" }
        out.write(buffer, 0, count)
        total += count
    }
    return out.toByteArray()
}
