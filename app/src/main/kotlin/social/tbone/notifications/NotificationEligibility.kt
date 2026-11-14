package social.tbone.notifications

import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Nip57
import social.tbone.nostr.Nip88
import social.tbone.nostr.nip10Marker
import social.tbone.nostr.replyEventId
import social.tbone.ui.feed.extractInlineQuoteIds

/** How an event relates to the account owner, if it does at all. */
internal enum class NotificationClass { REACTION, REPOST, REPLY, QUOTE, MENTION, VOTE, ZAP }

/**
 * The single eligibility gate every notification must pass, no matter which
 * subscription or code path delivered the event.
 *
 * Relays are untrusted: a relay may ignore the `#p` tag filter (and the
 * `until` window) and return arbitrary events for a notifications query.
 * Trusting the relay is what filled notification tabs with strangers' posts
 * labelled as "mentions" — posts that mentioned nobody. An event only
 * becomes a notification here when the event itself proves it involves
 * [me]:
 *
 *  - reactions / reposts / poll votes: the referenced event is mine;
 *  - quotes: a quoted event (q-tag or inline `nostr:` quote) is mine;
 *  - replies: the event p-tags me, or its reply parent is my event;
 *  - mentions: the event p-tags me, or it references one of my events;
 *  - zaps: a kind 9735 receipt whose `p` tag is me, whose sender (from the
 *    embedded zap request) is somebody else.
 *
 * [isOwnEvent] decides whether an event id belongs to [me]; callers resolve
 * it from the own-event set plus the local event cache. Events I authored
 * never notify.
 */
internal fun classifyNotification(
    event: Event,
    me: String,
    isOwnEvent: (String) -> Boolean,
): NotificationClass? {
    if (me.isBlank() || event.pubkey == me) return null
    val hasPTag = event.parsedTags.any { it.name == "p" && it.value() == me }
    return when (event.kind) {
        EventKind.REACTION, EventKind.REPOST -> {
            val refId = event.parsedTags.lastOrNull { it.name == "e" }?.value()
            when {
                refId == null || !isOwnEvent(refId) -> null
                event.kind == EventKind.REACTION -> NotificationClass.REACTION
                else -> NotificationClass.REPOST
            }
        }
        EventKind.POLL_RESPONSE -> {
            val pollId = Nip88.getPollEventId(event)
            if (pollId != null && isOwnEvent(pollId)) NotificationClass.VOTE else null
        }
        EventKind.ZAP_RECEIPT -> {
            // A receipt proves a zap happened, but only the recipient's own
            // account may be notified by it: relays happily return receipts for
            // other people, and the receipt's author is the *LN node*, so the
            // usual "author != me" test above says nothing about relevance.
            val receipt = Nip57.receipt(event)
            when {
                receipt == null -> null
                receipt.recipient != me -> null
                // Ignore zaps the user sent to themselves (self-zaps are a way to
                // move sats, not an interaction worth notifying about).
                receipt.sender?.equals(me, ignoreCase = true) == true -> null
                else -> NotificationClass.ZAP
            }
        }
        EventKind.TEXT_NOTE, EventKind.COMMENT, EventKind.POLL -> {
            val quoteIds = notificationQuoteIds(event)
            val replyId = event.parsedTags.replyEventId
            when {
                quoteIds.any(isOwnEvent) -> NotificationClass.QUOTE
                replyId != null && (hasPTag || isOwnEvent(replyId)) -> NotificationClass.REPLY
                hasPTag || notificationMentionedOwnIds(event).any(isOwnEvent) ->
                    NotificationClass.MENTION
                else -> null
            }
        }
        else -> null
    }
}

/** Event ids this event quotes, via q-tags or inline `nostr:` references. */
internal fun notificationQuoteIds(event: Event): List<String> =
    (event.parsedTags.filter { it.name == "q" }.mapNotNull { it.value() } +
        extractInlineQuoteIds(event.content)).distinct()

/** All event ids this event references via e/q/E tags. */
internal fun notificationReferencedIds(event: Event): List<String> =
    event.parsedTags
        .filter { it.name == "e" || it.name == "q" || it.name == "E" }
        .mapNotNull { it.value() }
        .distinct()

/**
 * Own-event ids this event points at with an explicit NIP-10 "mention"
 * e-tag. Thread-structure e-tags (root/reply) do NOT count: a reply deep
 * in a thread rooted at my note, written to someone else and tagging
 * nobody, is not a mention of me.
 */
internal fun notificationMentionedOwnIds(event: Event): List<String> =
    event.parsedTags
        .filter { it.name == "e" && it.nip10Marker() == "mention" }
        .mapNotNull { it.value() }
        .distinct()
