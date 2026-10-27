package social.tbone.lists

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import social.tbone.nostr.relay.RelayPool
import java.net.URI

/**
 * Import / export format for NIP-65 relay lists (kind 10002).
 *
 * Export is a single JSON file shaped exactly like a relay-published event:
 * ```
 * {
 *   "kind": 10002,
 *   "created_at": 1760000000,
 *   "pubkey": "<hex>",
 *   "tags": [["r","wss://..."], ["r","wss://...","read"], ["r","wss://...","write"], ...],
 *   "content": ""
 * }
 * ```
 * That is the exact structure relays store, so the file is directly readable by other clients.
 *
 * Import is deliberately liberal:
 *  - the JSON object above (also a fully signed event, extra fields ignored)
 *  - a JSON array of events (newest matching wins)
 *  - a JSON array of tags ([["r","wss://..."], ...])
 *  - a JSON array/object of relay URL strings
 *  - plain text: relay URLs separated by whitespace, commas or new lines
 */
object RelayFileFormat {

    const val MAX_FILE_CHARS = 8_000_000
    const val MAX_RELAYS = 200
    const val RELAY_KIND = 10002

    private val pretty = Json { prettyPrint = true }

    /** Builds export file contents for [relays] belonging to [ownerPubkey]. */
    fun export(
        ownerPubkey: String,
        relays: List<String>,
        createdAtSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val obj = buildJsonObject {
            put("kind", RELAY_KIND)
            put("created_at", createdAtSeconds)
            put("pubkey", ownerPubkey)
            put("tags", buildJsonArray {
                relays.forEach { url ->
                    add(buildJsonArray {
                        add(JsonPrimitive("r"))
                        add(JsonPrimitive(url))
                    })
                }
            })
            put("content", "")
        }
        return pretty.encodeToString(JsonObject.serializer(), obj) + "\n"
    }

    /** Export preserving original read/write markers if available. */
    fun exportWithMarkers(
        ownerPubkey: String,
        relaysWithMarkers: List<RelayMarker>,
        createdAtSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val obj = buildJsonObject {
            put("kind", RELAY_KIND)
            put("created_at", createdAtSeconds)
            put("pubkey", ownerPubkey)
            put("tags", buildJsonArray {
                relaysWithMarkers.forEach { rm ->
                    add(buildJsonArray {
                        add(JsonPrimitive("r"))
                        add(JsonPrimitive(rm.url))
                        if (rm.marker != null) add(JsonPrimitive(rm.marker))
                    })
                }
            })
            put("content", "")
        }
        return pretty.encodeToString(JsonObject.serializer(), obj) + "\n"
    }

    fun suggestedFileName(ownerPubkey: String, dateYyyyMmDd: String): String {
        val short = ownerPubkey.take(8)
        return "tbone-relays-$short-$dateYyyyMmDd.json"
    }

    data class RelayMarker(val url: String, val marker: String? = null)

    data class Parsed(
        val relays: List<String> = emptyList(),
        val relaysWithMarkers: List<RelayMarker> = emptyList(),
        val ownerPubkey: String? = null,
        val skipped: Int = 0,
        val error: String? = null,
    )

    fun parse(rawText: String): Parsed {
        val text = rawText.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return Parsed(error = "The file is empty.")
        if (text.length > MAX_FILE_CHARS) return Parsed(error = "The file is too large to be a relay list.")

        return if (text.startsWith("{") || text.startsWith("[")) {
            val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                ?: return Parsed(error = "The file looks like JSON but could not be read.")
            parseJson(element)
        } else {
            parsePlainText(text)
        }
    }

    private fun parseJson(root: JsonElement): Parsed {
        val collector = Collector()
        var owner: String? = null

        when (root) {
            is JsonObject -> {
                val err = readObject(root, collector)
                if (err != null) return Parsed(error = err)
                owner = root.str("pubkey")
            }
            is JsonArray -> {
                val events = root.filterIsInstance<JsonObject>().filter { it["tags"] is JsonArray }
                if (events.isNotEmpty()) {
                    val usable = events.filter { (it.int("kind") ?: RELAY_KIND) == RELAY_KIND }
                    if (usable.isEmpty()) {
                        val k = events.firstNotNullOfOrNull { it.int("kind") }
                        return Parsed(error = if (k != null) "This file is a kind $k event, not a relay list (kind 10002)." else "Unrecognised file contents.")
                    }
                    val newest = usable.maxByOrNull { it.long("created_at") ?: 0L }!!
                    val err = readObject(newest, collector)
                    if (err != null) return Parsed(error = err)
                    owner = newest.str("pubkey")
                } else {
                    readLooseArray(root, collector)
                }
            }
            else -> return Parsed(error = "Unrecognised file contents.")
        }

        val relays = collector.relays()
        val markers = collector.markers()
        if (relays.isEmpty() && collector.skipped == 0) {
            return Parsed(ownerPubkey = owner, error = "No relays found in this file.")
        }
        if (relays.isEmpty()) {
            return Parsed(ownerPubkey = owner, skipped = collector.skipped, error = "No valid relay URLs found in this file.")
        }
        if (relays.size > MAX_RELAYS) {
            return Parsed(error = "This file has more than $MAX_RELAYS relays — refusing to import it.")
        }
        return Parsed(relays = relays, relaysWithMarkers = markers, ownerPubkey = owner, skipped = collector.skipped)
    }

