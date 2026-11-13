package social.tbone.feed

import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter

/**
 * Small rules shared by every timeline in the app.
 *
 * This file exists because the feed viewer tool would otherwise be the third
 * copy of "ask for kind 0 for these authors" and the second copy of "is this
 * kind allowed to appear in a timeline". Copies drift; these do not.
 */
object TimelineSupport {

    /** Metadata requests are cheap but a relay will still throttle a 1,000-author REQ. */
    private const val MAX_AUTHORS_PER_METADATA_REQUEST = 150

    /**
     * One kind-0 request per chunk of [pubkeys], for names and avatars.
     *
     * Callers pass authors they have just seen in a timeline; the profile
     * repository deduplicates and ignores older metadata, so over-asking is
     * harmless but asking for everybody in one REQ is not — relays drop it.
     */
    fun metadataFilters(pubkeys: Collection<String>, limit: Int = MAX_AUTHORS_PER_METADATA_REQUEST): List<Filter> =
        pubkeys.asSequence()
            .filter { it.length == 64 }
            .distinct()
            .chunked(MAX_AUTHORS_PER_METADATA_REQUEST.coerceAtMost(limit.coerceAtLeast(1)))
            .map { Filter(authors = it, kinds = listOf(EventKind.METADATA), limit = it.size) }
            .toList()

    /**
     * Kinds that are structural rather than content, and therefore never appear
     * as a timeline row no matter what a feed asked for.
     *
     * Kept here rather than in the planner so the tool and the home feeds cannot
     * disagree about what "a note" is.
     */
    private val STRUCTURAL_KINDS = setOf(
        EventKind.METADATA,
        EventKind.FOLLOW_LIST,
        EventKind.RELAY_LIST,
    )

    /** True when [event] is something a timeline may show. */
    fun isTimelineContent(event: Event): Boolean =
        event.kind !in STRUCTURAL_KINDS && !EventKind.isPrivateMessaging(event.kind)

    /**
     * True when a source may show this event at all.
     *
     * Content warnings are *not* handled here: the app covers or hides warned
     * notes through the shared content filter that every timeline already runs,
     * and doing it twice would mean a note the user tapped to reveal disappeared
     * again on the next refresh. What this does reject is the one thing no feed
     * may ever show — private messaging events, which the home feed also refuses
     * at ingest.
     */
    fun admitsContent(event: Event): Boolean = isTimelineContent(event)
}
