package social.tbone.nostr.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement
import social.tbone.nostr.Event
import social.tbone.nostr.Filter

/**
 * Messages sent FROM the client TO a relay, per NIP-01.
 */
sealed interface ClientMessage {

    fun toJson(): String

    /** ["REQ", <subscription_id>, <filter>, ...] */
    data class Req(
        val subscriptionId: String,
        val filters: List<Filter>,
    ) : ClientMessage {
        override fun toJson(): String = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            filters.forEach { add(it.toJsonObject()) }
        }.toString()
    }

    /** ["CLOSE", <subscription_id>] */
    data class Close(val subscriptionId: String) : ClientMessage {
        override fun toJson(): String = buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subscriptionId))
        }.toString()
    }

    /** ["EVENT", <event>] — publish an event */
    data class Publish(val event: Event) : ClientMessage {
        override fun toJson(): String = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(Json.encodeToJsonElement(event))
        }.toString()
    }

    /** ["AUTH", <signed event>] — NIP-42 authentication response */
    data class Auth(val event: Event) : ClientMessage {
        override fun toJson(): String = buildJsonArray {
            add(JsonPrimitive("AUTH"))
            add(Json.encodeToJsonElement(event))
        }.toString()
    }
}
