package social.tbone.toolbox.feeds

import social.tbone.nostr.Event
import java.util.concurrent.ConcurrentHashMap

/**
 * One open tab in the feed viewer.
 *
 * A viewer tab is deliberately *not* a [social.tbone.home.SlotRuntime]: a slot is
 * a saved position on the home screen and survives having its source changed,
 * while a viewer tab is a throwaway — you open it, read it, and it goes away when
 * you close it or leave the tool. Sharing the class would have meant one of the
 * two owning fields it does not use.
 *
 * What they do share is the important part: a tab owns its events and its
 * subscription ids, so two tabs cannot show each other's notes or tear down each
 * other's REQs.
 */
class FeedTabState(
    /** Stable for the life of the tab; used as the list key and the sub prefix. */
    val tabId: String,
    val source: FeedSource,
) {

    /** Sorted newest-first. Same ordering rule as the home feed. */
    val events: MutableSet<Event> = java.util.concurrent.ConcurrentSkipListSet(
        compareByDescending<Event> { it.createdAt }.thenBy { it.id },
    )

    /**
     * Events held until the tab settles.
     *
     * A relay feed answers in a burst. Rendering each note as it lands makes the
     * list jump under the reader's thumb; buffering until every filter has been
     * answered (or the settle timeout passes) makes it appear in one motion, the
     * same way the home feed does it.
     */
    val pending: MutableList<Event> = java.util.Collections.synchronizedList(mutableListOf())

    /** Subscription ids this tab owns. Nothing else may unsubscribe them. */
    val subIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile var settled: Boolean = false

    /** Filters this tab asked for, so "load older" can extend them. */
    @Volatile var lastFilters: List<social.tbone.nostr.Filter> = emptyList()

    /** The oldest event this tab has, used as the paging cursor. */
    val oldestAt: Long get() = events.lastOrNull()?.createdAt ?: 0L

    /** True when this tab can ask for older notes (a fixed window cannot). */
    @Volatile var canPageOlder: Boolean = false

    @Volatile var loadingOlder: Boolean = false

    /** Set when the planner refused the source; shown instead of a spinner. */
    @Volatile var blockedReason: String? = null

    /** The relays this tab talks to, when it is scoped to its own set. */
    @Volatile var relayScope: Set<String>? = null

    /** True when this tab reads the app's global relay set rather than the account's. */
    @Volatile var usesGlobalPool: Boolean = false

    /**
     * Which of this tab's subscriptions have been answered.
     *
     * A tab settles when every one of its REQs has had an EOSE — not when the
     * first one does, or half a chunked feed would appear with the rest still to
     * come — or when the settle timer expires, whichever happens first.
     */
    val eoseSubs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun buffer(event: Event) {
        if (settled) events.add(event) else pending.add(event)
    }

    fun flush() {
        synchronized(pending) {
            pending.forEach { events.add(it) }
            pending.clear()
        }
    }

    fun snapshot(limit: Int): List<Event> = events.asSequence().take(limit).toList()

    fun clear() {
        events.clear()
        synchronized(pending) { pending.clear() }
    }

    /** A short id prefix, so a log line names the tab a subscription belongs to. */
    fun label(): String = "viewer-${tabId.take(4)}"
}
