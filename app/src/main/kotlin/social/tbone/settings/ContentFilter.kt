package social.tbone.settings

import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.isReply
import social.tbone.nostr.replyEventId
import social.tbone.nostr.quotedEventId
import social.tbone.nostr.threadRootEventId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** How one filter word matches note text. Chosen per word by the user. */
enum class WordMatchMode {
    /** Only the word on its own: "ass" does not match "classic" or "passage". */
    WHOLE_WORD,

    /** The character sequence anywhere, even inside other words (legacy behaviour). */
    ANYWHERE,
}

/** One user filter word together with its matching mode. */
data class FilterWord(
    val word: String,
    val mode: WordMatchMode = WordMatchMode.WHOLE_WORD,
)

/** What happens to notes carrying a NIP-36 content-warning tag. */
enum class NsfwMode {
    /** Content warnings are ignored; everything shows. */
    OFF,

    /** Warned notes stay in the timeline behind a tap-to-reveal veil. */
    COVER,

    /** Warned notes are removed entirely (the pre-veil behaviour). */
    HIDE,
}

/** Why a note was hidden — so the UI can say what happened instead of silence. */
sealed interface HideReason {
    data object BlockedAuthor : HideReason
    data class HideWord(val word: String) : HideReason
    data object ContentWarning : HideReason
}

/**
 * Content filtering applied to notes before they reach the UI:
 *  - BLOCK: notes from blocked pubkeys are removed entirely, along with replies
 *    to blocked authors when the parent event is unavailable locally. Blocking
 *    a person inherits through replies, reposts and quotes.
 *  - NSFW (NIP-36): governed by [NsfwMode] — covered behind a veil, hidden
 *    entirely, or ignored. Judged on the note itself only.
 *  - HIDE WORDS: notes whose own text matches a hidden word (per its
 *    [WordMatchMode]) are removed. Word matches do not inherit: a note that
 *    merely quotes a matching note stays, with the quote veiled/placeholdered.
 *  - BLEEP WORDS: notes stay visible but matching words are replaced with
 *    asterisks, honouring each word's match mode.
 */
object ContentFilter {

    fun shouldHide(
        event: Event,
        blockedPubkeys: Set<String>,
        nsfwMode: NsfwMode,
        hideWords: Set<FilterWord>,
        referencedEvents: Map<String, Event> = emptyMap(),
    ): Boolean = hideReason(event, blockedPubkeys, nsfwMode, hideWords, referencedEvents) != null

    /** The reason [event] is hidden, or null when it stays visible (veiled notes stay visible). */
    fun hideReason(
        event: Event,
        blockedPubkeys: Set<String>,
        nsfwMode: NsfwMode,
        hideWords: Set<FilterWord>,
        referencedEvents: Map<String, Event> = emptyMap(),
    ): HideReason? {
        directHideReason(event, blockedPubkeys, nsfwMode, hideWords)?.let { return it }
        if (blockedPubkeys.isEmpty()) return null
        // Only blocking a person inherits through the reference graph; word
        // and content-warning rules judge each note on its own text.
        return if (isBlockedByReference(event, blockedPubkeys, referencedEvents, mutableSetOf(event.id))) {
            HideReason.BlockedAuthor
        } else {
            null
        }
    }

    /** True when [event] should be shown behind a tap-to-reveal veil instead of openly. */
    fun shouldVeil(event: Event, nsfwMode: NsfwMode): Boolean =
        nsfwMode == NsfwMode.COVER && hasContentWarning(event)

    /** Rules that judge the note itself, with no reference inheritance. */
    private fun directHideReason(
        note: Event,
        blockedPubkeys: Set<String>,
        nsfwMode: NsfwMode,
        hideWords: Set<FilterWord>,
    ): HideReason? {
        if (note.pubkey in blockedPubkeys) return HideReason.BlockedAuthor
        if (nsfwMode == NsfwMode.HIDE && hasContentWarning(note)) return HideReason.ContentWarning
        if (hideWords.isNotEmpty()) {
            matchedHideWord(note.content, hideWords)?.let { return HideReason.HideWord(it) }
        }
        return null
    }

