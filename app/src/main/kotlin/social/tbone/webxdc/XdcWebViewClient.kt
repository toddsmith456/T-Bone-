package social.tbone.webxdc

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Serves a mini app from app-private storage under a private origin
 * (`https://<appId>.xdc.local/`) and denies every other origin.
 *
 * Webxdc's first security rule is "no internet access". Three layers enforce it:
 *
 *  1. every request whose host is not this app's origin gets a 403 and never
 *     reaches the network stack;
 *  2. HTML is served with a CSP that only allows `'self'`, `data:` and `blob:`;
 *  3. file/content access is disabled on the WebView itself (see the player).
 *
 * `webxdc.js` is not read from the archive — the messenger must provide it — so
 * this client injects the T-Bone shim and references it from every HTML page.
 */
class XdcWebViewClient(
    private val originHost: String,
    private val filesDir: File,
    private val shim: String,
    private val onExternalUrl: (Uri) -> Unit,
) : WebViewClient() {

    private val canonicalRoot: File by lazy { filesDir.canonicalFile }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return forbidden("bad request")
        val scheme = uri.scheme?.lowercase()
        // Only http(s) is ours to police. data:, blob:, about: and friends are
        // produced and consumed inside the renderer, never on the network, so
        // hand them back to WebView instead of 403-ing every inline image.
        if (scheme != "http" && scheme != "https") return null
        if (!uri.host.equals(originHost, ignoreCase = true)) {
            // No internet, ever — not even a tracking pixel.
            return forbidden("blocked external request")
        }

        val path = Uri.decode(uri.path ?: "/").trimStart('/').ifEmpty { "index.html" }
        if (path == "webxdc.js") {
            return response("application/javascript", "utf-8", shim.toByteArray(Charsets.UTF_8), html = false)
        }

        val file = resolve(path)
            ?: return notFound("unsafe path")

        if (file.isDirectory) {
            val index = File(file, "index.html")
            if (index.isFile) return serve(index)
            return notFound("directory without index.html")
        }
        if (!file.isFile) return notFound("missing file")
        return serve(file)
    }

    private fun resolve(path: String): File? = runCatching {
        val candidate = File(canonicalRoot, path).canonicalFile
        if (candidate.path == canonicalRoot.path || candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            candidate
        } else {
            null
        }
    }.getOrNull()

    private fun serve(file: File): WebResourceResponse {
        val mime = mimeType(file.name)
        val isHtml = mime == "text/html"
        if (isHtml) {
            val text = file.readText(Charsets.UTF_8)
            val injected = injectBridge(text)
            return response(mime, "utf-8", injected.toByteArray(Charsets.UTF_8), html = true)
        }
        return response(mime, if (mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript")) "utf-8" else null, file, html = false)
    }

    /** Adds the bridge script through [XdcHtml.injectBridge]. */
    internal fun injectBridge(html: String): String = XdcHtml.injectBridge(html)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return true
        if (uri.host.equals(originHost, ignoreCase = true)) return false
        if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto") {
            runCatching { onExternalUrl(uri) }
        }
        return true
    }

    private fun response(
        mime: String,
        encoding: String?,
        bytes: ByteArray,
        html: Boolean,
    ): WebResourceResponse = WebResourceResponse(
        mime,
        encoding,
        200,
        "OK",
        headers(html),
        ByteArrayInputStream(bytes),
    )

    private fun response(mime: String, encoding: String?, file: File, html: Boolean): WebResourceResponse =
        WebResourceResponse(mime, encoding, 200, "OK", headers(html), FileInputStream(file))

    private fun headers(html: Boolean): Map<String, String> = buildMap {
        put("Cache-Control", "no-store")
        if (html) {
            put("Content-Security-Policy", XdcHtml.CSP)
        }
    }

    private fun forbidden(reason: String): WebResourceResponse {
        Timber.w("webxdc blocked request: %s", reason)
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Forbidden",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream("blocked".toByteArray()),
        )
    }

    private fun notFound(reason: String): WebResourceResponse {
        Timber.d("webxdc 404: %s", reason)
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream("not found".toByteArray()),
        )
    }

    companion object {
        private val MIME = mapOf(
            "html" to "text/html", "htm" to "text/html",
            "js" to "application/javascript", "mjs" to "application/javascript",
            "css" to "text/css",
            "json" to "application/json", "map" to "application/json",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
            "ico" to "image/x-icon", "bmp" to "image/bmp",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "oga" to "audio/ogg",
            "wav" to "audio/wav", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
            "mp4" to "video/mp4", "webm" to "video/webm", "ogv" to "video/ogg",
            "wasm" to "application/wasm",
            "txt" to "text/plain", "csv" to "text/csv", "xml" to "application/xml",
            "toml" to "text/plain", "md" to "text/plain",
            "pdf" to "application/pdf", "zip" to "application/zip",
        )

        fun mimeType(name: String): String =
            MIME[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    }
}
