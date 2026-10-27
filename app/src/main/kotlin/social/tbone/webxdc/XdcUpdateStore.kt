package social.tbone.webxdc

import java.io.File
import java.util.Base64

/**
 * One `sendUpdate()` call, stored locally.
 *
 * Webxdc normally coordinates this through a chat; T-Bone is a deliberately
 * single-player xdc player, so updates live in an append-only local journal
 * and are replayed to the app with the same serial semantics the spec defines.
 */
data class XdcUpdate(
    val serial: Int,
    val payloadJson: String,
    val info: String? = null,
    val document: String? = null,
    val summary: String? = null,
    val href: String? = null,
    val createdAt: Long = 0L,
)

/**
 * Append-only per-app update journal.
 *
 * One record per line:
 * `serial \t createdAt \t b64(payload) \t b64(info) \t b64(document) \t b64(summary) \t b64(href)`
 *
 * Base64 is used for every variable field so payloads (arbitrary JSON) and
 * metadata (arbitrary unicode) never need escaping. Serials are strictly
 * increasing 1..N, matching webxdc's `serial` / `max_serial` contract.
 */
class XdcUpdateStore(private val file: File) {

    companion object {
        private const val SEP = "\t"
        private const val EMPTY = "~"
        const val MAX_UPDATES = 5000
        const val MAX_BYTES = 5L * 1024 * 1024
    }

    private val lock = Any()

    fun load(): List<XdcUpdate> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        val out = ArrayList<XdcUpdate>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split(SEP)
            if (parts.size < 7) return@forEachLine
            val serial = parts[0].toIntOrNull() ?: return@forEachLine
            out += XdcUpdate(
                serial = serial,
                payloadJson = decode(parts[2]) ?: return@forEachLine,
                info = decode(parts[3]),
                document = decode(parts[4]),
                summary = decode(parts[5]),
                href = decode(parts[6]),
                createdAt = parts[1].toLongOrNull() ?: 0L,
            )
        }
        out.sortBy { it.serial }
        // Compact duplicates (e.g. a crash between append and fsync) while loading.
        out.distinctBy { it.serial }
    }



    /** Appends one update and returns it with its assigned serial. Quota: max 5k updates / 5 MB */
    fun append(
        payloadJson: String,
        info: String? = null,
        document: String? = null,
        summary: String? = null,
        href: String? = null,
    ): XdcUpdate = synchronized(lock) {
        val existing = load().lastOrNull()?.serial ?: 0
        val update = XdcUpdate(
            serial = existing + 1,
            payloadJson = payloadJson,
            info = info,
            document = document,
            summary = summary,
            href = href,
            createdAt = System.currentTimeMillis(),
        )
        if (load().size >= MAX_UPDATES) error("webxdc update limit reached (5k)")
        if (file.exists() && file.length() > MAX_BYTES) error("webxdc journal full (5 MB)")
        file.parentFile?.mkdirs()
        file.appendText(buildString {
            append(update.serial.toString())
            append(SEP)
            append(update.createdAt.toString())
            append(SEP)
            append(encode(update.payloadJson))
            append(SEP)
            append(encode(update.info))
            append(SEP)
            append(encode(update.document))
            append(SEP)
            append(encode(update.summary))
            append(SEP)
            append(encode(update.href))
            append('\n')
        })
        update
    }

    fun clear() = synchronized(lock) {
        file.delete()
        Unit
    }

    private fun encode(value: String?): String =
        if (value == null) EMPTY else Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String? =
        if (value == EMPTY || value.isEmpty()) null
        else runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
}
