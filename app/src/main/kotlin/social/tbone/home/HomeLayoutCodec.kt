package social.tbone.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Reads and writes [HomeLayout] by hand rather than through a generated
 * serializer.
 *
 * That is a deliberate, slightly unusual choice. A generated polymorphic
 * serializer for the sealed [FeedSource] hierarchy fails the *whole* layout on
 * one unknown `type` string, and the realistic ways a layout can contain one are
 * all ordinary: restored from a newer build, hand-edited in an export, downgraded
 * after an update. Losing every tab because one feed type was added in a later
 * version is not an acceptable failure, so this codec:
 *
 *  - decodes each slot independently and drops only slots it cannot read;
 *  - turns an unrecognised `type` into [FeedSource.Unsupported] rather than
 *    failing, so the slot survives a downgrade and comes back on re-upgrade;
 *  - clamps every numeric field (hours 1..24) instead of trusting it;
 *  - caps the payload size before parsing at all.
 *
 * The writer stays plain JSON so a layout can be copy-pasted between devices.
 */
object HomeLayoutCodec {

    /** Hard ceiling on an imported layout. Anything larger is not a layout. */
    const val MAX_PAYLOAD_BYTES = 256 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** Never throws. A null return means "keep whatever we had". */
    fun decode(raw: String?): HomeLayout? {
        if (raw.isNullOrBlank()) return null
        if (raw.length > MAX_PAYLOAD_BYTES) {
            Timber.w("home layout: payload too large (%d chars), ignoring", raw.length)
            return null
        }
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: run {
            Timber.w("home layout: not a JSON object, ignoring")
            return null
        }

        val slotArray = root["slots"] as? JsonArray ?: return null
        val slots = slotArray.mapNotNull { element -> runCatching { decodeSlot(element) }.getOrNull() }
        if (slots.isEmpty()) return null

        val lastActive = (root["lastActiveSlotId"] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        return HomeLayout(slots = slots, lastActiveSlotId = lastActive).normalised()
    }

    fun encode(layout: HomeLayout): String =
        json.encodeToString(JsonObject.serializer(), toJson(layout))

    // ── Slots ────────────────────────────────────────────────────────────────

    private fun toJson(layout: HomeLayout): JsonObject {
        val slotsJson = JsonArray(layout.slots.map { slot ->
            buildJsonObjectOf(
                "id" to JsonPrimitive(slot.id),
                "visible" to JsonPrimitive(slot.visible),
                "source" to sourceToJson(slot.source),
            )
        })
        val fields = linkedMapOf<String, JsonElement>(
            "version" to JsonPrimitive(VERSION),
            "slots" to slotsJson,
        )
        layout.lastActiveSlotId?.let { fields["lastActiveSlotId"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun decodeSlot(element: JsonElement): FeedSlot? {
        val obj = element.jsonObject
        val sourceElement = obj["source"] ?: return null
        val source = decodeSource(sourceElement) ?: return null
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return FeedSlot.of(source)
        val visible = (obj["visible"] as? JsonPrimitive)?.booleanOrNull ?: true
        return FeedSlot(source = source, id = id, visible = visible)
    }

    // ── Sources ──────────────────────────────────────────────────────────────

    private fun sourceToJson(source: FeedSource): JsonObject = when (source) {
        is FeedSource.Follows -> buildJsonObjectOf(
            "type" to JsonPrimitive("follows"),
            "label" to JsonPrimitive(source.label),
        )
        is FeedSource.Clock -> buildJsonObjectOf(
            "type" to JsonPrimitive("clock"),
            "hours" to JsonPrimitive(source.hours),
            "label" to JsonPrimitive(source.label),
        )
        is FeedSource.Suggested -> buildJsonObjectOf(
            "type" to JsonPrimitive("suggested"),
            "label" to JsonPrimitive(source.label),
        )
        is FeedSource.Global -> buildJsonObjectOf(
            "type" to JsonPrimitive("global"),
            "label" to JsonPrimitive(source.label),
        )
        is FeedSource.People -> JsonObject(
            buildMap {
                put("type", JsonPrimitive("people"))
                put("name", JsonPrimitive(source.name))
                put("pubkeys", JsonArray(source.pubkeys.map(::JsonPrimitive)))
                put("origin", JsonPrimitive(source.origin.name))
                put("label", JsonPrimitive(source.label))
                // Only written when the list came from a published set.
                source.dTag?.let { put("dTag", JsonPrimitive(it)) }
            },
        )
        is FeedSource.Relay -> buildJsonObjectOf(
            "type" to JsonPrimitive("relay"),
            "urls" to JsonArray(source.urls.map(::JsonPrimitive)),
            "authors" to JsonArray(source.authors.map(::JsonPrimitive)),
            "label" to JsonPrimitive(source.label),
        )
        is FeedSource.Hashtag -> buildJsonObjectOf(
            "type" to JsonPrimitive("hashtag"),
            "tags" to JsonArray(source.tags.map(::JsonPrimitive)),
            "label" to JsonPrimitive(source.label),
        )
        is FeedSource.Polls -> buildJsonObjectOf(
            "type" to JsonPrimitive("polls"),
            "authors" to JsonArray(source.authors.map(::JsonPrimitive)),
            "label" to JsonPrimitive(source.label),
        )
        is FeedSource.Unsupported -> buildJsonObjectOf(
            "type" to JsonPrimitive("unsupported"),
            "typeName" to JsonPrimitive(source.typeName),
            "label" to JsonPrimitive(source.label),
        )
    }

    private fun decodeSource(element: JsonElement): FeedSource? {
        val obj = element.jsonObject
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return null
        val label = (obj["label"] as? JsonPrimitive)?.contentOrNull
        return when (type) {
            "follows" -> FeedSource.Follows()
            "clock" -> FeedSource.Clock(
                hours = ((obj["hours"] as? JsonPrimitive)?.intOrNull ?: 6).coerceIn(1, 24),
            )
            "suggested" -> FeedSource.Suggested()
            "global" -> FeedSource.Global()
            "people" -> {
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val pubkeys = obj.stringList("pubkeys")
                if (pubkeys.isEmpty()) return null // a people feed with nobody in it is not a feed
                val origin = (obj["origin"] as? JsonPrimitive)?.contentOrNull
                    ?.let { name -> PeopleOrigin.entries.firstOrNull { it.name == name } }
                    ?: PeopleOrigin.MANUAL
                FeedSource.People(
                    name = name.ifBlank { "people" },
                    pubkeys = pubkeys,
                    origin = origin,
                    dTag = (obj["dTag"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                )
            }
            "relay" -> {
                val urls = obj.stringList("urls").filter { it.isNotBlank() }
                if (urls.isEmpty()) return null
                FeedSource.Relay(urls = urls, authors = obj.stringList("authors"))
            }
            "hashtag" -> {
                val tags = obj.stringList("tags")
                    .map { it.trim().removePrefix("#").lowercase() }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (tags.isEmpty()) return null
                FeedSource.Hashtag(tags = tags)
            }
            "polls" -> FeedSource.Polls(authors = obj.stringList("authors"))
            // Unknown type: keep the slot alive. The label is carried through so
            // the placeholder can say which feed is missing.
            else -> FeedSource.Unsupported(
                typeName = type,
                label = label?.takeIf { it.isNotBlank() } ?: "FEED",
            )
        }
    }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

    private fun buildJsonObjectOf(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(pairs.toMap())

    /** Bumped when the on-disk shape changes; the codec itself stays forward-tolerant. */
    const val VERSION = 1
}
