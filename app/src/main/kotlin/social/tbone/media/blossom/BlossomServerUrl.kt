package social.tbone.media.blossom

/**
 * Endpoint paths, header names and URL shapes of the Blossom HTTP API
 * (BUD-01 … BUD-12).
 *
 * Centralised here so the uploader, the settings screen and the tests all
 * derive `<server>/upload` the same way instead of hand-concatenating strings.
 */
object BlossomServerUrl {

    /** BUD-01 upload endpoint: `PUT <server>/upload`. */
    const val UPLOAD_PATH = "/upload"

    /** BUD-04 mirror endpoint: `PUT <server>/mirror`. */
    const val MIRROR_PATH = "/mirror"

    /** BUD-05 server-side optimisation endpoint: `PUT <server>/media`. */
    const val MEDIA_PATH = "/media"

    /** BUD-02 listing prefix: `GET <server>/list/<pubkey>`. */
    const val LIST_PATH = "/list/"

    /** BUD-01 blob endpoint prefix: `GET|DELETE <server>/<sha256>[.ext]`. */
    const val BLOB_PATH = "/"

    /**
     * Human-readable failure reason a Blossom server SHOULD attach to a
     * non-2xx response (BUD-01). Surfacing it is what turns an opaque
     * "HTTP 400" into an actionable message in the compose screen.
     */
    const val REASON_HEADER = "X-Reason"

    /** BUD-06 preflight request headers. */
    const val X_SHA_256_HEADER = "X-SHA-256"
    const val X_CONTENT_TYPE_HEADER = "X-Content-Type"
    const val X_CONTENT_LENGTH_HEADER = "X-Content-Length"

    /** BUD-07 payment headers. */
    const val X_CASHU_HEADER = "X-Cashu"
    const val X_LIGHTNING_HEADER = "X-Lightning"

    /** `<server>/upload`. */
    fun upload(serverBaseUrl: String): String = base(serverBaseUrl) + UPLOAD_PATH

    /** `<server>/media`. */
    fun media(serverBaseUrl: String): String = base(serverBaseUrl) + MEDIA_PATH

    /** `<server>/mirror`. */
    fun mirror(serverBaseUrl: String): String = base(serverBaseUrl) + MIRROR_PATH

    /** BUD-02 `<server>/list/<pubkey>`. */
    fun list(serverBaseUrl: String, pubkey: String): String = base(serverBaseUrl) + LIST_PATH + pubkey

    /**
     * BUD-01 blob endpoint `<server>/<sha256>[.<ext>]`.
     * A blank [extension] omits the suffix — servers MUST accept both forms.
     */
    fun blob(serverBaseUrl: String, hash: String, extension: String = ""): String {
        val suffix = if (extension.isBlank()) "" else ".${extension.trimStart('.')}"
        return base(serverBaseUrl) + BLOB_PATH + hash.lowercase() + suffix
    }

    /**
     * Strips trailing slashes so path concatenation can never produce `//`.
     */
    fun base(serverBaseUrl: String): String = serverBaseUrl.trim().trimEnd('/')

    /**
     * The lowercase bare domain of [serverBaseUrl] — the exact form the BUD-11
     * `server` authorization tag requires ("lowercase domain name only", no
     * scheme, no port, no path). Returns "" when no host can be found.
     */
    fun domain(serverBaseUrl: String): String {
        val withoutScheme = serverBaseUrl.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
        val host = withoutScheme.substringBefore('@').substringBefore(':')
        return host.lowercase()
    }

    /**
     * Normalises user input from the settings screen into a canonical base URL:
     * trims, defaults the scheme to `https://` and drops trailing slashes.
     * Returns null when the result clearly cannot be a server (no host, spaces).
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        val (scheme, rest) = when {
            lower.startsWith("https://") -> "https://" to trimmed.substring(8)
            lower.startsWith("http://") -> "http://" to trimmed.substring(7)
            else -> "https://" to trimmed
        }

        // Lower-case the authority (host + optional port) so "CDN.Example.com"
        // and "cdn.example.com" collapse to one entry in the server pool. Paths
        // are dropped: Blossom endpoints always live at the domain root.
        val authority = rest.substringBefore('/').trim().lowercase()
        if (authority.isEmpty()) return null

        val host = domain(authority)
        if (host.isEmpty() || host.contains(' ') || !host.contains('.')) return null

        return "$scheme$authority"
    }
}
