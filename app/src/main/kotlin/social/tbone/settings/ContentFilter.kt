package social.tbone.settings

import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.isReply
import social.tbone.nostr.replyEventId
import social.tbone.nostr.quotedEventId
import social.tbone.nostr.threadRootEventId
import java.util.Locale

/**
 * Content filtering applied to notes before they reach the UI:
 *  - BLOCK: notes from blocked pubkeys are removed entirely, along with replies
 *    to blocked authors when the parent event is unavailable locally.
 *  - NSFW (NIP-36): notes carrying a "content-warning" tag are hidden.
 *  - HIDE WORDS: notes containing any hidden word are removed entirely.
 *  - BLEEP WORDS: notes stay visible but the words are replaced with
 *    asterisks (word -> ****).
 */
object ContentFilter {

    /** True when this note, or a referenced parent/root note, should be hidden. */
    fun shouldHide(
        event: Event,
        blockedPubkeys: Set<String>,
        hideNsfw: Boolean,
        hideWords: Set<String>,
        referencedEvents: Map<String, Event> = emptyMap(),
    ): Boolean {
        if (blockedPubkeys.isEmpty() && !hideNsfw && hideWords.isEmpty()) return false
        val visited = mutableSetOf<String>()

        fun isDirectlyHidden(note: Event): Boolean {
            if (note.pubkey in blockedPubkeys) return true
            if (hideNsfw && hasContentWarning(note)) return true
            if (hideWords.isNotEmpty() && containsAny(note.content, hideWords)) return true
            return false
        }

        fun isHidden(note: Event): Boolean {
            if (!visited.add(note.id)) return false
            if (isDirectlyHidden(note)) return true

            val tags = note.parsedTags
            // NIP-18 reposts also identify the original author with a p-tag;
            // hide them when that author is blocked even if the target event
            // has not arrived in the local cache yet.
            if ((note.kind == EventKind.REPOST || note.kind == 16) && tags.any { tag ->
                    (tag.name == "p" || tag.name == "P") && tag.value()?.let { it in blockedPubkeys } == true
                }
            ) return true

            // A reply normally carries a p-tag for the author it responds to.
            // This catches replies to a blocked note even when the relay or local
            // cache has not supplied the parent event yet. p-tags on non-replies
            // are not used, so merely mentioning a blocked account is unaffected.
            if (tags.isReply && tags.any { tag ->
                    (tag.name == "p" || tag.name == "P") && tag.value()?.let { it in blockedPubkeys } == true
                }
            ) return true

            val parentIds = buildList {
                addAll(listOfNotNull(tags.replyEventId, tags.threadRootEventId, tags.quotedEventId))
                if (note.kind == EventKind.REPOST || note.kind == 16) {
                    tags.firstOrNull { it.name == "e" }?.value()?.let(::add)
                }
            }.asSequence()
                .filter { it.isNotBlank() && it != note.id }
                .distinct()
            return parentIds.any { parentId ->
                referencedEvents[parentId]?.let(::isHidden) == true
            }
        }

        return isHidden(event)
    }

    /** Replaces bleep words in text with asterisks of the same length. */
    fun bleep(text: String, bleepWords: Set<String>): String {
        if (bleepWords.isEmpty()) return text
        val lower = text.lowercase(Locale.ROOT)
        val builder = StringBuilder(text)
        bleepWords.forEach { word ->
            if (word.isBlank()) return@forEach
            val foldedWord = word.lowercase(Locale.ROOT)
            val mask = "*".repeat(word.length)
            var from = 0
            while (true) {
                val idx = lower.indexOf(foldedWord, from)
                if (idx < 0) break
                builder.replace(idx, idx + word.length, mask)
                from = idx + word.length
            }
        }
        return builder.toString()
    }

    /** NIP-36 content-warning tag ("content-warning" with an optional reason). */
    fun contentWarningReason(event: Event): String? =
        event.parsedTags.firstOrNull { it.name == "content-warning" }?.value()

    private fun hasContentWarning(event: Event): Boolean =
        event.parsedTags.any { it.name == "content-warning" }

    private fun containsAny(text: String, words: Set<String>): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return words.any { it.isNotBlank() && lower.contains(it.lowercase(Locale.ROOT)) }
    }
}