    /**
     * Blocked-author inheritance: repost/reply p-tags (which catch parents
     * that have not arrived locally), then recursion through referenced
     * parents. Content rules are deliberately not consulted here.
     */
    private fun isBlockedByReference(
        note: Event,
        blockedPubkeys: Set<String>,
        referencedEvents: Map<String, Event>,
        visited: MutableSet<String>,
    ): Boolean {
        val tags = note.parsedTags
        if ((note.kind == EventKind.REPOST || note.kind == 16) && tags.any { tag ->
                (tag.name == "p" || tag.name == "P") && tag.value()?.let { it in blockedPubkeys } == true
            }
        ) return true
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
            val parent = referencedEvents[parentId] ?: return@any false
            if (!visited.add(parent.id)) return@any false
            parent.pubkey in blockedPubkeys ||
                directHideReason(parent, blockedPubkeys, NsfwMode.OFF, emptySet()) == HideReason.BlockedAuthor ||
                isBlockedByReference(parent, blockedPubkeys, referencedEvents, visited)
        }
    }

    /** Replaces bleep words in text with asterisks, honouring each word's match mode. */
    fun bleep(text: String, bleepWords: Set<FilterWord>): String {
        if (bleepWords.isEmpty() || text.isEmpty()) return text
        val masked = BooleanArray(text.length)
        var any = false
        for (filterWord in bleepWords) {
            val word = filterWord.word.trim()
            if (word.isEmpty()) continue
            for (range in matchRanges(text, word, filterWord.mode)) {
                for (i in range) {
                    if (!masked[i]) { masked[i] = true; any = true }
                }
            }
        }
        if (!any) return text
        val chars = text.toCharArray()
        for (i in chars.indices) {
            if (masked[i] && !chars[i].isWhitespace()) chars[i] = '*'
        }
        return String(chars)
    }

    /** NIP-36 content-warning tag ("content-warning" with an optional reason). */
    fun contentWarningReason(event: Event): String? =
        event.parsedTags.firstOrNull { it.name == "content-warning" }?.value()

    fun hasContentWarning(event: Event): Boolean =
        event.parsedTags.any { it.name == "content-warning" }

    private fun matchedHideWord(text: String, words: Set<FilterWord>): String? =
        words.firstOrNull { filterWord ->
            val word = filterWord.word.trim()
            word.isNotEmpty() && matchRanges(text, word, filterWord.mode).any()
        }?.word?.trim()?.lowercase(Locale.ROOT)

    private fun matchRanges(text: String, word: String, mode: WordMatchMode): List<IntRange> =
        when (mode) {
            WordMatchMode.ANYWHERE -> {
                val lowerText = text.lowercase(Locale.ROOT)
                val lowerWord = word.lowercase(Locale.ROOT)
                buildList {
                    var from = 0
                    while (true) {
                        val idx = lowerText.indexOf(lowerWord, from)
                        if (idx < 0) break
                        add(idx until idx + lowerWord.length)
                        from = idx + lowerWord.length.coerceAtLeast(1)
                    }
                }
            }
            WordMatchMode.WHOLE_WORD -> wholeWordRegex(word).findAll(text).map { it.range }.toList()
        }

    private val regexCache = ConcurrentHashMap<String, Regex>()

    /**
     * Whole-word/phrase pattern: the match must not be flanked by letters,
     * digits or underscore on either side. Phrases get boundaries at their
     * ends only; the interior is literal.
     */
    private fun wholeWordRegex(word: String): Regex = regexCache.getOrPut(word.lowercase(Locale.ROOT)) {
        Regex(
            "(?<![\\p{L}\\p{N}_])" + Regex.escape(word) + "(?![\\p{L}\\p{N}_])",
            RegexOption.IGNORE_CASE,
        )
    }
}
