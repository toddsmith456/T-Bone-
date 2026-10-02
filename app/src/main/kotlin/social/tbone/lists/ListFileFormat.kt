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
import kotlinx.serialization.json.put
import social.tbone.nostr.Nip19

/**
 * Import / export format for offline lists.
 *
 * ## Export
 * One single UTF-8 `.json` file shaped like a standard Nostr list event
 * (NIP-02 contact list for follows, NIP-51 mute list for blocks):
 *
 * ```json
 * {
 *   "kind": 3,
 *   "created_at": 1760000000,
 *   "pubkey": "<hex of the account the list belongs to>",
 *   "tags": [["p", "<hex>", "<relay hint?>", "<petname?>"], ...],
 *   "content": ""
 * }
 * ```
 *
 * That is the exact structure every Nostr client already understands, so the
 * file is not tied to T-bone. (It is intentionally unsigned: a local list is
 * not an event, and signing would require the account's signer.)
 *
 * ## Import
 * Deliberately liberal so lists from other apps/tools work too:
 *  - the JSON object above (also a fully signed relay event, extra fields ignored)
 *  - a JSON array of events (the newest matching one wins)
 *  - a JSON array of tags (`[["p","<hex>"],…]`)
 *  - a JSON array / object of npub / nprofile / hex strings
 *  - plain text: npub / nprofile / hex pubkeys separated by whitespace, commas
 *    or new lines (`#` starts a comment line, `nostr:` prefixes are fine)
 */
object ListFileFormat {

    /** Hard limits so a hostile or accidental huge file can't exhaust memory. */
    const val MAX_FILE_CHARS = 8_000_000
    const val MAX_ENTRIES = 50_000

    private val pretty = Json { prettyPrint = true }

    /** Builds the export file contents for [entries] belonging to [ownerPubkey]. */
    fun export(
        type: ListType,
        ownerPubkey: String,
        entries: List<ListEntry>,
        createdAtSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val obj = buildJsonObject {
            put("kind", type.kind)
            put("created_at", createdAtSeconds)
            put("pubkey", ownerPubkey)
            put("tags", buildJsonArray {
                entries.forEach { e ->
                    add(buildJsonArray {
                        add(JsonPrimitive("p"))
                        add(JsonPrimitive(e.pubkey))
                        // NIP-02 positional fields: a petname needs the relay slot filled.
                        if (e.relay.isNotEmpty() || e.petname.isNotEmpty()) add(JsonPrimitive(e.relay))
                        if (e.petname.isNotEmpty()) add(JsonPrimitive(e.petname))
                    })
                }
            })
            put("content", "")
        }
        return pretty.encodeToString(JsonObject.serializer(), obj) + "\n"
    }

    /** Suggested file name for an export, e.g. `tbone-follows-npub1abcd…-20261001.json`. */
    fun suggestedFileName(type: ListType, ownerPubkey: String, dateYyyyMmDd: String): String {
        val npub = runCatching { Nip19.hexToNpub(ownerPubkey) }.getOrNull()?.take(12) ?: ownerPubkey.take(8)
        return "tbone-${type.slug}-$npub-$dateYyyyMmDd.json"
    }

    /**
     * Result of parsing an import file.
     *
     * @property entries      valid, de-duplicated entries (file order)
     * @property ownerPubkey  the account the file says it was exported from (if known)
     * @property skipped      number of unusable items that were ignored
     * @property error        non-null when the file must be rejected as a whole
     */
    data class Parsed(
        val entries: List<ListEntry> = emptyList(),
        val ownerPubkey: String? = null,
        val skipped: Int = 0,
        val error: String? = null,
    )

