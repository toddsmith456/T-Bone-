package social.tbone.lists

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import social.tbone.util.readBounded
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read an HTTP sample off the main thread and cancel its socket with its caller. */
internal suspend fun Call.awaitBoundedFollowerBody(maxBytes: Int): String? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val body = response.use {
                    if (!it.isSuccessful) null else it.body?.byteStream()?.use { stream ->
                        stream.readBounded(maxBytes).toString(Charsets.UTF_8)
                    }
                }
                if (continuation.isActive) continuation.resume(body)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
