package social.tbone.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.json.JsonArray
import social.tbone.nostr.Event
import social.tbone.nostr.NostrJson

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["kind", "accountPubkey"]),
        Index(value = ["createdAt"]),
    ],
)
data class EventEntity(
    @PrimaryKey val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tagsJson: String,
    val content: String,
    val sig: String,
    /** The local account pubkey whose feed this event belongs to. */
    val accountPubkey: String = "",
)

fun Event.toEntity(accountPubkey: String) = EventEntity(
    id = id,
    pubkey = pubkey,
    createdAt = createdAt,
    kind = kind,
    tagsJson = NostrJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(JsonArray.serializer()), tags),
    content = content,
    sig = sig,
    accountPubkey = accountPubkey,
)

fun EventEntity.toEvent() = Event(
    id = id,
    pubkey = pubkey,
    createdAt = createdAt,
    kind = kind,
    tags = NostrJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(JsonArray.serializer()), tagsJson),
    content = content,
    sig = sig,
)