    private fun readObject(obj: JsonObject, out: Collector): String? {
        val tags = obj["tags"]
        if (tags is JsonArray) {
            val kind = obj.int("kind")
            if (kind != null && kind != RELAY_KIND) return "This file is a kind $kind event, not a relay list (kind 10002)."
            readTags(tags, out)
            return null
        }
        var found = false
        for (key in CONTAINER_KEYS) {
            val v = obj[key] ?: continue
            if (v is JsonArray) { readLooseArray(v, out); found = true }
            if (v is JsonObject && v["tags"] is JsonArray) {
                val innerErr = readObject(v, out)
                if (innerErr == null) found = true
            }
        }
        // Also handle { "relays": ["wss://...", ...] }
        for (key in RELAY_CONTAINER_KEYS) {
            val v = obj[key] ?: continue
            if (v is JsonArray) { readLooseArray(v, out); found = true }
        }
        return if (found) null else "Unrecognised file contents (no relay tags or relay list)."
    }

    private val CONTAINER_KEYS = listOf("follows", "following", "contacts", "mutes", "list", "tags", "p")
    private val RELAY_CONTAINER_KEYS = listOf("relays", "relay_list", "r", "relayUrls", "relay_urls")

    private fun readTags(tags: JsonArray, out: Collector) {
        for (t in tags) {
            val arr = t as? JsonArray ?: continue
            val name = (arr.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: continue
            if (name != "r") continue
            val url = (arr.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: run { out.skip(); continue }
            val marker = (arr.getOrNull(2) as? JsonPrimitive)?.contentOrNull
            out.add(url, marker)
        }
    }

    private fun readLooseArray(arr: JsonArray, out: Collector) {
        for (el in arr) {
            when (el) {
                is JsonPrimitive -> if (el !is JsonNull) out.add(el.contentOrNull)
                is JsonArray -> {
                    val first = (el.getOrNull(0) as? JsonPrimitive)?.contentOrNull
                    if (first == "r") {
                        val url = (el.getOrNull(1) as? JsonPrimitive)?.contentOrNull
                        val marker = (el.getOrNull(2) as? JsonPrimitive)?.contentOrNull
                        if (url != null) out.add(url, marker) else out.skip()
                    } else {
                        // Could be plain relay URL array with single string per inner array? skip
                        out.skip()
                    }
                }
                is JsonObject -> {
                    val url = el.str("url") ?: el.str("relay") ?: el.str("relay_url") ?: el.str("r")
                    if (url != null) {
                        val marker = el.str("marker") ?: el.str("type") ?: el.str("permission")
                        out.add(url, marker)
                    } else out.skip()
                }
                else -> out.skip()
            }
        }
    }

    private fun parsePlainText(text: String): Parsed {
        val out = Collector()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            for (token in trimmed.split(Regex("[\\s,;\"'\\[\\]]+"))) {
                if (token.isNotEmpty()) out.add(token)
            }
        }
        val relays = out.relays()
        if (relays.isEmpty()) return Parsed(skipped = out.skipped, error = "No valid relay URLs (wss:// or ws://) found in this file.")
        if (relays.size > MAX_RELAYS) return Parsed(error = "This file has more than $MAX_RELAYS relays — refusing to import it.")
        return Parsed(relays = relays, relaysWithMarkers = out.markers(), skipped = out.skipped)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private class Collector {
        private val map = LinkedHashMap<String, RelayMarker>()
        var skipped = 0
            private set

        fun skip() { skipped++ }

        fun add(rawUrl: String?, marker: String? = null) {
            val normalized = normalizeRelayUrl(rawUrl)
            if (normalized == null) { skipped++; return }
            if (map.containsKey(normalized)) return
            val cleanMarker = marker?.trim()?.lowercase()?.takeIf { it == "read" || it == "write" }
            map[normalized] = RelayMarker(normalized, cleanMarker)
        }

        fun relays(): List<String> = map.values.map { it.url }
        fun markers(): List<RelayMarker> = map.values.toList()
    }

    fun normalizeRelayUrl(raw: String?): String? {
        val input = raw?.trim()?.removePrefix("nostr:")?.trim().orEmpty()
        if (input.isEmpty()) return null
        // Must be ws:// or wss://
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "wss" && scheme != "ws") return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return RelayPool.normalize(input)
    }
}
