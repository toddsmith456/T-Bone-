package social.tbone.webxdc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Anything wrong with an .xdc container — invalid zip, missing index.html, unsafe paths, too big. */
class XdcException(message: String) : Exception(message)

/**
 * The subset of `manifest.toml` the player needs.
 * https://webxdc.org/docs/spec/format.html
 */
data class XdcManifest(
    val name: String? = null,
    val sourceCodeUrl: String? = null,
    val description: String? = null,
)

/**
 * A validated, fully-in-memory webxdc package.
 *
 * The reader never touches the filesystem, so the exact same code path is used
 * for local imports, store downloads and Nostr attachments — and it is unit
 * testable on a plain JVM.
 */
data class XdcPackage(
    /** Stable id derived from the archive bytes (sha256, first 32 hex chars). */
    val id: String,
    val name: String,
    val sourceCodeUrl: String?,
    val description: String?,
    /** Normalised path -> bytes. Always contains `index.html`. */
    val files: Map<String, ByteArray>,
    /** Root-level `icon.png` / `icon.jpg` path, if the archive has one. */
    val iconPath: String?,
    val sizeBytes: Long,
) {
    fun file(path: String): ByteArray? = files[path]
}

/**
 * Reads and validates `.xdc` containers exactly as the webxdc spec describes:
 * a ZIP using Store/Deflate, containing at least `index.html`, optionally a
 * `manifest.toml` and `icon.png`/`icon.jpg`.
 *
 * Hard limits protect the device from zip bombs and runaway archives; webxdc
 * mini apps are a few kilobytes to a few megabytes in practice.
 */
object XdcArchive {

    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 96L * 1024 * 1024
    const val MAX_ENTRIES = 4096

    private const val INDEX_HTML = "index.html"

    /**
     * @param bytes the raw `.xdc` file
     * @param fallbackName used when the archive has no manifest name (usually the file name)
     */
    fun read(bytes: ByteArray, fallbackName: String? = null): XdcPackage {
        if (bytes.isEmpty()) throw XdcException("empty file")
        if (bytes.size > MAX_ARCHIVE_BYTES) throw XdcException("archive is too large")

        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var entries = 0
        var hasIndex = false

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                entries++
                if (entries > MAX_ENTRIES) throw XdcException("too many files in archive")

                // RFC 1950 method 0 = Store, 8 = Deflate. Anything else (e.g. bzip2) is not webxdc.
                val method = entry.method
                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                    throw XdcException("unsupported compression method")
                }

                val rawName = entry.name ?: throw XdcException("invalid entry name")
                if (!entry.isDirectory) {
                    val raw = normalisePath(rawName) ?: throw XdcException("unsafe path in archive")
                    // The spec requires `index.html`; tolerate a capitalised name
                    // and store it under the canonical key so `/` can serve it.
                    val path = if (raw.equals(INDEX_HTML, ignoreCase = true)) INDEX_HTML else raw
                    val data = readCapped(zip)
                    total += data.size
                    if (total > MAX_UNCOMPRESSED_BYTES) throw XdcException("archive expands too large")
                    files[path] = data
                    if (path == INDEX_HTML) hasIndex = true
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (files.isEmpty()) throw XdcException("not a zip archive")
        if (!hasIndex) throw XdcException("index.html is missing")

        val manifest = files["manifest.toml"]?.let { parseManifest(String(it, Charsets.UTF_8)) }
        val name = manifest?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackName?.removeSuffix(".xdc")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "webxdc app"

        val iconPath = files.keys.firstOrNull { path ->
            !path.contains('/') && path.lowercase() in ICON_NAMES
        }

        return XdcPackage(
            id = sha256Hex(bytes).take(32),
            name = name,
            sourceCodeUrl = manifest?.sourceCodeUrl,
            description = manifest?.description,
            files = files,
            iconPath = iconPath,
            sizeBytes = bytes.size.toLong(),
        )
    }

    /** Same validation for a filename-only probe (used for store entries before download). */
    fun looksLikeXdc(bytes: ByteArray): Boolean =
        runCatching { read(bytes) }.isSuccess

    private val ICON_NAMES = setOf("icon.png", "icon.jpg", "icon.jpeg")

    private fun readCapped(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_ARCHIVE_BYTES) throw XdcException("entry is too large")
        }
        return out.toByteArray()
    }

    /**
     * Normalises a zip entry path and rejects anything that could escape the
     * app's private directory: absolute paths, `..`, NUL bytes, drive letters.
     */
    internal fun normalisePath(raw: String): String? {
        if (raw.isEmpty() || raw.contains('\u0000')) return null
        var p = raw.replace('\\', '/')
        while (p.startsWith("./")) p = p.substring(2)
        p = p.trimStart('/')
        if (p.isEmpty()) return null
        if (p.contains("..")) return null
        if (p.length >= 2 && p[1] == ':') return null
        // Drop empty segments ("a//b") so the served URL and the map key agree.
        p = p.split('/').filter { it.isNotEmpty() }.joinToString("/")
        return p.ifEmpty { null }
    }

    /** Parses the tiny TOML subset webxdc manifests use. Unknown keys are ignored. */
    fun parseManifest(toml: String): XdcManifest {
        var name: String? = null
        var source: String? = null
        var description: String? = null

        for (line in toml.lineSequence()) {
            val trimmed = stripComment(line).trim()
            if (trimmed.isEmpty() || trimmed.startsWith("[")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim().trim('"', '\'')
            val value = unquote(trimmed.substring(eq + 1).trim()) ?: continue
            when (key) {
                "name" -> name = value
                "source_code_url" -> source = value
                "description" -> description = value
            }
        }
        return XdcManifest(name, source, description)
    }

    private fun stripComment(line: String): String {
        var inString = false
        var quote = ' '
        var escaped = false
        for ((i, c) in line.withIndex()) {
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\' && quote == '"') escaped = true
                else if (c == quote) inString = false
            } else {
                if (c == '"' || c == '\'') {
                    inString = true
                    quote = c
                } else if (c == '#') {
                    return line.substring(0, i)
                }
            }
        }
        return line
    }

    private fun unquote(value: String): String? {
        if (value.length < 2) return null
        return when {
            value.startsWith("\"") && value.endsWith("\"") -> {
                val body = value.substring(1, value.length - 1)
                buildString {
                    var i = 0
                    while (i < body.length) {
                        val c = body[i]
                        if (c == '\\' && i + 1 < body.length) {
                            when (val next = body[i + 1]) {
                                'n' -> append('\n')
                                't' -> append('\t')
                                'r' -> append('\r')
                                '"' -> append('"')
                                '\\' -> append('\\')
                                else -> append(next)
                            }
                            i += 2
                        } else {
                            append(c)
                            i++
                        }
                    }
                }
            }
            value.startsWith("'") && value.endsWith("'") -> value.substring(1, value.length - 1)
            else -> value.takeIf { it.isNotBlank() }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }
}