    fun parse(rawText: String, type: ListType): Parsed {
        val text = rawText.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return Parsed(error = "The file is empty.")
        if (text.length > MAX_FILE_CHARS) return Parsed(error = "The file is too large to be a list.")

        return if (text.startsWith("{") || text.startsWith("[")) {
            val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                ?: return Parsed(error = "The file looks like JSON but could not be read.")
            parseJson(element, type)
        } else {
            parsePlainText(text)
        }
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    private fun parseJson(root: JsonElement, type: ListType): Parsed {
        val collector = Collector()
        var owner: String? = null

        when (root) {
            is JsonObject -> {
                val err = readObject(root, type, collector)
                if (err != null) return Parsed(error = err)
                owner = root.str("pubkey")?.let(Nip19::normalisePubkey)
            }
            is JsonArray -> {
                // Array of events? keep the newest one of an accepted kind.
                val events = root.filterIsInstance<JsonObject>().filter { it["tags"] is JsonArray }
                if (events.isNotEmpty()) {
                    val usable = events.filter { (it.int("kind") ?: type.kind) in type.acceptedImportKinds }
                    if (usable.isEmpty()) {
                        val k = events.firstNotNullOfOrNull { it.int("kind") }
                        return Parsed(error = wrongKind(type, k))
                    }
                    val newest = usable.maxByOrNull { it.long("created_at") ?: 0L }!!
                    val err = readObject(newest, type, collector)
                    if (err != null) return Parsed(error = err)
                    owner = newest.str("pubkey")?.let(Nip19::normalisePubkey)
                } else {
                    readLooseArray(root, collector)
                }
            }
            else -> return Parsed(error = "Unrecognised file contents.")
        }

        val entries = collector.entries()
        if (entries.isEmpty() && collector.skipped == 0) {
            return Parsed(ownerPubkey = owner, error = "No ${type.plural} found in this file.")
        }
        if (entries.isEmpty()) {
            return Parsed(ownerPubkey = owner, skipped = collector.skipped, error = "No valid pubkeys found in this file.")
        }
        if (entries.size > MAX_ENTRIES) {
            return Parsed(error = "This file has more than $MAX_ENTRIES entries — refusing to import it.")
        }
        return Parsed(entries = entries, ownerPubkey = owner, skipped = collector.skipped)
    }

    /** Reads one event-like or container object; returns an error message or null. */
    private fun readObject(obj: JsonObject, type: ListType, out: Collector): String? {
        val tags = obj["tags"]
        if (tags is JsonArray) {
            val kind = obj.int("kind")
            if (kind != null && kind !in type.acceptedImportKinds) return wrongKind(type, kind)
            readTags(tags, out)
            return null
        }
        // Container objects: {"follows":[...]} / {"pubkeys":[...]} / {"mutes":[...]} …
        var found = false
        for (key in CONTAINER_KEYS) {
            val v = obj[key] ?: continue
            if (v is JsonArray) { readLooseArray(v, out); found = true }
        }
        return if (found) null else "Unrecognised file contents (no tags or pubkey list)."
    }

    private val CONTAINER_KEYS = listOf(
        "follows", "following", "contacts", "mutes", "muted", "blocked", "pubkeys", "npubs", "list", "p",
    )

    private fun readTags(tags: JsonArray, out: Collector) {
        for (t in tags) {
            val arr = t as? JsonArray
            if (arr == null) { out.skip(); continue }
            val name = (arr.getOrNull(0) as? JsonPrimitive)?.contentOrNull
            if (name != "p") continue // other tags (t, word, e, r…) are not pubkeys; not an error
            val values = arr.map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
            out.add(values.getOrNull(1), values.getOrNull(2), values.getOrNull(3))
        }
    }

    /** Array of strings, tags, or `{pubkey: …}` objects. */
    private fun readLooseArray(arr: JsonArray, out: Collector) {
        for (el in arr) {
            when (el) {
                is JsonPrimitive -> if (el !is JsonNull) out.add(el.contentOrNull)
                is JsonArray -> {
                    val values = el.map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
                    if (values.firstOrNull() == "p") out.add(values.getOrNull(1), values.getOrNull(2), values.getOrNull(3))
                    else out.skip()
                }
                is JsonObject -> {
                    val pk = el.str("pubkey") ?: el.str("npub") ?: el.str("pk")
                    if (pk != null) out.add(pk, el.str("relay"), el.str("petname"))
                    else out.skip()
                }
                else -> out.skip()
            }
        }
    }

    // ── Plain text ────────────────────────────────────────────────────────────

    private fun parsePlainText(text: String): Parsed {
        val out = Collector()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            for (token in trimmed.split(Regex("[\\s,;\"'\\[\\]]+"))) {
                if (token.isNotEmpty()) out.add(token)
            }
        }
        val entries = out.entries()
        if (entries.isEmpty()) return Parsed(skipped = out.skipped, error = "No valid pubkeys (npub / hex) found in this file.")
        if (entries.size > MAX_ENTRIES) return Parsed(error = "This file has more than $MAX_ENTRIES entries — refusing to import it.")
        return Parsed(entries = entries, skipped = out.skipped)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun wrongKind(type: ListType, kind: Int?): String =
        if (kind == null) "Unrecognised file contents."
        else "This file is a ${ListType.describeKind(kind)}, not a ${type.listName}."

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /** Collects entries, normalising pubkeys and de-duplicating while keeping order. */
    private class Collector {
        private val map = LinkedHashMap<String, ListEntry>()
        var skipped = 0
            private set

        fun skip() { skipped++ }

        fun add(rawPubkey: String?, relay: String? = null, petname: String? = null) {
            val pk = normalise(rawPubkey)
            if (pk == null) { skipped++; return }
            if (map.containsKey(pk)) return
            map[pk] = ListEntry(
                pubkey = pk,
                relay = relay.orEmpty().trim().takeIf { isRelayUrl(it) } ?: "",
                petname = petname.orEmpty().trim().take(MAX_PETNAME),
            )
        }

        fun entries(): List<ListEntry> = map.values.toList()
    }

    private const val MAX_PETNAME = 128

    private fun isRelayUrl(s: String) = s.startsWith("wss://") || s.startsWith("ws://")

    /**
     * Accepts npub / nprofile / hex (optionally `nostr:`-prefixed).
     * Returns lowercase 64-char hex, or null.
     */
    fun normalise(raw: String?): String? {
        val input = raw?.trim()?.removePrefix("nostr:")?.trim().orEmpty()
        if (input.isEmpty()) return null
        val lower = input.lowercase()
        return when {
            lower.startsWith("npub1") -> Nip19.npubToHex(lower)
            lower.startsWith("nprofile1") -> Nip19.nprofileToHex(lower)
            lower.length == 64 && lower.all { it in '0'..'9' || it in 'a'..'f' } -> lower
            else -> null
        }
    }
}
