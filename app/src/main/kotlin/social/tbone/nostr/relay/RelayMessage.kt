package social.tbone.nostr.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import social.tbone.nostr.Event
import social.tbone.nostr.NostrJson

/**
 * Messages sent FROM a relay TO the client, per NIP-01.
 */
sealed interface RelayMessage {

    /** ["EVENT", <subscription_id>, <event JSON>] */
    data class EventMessage(
        val subscriptionId: String,
        val event: Event,
    ) : RelayMessage

    /** ["EOSE", <subscription_id>] — end of stored events */
    data class EndOfStoredEvents(val subscriptionId: String) : RelayMessage

    /** ["NOTICE", <message>] — human-readable relay message */
    data class Notice(val message: String) : RelayMessage

    /** ["OK", <event_id>, <true|false>, <message>] — publish result */
    data class Ok(
        val eventId: String,
        val accepted: Boolean,
        val message: String,
    ) : RelayMessage

    /** ["CLOSED", <subscription_id>, <message>] — relay closed a subscription */
    data class Closed(
        val subscriptionId: String,
        val message: String,
    ) : RelayMessage

    /** ["AUTH", <challenge>] — NIP-42 authentication challenge */
    data class Auth(val challenge: String) : RelayMessage

    /** Unparseable or unknown message — retained for debugging */
    data class Unknown(val raw: String) : RelayMessage

    /** Internal: emitted by [RelayConnection] when the WebSocket handshake completes. */
    data object Connected : RelayMessage

    companion object {
        fun parse(raw: String): RelayMessage = runCatching {
            val array = NostrJson.parseToJsonElement(raw).jsonArray
            when (val type = array[0].jsonPrimitive.content) {
                "EVENT" -> EventMessage(
                    subscriptionId = array[1].jsonPrimitive.content,
                    event = NostrJson.decodeFromJsonElement(Event.serializer(), array[2]),
                )
                "EOSE" -> EndOfStoredEvents(array[1].jsonPrimitive.content)
                "NOTICE" -> Notice(array[1].jsonPrimitive.content)
                "OK" -> Ok(
                    eventId = array[1].jsonPrimitive.content,
                    accepted = array[2].jsonPrimitive.boolean,
                    message = array.getOrNull(3)?.jsonPrimitive?.content ?: "",
                )
                "CLOSED" -> Closed(
                    subscriptionId = array[1].jsonPrimitive.content,
                    message = array.getOrNull(2)?.jsonPrimitive?.content ?: "",
                )
                "AUTH" -> Auth(array[1].jsonPrimitive.content)
                else -> Unknown(raw)
            }
        }.getOrElse { Unknown(raw) }
    }
}
