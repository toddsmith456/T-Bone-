package social.tbone.home

import social.tbone.account.Account
import social.tbone.feed.SuggestedFollows
import social.tbone.lists.ListType
import social.tbone.lists.OfflineListRepository
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.relay.RelayPool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a slot needs from the relay layer, expressed as data.
 *
 * Deliberately contains no subscription: the planner decides *what* to ask for
 * and *where*, and the ViewModel decides when to ask. That split is what makes
 * this file testable without a relay, an Android context, or a ViewModel — and
 * it is where all the accumulated quirks of the four existing feeds are now
 * written down in one place instead of four.
 */
sealed interface FeedPlan {

    /** @property filters one REQ per filter, all merged into one timeline. */
    data class Query(
        val filters: List<Filter>,
        val pool: PoolChoice,
        val canPageOlder: Boolean,
        /** Clock feeds only: the epoch second the window starts at. */
        val anchor: Long? = null,
        /** The author set that produced these filters, for the empty states. */
        val authors: List<String> = emptyList(),
    ) : FeedPlan

    /** Nothing to ask for, and why. Rendered as a message, never a spinner. */
    data class Blocked(val reason: String) : FeedPlan
}

/** Which relay pool a slot talks to. */
enum class PoolChoice {
    /** The account's own relays. */
    PERSONAL,

    /** The app's global relay set, on the separate global pool. */
    GLOBAL,

    /** A specific list of relays — a relay feed. */
    SPECIFIC,
}

/**
 * Turns a [FeedSource] into a [FeedPlan].
 *
 * The four existing feeds are reproduced exactly as they are today: the follow
 * set comes from the offline list when its switch is on (otherwise the relay
 * list), the clock feed is anchored `now - hours`, the suggested set is chunked
 * because relays reject a 1,300-author REQ, and global is a plain kinds query
 * against the global pool.
 */
