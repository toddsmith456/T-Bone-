package social.tbone.notifications

import kotlinx.serialization.Serializable
import social.tbone.nostr.Event

/**
 * The kinds of activity T-bone surfaces as notifications.
 *
 * Mirrors Wisp's NotificationType, reduced to what this app can actually do
 * (no wallet/zaps, no DMs, no group chats — so those types don't exist here).
 */
@Serializable
enum class NotifType { REACTION, REPOST, REPLY, QUOTE, MENTION, VOTE, CALENDAR }

/**
 * Filterable notification categories shown in the summary bar and the filter
 * sheet. Quotes are folded into MENTIONS, exactly like Wisp.
 */
enum class NotifFilter(val label: String) {
    REPLIES("REPLIES"),
    REACTIONS("REACTIONS"),
    REPOSTS("REPOSTS"),
    MENTIONS("MENTIONS"),
    VOTES("VOTES"),
    CALENDAR("CALENDAR"),
    ;

    companion object {
        fun fromName(name: String?): NotifFilter? =
            entries.firstOrNull { it.name == name }
    }
}

/**
 * Grouped notification state, mirroring Wisp's NotificationGroup.
 *
 * Reactions and reposts are grouped per referenced note (one group per note
 * you wrote, with per-emoji actor lists). Replies, quotes, mentions and
 * follows keep one group per event/actor. Groups drive the 24h summary and
 * the filters; the visible list is derived from [FlatNotifItem]s.
 */
sealed interface NotifGroup {
    val groupId: String
    val latestTimestamp: Long

    /** Reactions (per emoji) and reposts targeting a single one of your notes. */
    data class ReactionGroup(
        override val groupId: String,
        val referencedEventId: String,
        /** emoji -> actor pubkeys (newest appended). */
        val reactions: Map<String, List<String>>,
        /** actor pubkey -> created_at. */
        val reactionTimestamps: Map<String, Long>,
        /** Actors who reposted the note (Wisp keeps these in the same group). */
        val repostPubkeys: List<String>,
        val repostTimestamps: Map<String, Long>,
        override val latestTimestamp: Long,
    ) : NotifGroup

    data class ReplyGroup(
        override val groupId: String,
        val senderPubkey: String,
        val replyEventId: String,
        val referencedEventId: String,
        override val latestTimestamp: Long,
    ) : NotifGroup

    data class QuoteGroup(
        override val groupId: String,
        val senderPubkey: String,
        val quoteEventId: String,
        val referencedEventId: String,
        override val latestTimestamp: Long,
    ) : NotifGroup

    data class MentionGroup(
        override val groupId: String,
        val senderPubkey: String,
        val eventId: String,
        override val latestTimestamp: Long,
    ) : NotifGroup

    /** A NIP-88 poll vote on one of your polls. */
    data class VoteGroup(
        override val groupId: String,
        val senderPubkey: String,
        val voteEventId: String,
        val referencedEventId: String,
        override val latestTimestamp: Long,
    ) : NotifGroup
}

/**
 * A single, visible notification row (mirrors Wisp's FlatNotificationItem).
 *
 * Reactions and reposts reference your note via [referencedEventId] and carry
 * the reaction [emoji]; replies, mentions and quotes carry their own [note].
 *
 * Serializable so the last 24 hours can be cached in the local database and
 * older history can be loaded on demand.
 */
@Serializable
data class FlatNotifItem(
    val id: String,
    val type: NotifType,
    val actorPubkey: String,
    val timestamp: Long,
    val referencedEventId: String? = null,
    val replyEventId: String? = null,
    val quoteEventId: String? = null,
    val emoji: String? = null,
    /** The notification's own note (replies, mentions, quotes). */
    val note: Event? = null,
    /** NIP-88: option ids the user voted for (VOTE notifications). */
    val voteOptionIds: List<String> = emptyList(),
    /** Calendar events surface here like any other notification. */
    val calendarEventId: String? = null,
    val calendarTitle: String? = null,
)

/** 24h counts shown in the summary bar — mirrors Wisp's NotificationSummary. */
data class NotifSummary(
    val replyCount: Int = 0,
    val reactionCount: Int = 0,
    val repostCount: Int = 0,
    val mentionCount: Int = 0,
    val quoteCount: Int = 0,
    val voteCount: Int = 0,
    val calendarCount: Int = 0,
)
