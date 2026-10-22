package social.tbone.webxdc

/**
 * Pure HTML helpers for serving a mini app. Kept free of Android types so the
 * security-relevant behaviour can be unit tested on the JVM.
 */
object XdcHtml {

    private val HEAD = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)

    /** A real `<script src=…webxdc.js>` reference, not just the word in prose. */
    private val SCRIPT_SRC = Regex(
        "<script[^>]+src\\s*=\\s*[\"']?[^\"'>]*webxdc\\.js",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Makes sure `window.webxdc` exists before any app script runs.
     *
     * The webxdc spec says `webxdc.js` is provided by the messenger and must
     * not live in the archive; apps reference it with `<script src="webxdc.js">`.
     * Apps that forget the reference still get the bridge because every HTML
     * response has the script injected as the first head child.
     */
    fun injectBridge(html: String): String {
        if (SCRIPT_SRC.containsMatchIn(html)) return html
        val head = HEAD.find(html)
        return if (head != null) {
            val insertAt = head.range.last + 1
            html.substring(0, insertAt) + "<script src=\"webxdc.js\"></script>" + html.substring(insertAt)
        } else {
            "<script src=\"webxdc.js\"></script>" + html
        }
    }

    /** Content-Security-Policy used for every HTML response (no external network). */
    const val CSP =
        "default-src 'self' 'unsafe-inline' 'unsafe-eval' 'wasm-unsafe-eval' data: blob:; " +
            "base-uri 'self'; form-action 'self'"
}