@Singleton
class FeedPlanner @Inject constructor(
    private val offlineLists: OfflineListRepository,
    private val suggested: SuggestedFollows,
) {

    /**
     * @param now epoch seconds, injected so the clock anchor is testable.
     * @param globalRelays the enabled global relay set, for [FeedSource.Global].
     */
    suspend fun plan(
        source: FeedSource,
        account: Account,
        now: Long = System.currentTimeMillis() / 1000,
        globalRelays: Set<String> = emptySet(),
    ): FeedPlan = when (source) {
        is FeedSource.Follows -> planFollows(account, now)
        is FeedSource.Clock -> planClock(account, source.hours, now)
        is FeedSource.Suggested -> planSuggested(now)
        is FeedSource.Global -> planGlobal(globalRelays)
        is FeedSource.People -> planPeople(source, now)
        is FeedSource.Relay -> planRelay(source, now)
        is FeedSource.Hashtag -> planHashtag(source, now)
        is FeedSource.Polls -> planPolls(source, account, now)
        is FeedSource.Unsupported -> FeedPlan.Blocked(
            "This feed was created by a newer version of T-Bone and cannot be shown here.",
        )
    }

    // ── The four shipped feeds ───────────────────────────────────────────────

    private suspend fun planFollows(account: Account, now: Long): FeedPlan {
        val follows = followSet(account)
        if (follows.isEmpty()) {
            return FeedPlan.Blocked("Follow some people to fill this feed.")
        }
        return FeedPlan.Query(
            filters = listOf(
                Filter(authors = follows, kinds = FEED_KINDS, limit = FEED_PAGE_SIZE),
            ),
            pool = PoolChoice.PERSONAL,
            canPageOlder = false,
            authors = follows,
        )
    }

    private suspend fun planClock(account: Account, hours: Int, now: Long): FeedPlan {
        val follows = followSet(account)
        if (follows.isEmpty()) {
            return FeedPlan.Blocked("Follow some people to fill this feed.")
        }
        val anchor = now - hours.coerceIn(1, 24) * 3600L
        return FeedPlan.Query(
            filters = listOf(
                // No `since`: the window is enforced by `until` alone, exactly as
                // the existing clock feed does, so rewinding 6h -> 24h keeps the
                // notes already on screen instead of re-fetching them.
                Filter(authors = follows, kinds = FEED_KINDS, until = anchor, limit = CLOCK_PAGE_SIZE),
            ),
            pool = PoolChoice.PERSONAL,
            canPageOlder = false,
            anchor = anchor,
            authors = follows,
        )
    }

    private fun planSuggested(now: Long): FeedPlan {
        val chunks = suggested.chunks()
        if (chunks.isEmpty()) {
            return FeedPlan.Blocked("The suggested account list is unavailable.")
        }
        // Enough per chunk to fill a page across all of them, capped so one chunk
        // cannot dominate the merged timeline. Same arithmetic as the current feed.
        val perChunk = ((FEED_PAGE_SIZE.toFloat() / chunks.size).toInt() + 8).coerceIn(12, 40)
        return FeedPlan.Query(
            filters = chunks.map { chunk -> Filter(authors = chunk, kinds = FEED_KINDS, limit = perChunk) },
            pool = PoolChoice.PERSONAL,
            canPageOlder = false,
            authors = suggested.pubkeys,
        )
    }

    private fun planGlobal(globalRelays: Set<String>): FeedPlan {
        if (globalRelays.isEmpty()) {
            return FeedPlan.Blocked("No Global relays are enabled. Configure Global relays to load this feed.")
        }
        return FeedPlan.Query(
            filters = listOf(Filter(kinds = FEED_KINDS, limit = FEED_PAGE_SIZE)),
            pool = PoolChoice.GLOBAL,
            canPageOlder = true,
            authors = emptyList(),
        )
    }

    // ── The tool feeds ───────────────────────────────────────────────────────

    private fun planPeople(source: FeedSource.People, now: Long): FeedPlan {
        val authors = source.pubkeys.mapNotNull { normaliseHex(it) }.distinct()
        if (authors.isEmpty()) {
            return FeedPlan.Blocked("This feed has no valid people in it yet.")
        }
        return FeedPlan.Query(
            filters = listOf(
                Filter(authors = authors.take(MAX_AUTHORS_PER_FILTER), kinds = FEED_KINDS, limit = FEED_PAGE_SIZE),
            ),
            pool = PoolChoice.PERSONAL,
            canPageOlder = true,
            authors = authors,
        )
    }

    private fun planRelay(source: FeedSource.Relay, now: Long): FeedPlan {
        val relays = source.urls
            .map { RelayPool.normalize(it) }
            .filter { RelayPool.isValidRelayUrl(it) }
            .distinct()
        if (relays.isEmpty()) {
            return FeedPlan.Blocked("This feed has no valid relay address.")
        }
        val authors = source.authors.mapNotNull { normaliseHex(it) }.distinct()
        return FeedPlan.Query(
            filters = listOf(
                Filter(
                    authors = authors.takeIf { it.isNotEmpty() },
                    kinds = FEED_KINDS,
                    limit = FEED_PAGE_SIZE,
                ),
            ),
            pool = PoolChoice.SPECIFIC,
            canPageOlder = true,
            authors = authors,
        )
    }

    private fun planHashtag(source: FeedSource.Hashtag, now: Long): FeedPlan {
        val tags = source.tags.map { it.trim().removePrefix("#").lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (tags.isEmpty()) {
            return FeedPlan.Blocked("This feed has no hashtag yet.")
        }
        return FeedPlan.Query(
            filters = listOf(
                Filter(kinds = FEED_KINDS, tTags = tags, limit = FEED_PAGE_SIZE),
            ),
            pool = PoolChoice.PERSONAL,
            canPageOlder = true,
            authors = emptyList(),
        )
    }

    private suspend fun planPolls(source: FeedSource.Polls, account: Account, now: Long): FeedPlan {
        val authors = source.authors.mapNotNull { normaliseHex(it) }.ifEmpty { followSet(account) }
        if (authors.isEmpty()) {
            return FeedPlan.Blocked("Follow some people, or pick a group, to see polls.")
        }
        return FeedPlan.Query(
            filters = listOf(
                Filter(authors = authors.take(MAX_AUTHORS_PER_FILTER), kinds = listOf(EventKind.POLL), limit = FEED_PAGE_SIZE),
            ),
            pool = PoolChoice.PERSONAL,
            canPageOlder = true,
            authors = authors,
        )
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    /**
     * The effective follow set.
     *
     * The offline list wins when its switch is on, matching the home feed — and
     * that rule now applies to people and poll feeds too, so a locally-muted
     * relationship cannot leak back in through a different slot.
     */
    private suspend fun followSet(account: Account): List<String> {
        val local = offlineLists.snapshot(ListType.FOLLOWS, account.pubkey)
        val follows = if (local.enabled) local.pubkeySet().toList() else account.follows
        return (follows + account.pubkey).distinct()
    }

    private fun normaliseHex(value: String): String? {
        val trimmed = value.trim().lowercase()
        return trimmed.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
    }

    companion object {
        /** Kinds every timeline shows — identical to the current `FEED_KINDS`. */
        val FEED_KINDS = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL)

        /** Matches `MAX_FEED_EVENTS` on the existing feed. */
        const val FEED_PAGE_SIZE = 100

        /** Matches `CLOCK_PAGE_SIZE` on the existing clock feed. */
        const val CLOCK_PAGE_SIZE = 100

        /**
         * Relays cap how many authors one filter may carry. The suggested set is
         * chunked at 150 for this reason; user-built people feeds get the same cap.
         */
        const val MAX_AUTHORS_PER_FILTER = 150

    }
}
