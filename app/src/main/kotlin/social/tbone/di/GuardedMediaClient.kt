package social.tbone.di

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean

/** Retain each call until its streaming body is consumed or closed. */
internal fun guardedMediaClient(client: OkHttpClient, useTor: Boolean, guard: TorCallGuard): OkHttpClient {
    val epoch = guard.snapshot(useTor)
    return client.newBuilder().addInterceptor(Interceptor { chain ->
        val call = chain.call()
        guard.registerAtEpoch(call, useTor, epoch)
        val finished = AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) guard.unregister(call) }
        try {
            val response = chain.proceed(chain.request())
            val body = response.body
            if (body == null) {
                finish()
                response
            } else {
                val wrapped = object : ResponseBody() {
                    private val stream: BufferedSource = object : ForwardingSource(body.source()) {
                        override fun read(sink: okio.Buffer, byteCount: Long): Long {
                            return try {
                                super.read(sink, byteCount).also { if (it == -1L) finish() }
                            } catch (error: Exception) {
                                finish()
                                throw error
                            }
                        }
                        override fun close() {
                            try { super.close() } finally { finish() }
                        }
                    }.buffer()
                    override fun contentType(): MediaType? = body.contentType()
                    override fun contentLength(): Long = body.contentLength()
                    override fun source(): BufferedSource = stream
                }
                response.newBuilder().body(wrapped).build()
            }
        } catch (error: Throwable) {
            finish()
            throw error
        }
    }).build()
}
