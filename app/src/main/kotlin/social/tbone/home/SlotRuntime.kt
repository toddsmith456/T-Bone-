package social.tbone.home

import social.tbone.home.FeedPlanner
import social.tbone.nostr.Event
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime state for one home slot.
 *
 * ### Why this exists
 * The four built-in feeds were written as four loaders that each owned fields
 * directly on `FeedViewModel`: `clockAuthors`, `suggestedSubIds`,
 * `globalVisibleLimit`, `feedSubId`, `feedSettled`… That worked only because a
 * tab and its loader were the same thing. Once a slot's contents come from a
 * stored layout, two slots can legitimately be the *same source type* — "last
 * hour" and "last day" as two clock feeds — and they would then be writing the
 * same fields and tearing each other's subscriptions down.
 *
 * Everything that is per-feed therefore lives here, keyed by slot id. Anything
 * shared (the message collector, quote resolution, profiles, content filtering)
 * stays on the ViewModel, because those are genuinely ViewModel-wide concerns.
 */
class SlotRuntime(
    val slotId: String,
    val source: FeedSource,
) {

    /** The exchange the planner produced for this slot. */
    @Volatile
    var plan: FeedPlan? = null

    /**
     * Subscriptions owned by this slot.
     *
     * Ownership is exclusive: [assertExclusive] enforces at runtime that no two
     * slots hold the same subscription id, because a leaked or double-owned REQ
     * is invisible until a relay starts rate-limiting.
     */
    val subIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Events for this slot, in insertion order.
     *
     * Per slot rather than shared: a note in the clock feed must not appear in a
     * relay feed just because both are loaded.
     */
    val events: MutableSet<Event> = java.util.concurrent.ConcurrentSkipListSet(
        compareByDescending<Event> { it.createdAt }.thenBy { it.id },
    )

    /**
     * Events held back until the slot settles, so the list snaps into place
     * rather than trickling in one note at a time.
     */
    val pending: MutableList<Event> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile var settled: Boolean = false

    /** The account this slot's current contents were loaded for. */
    @Volatile var loadedPubkey: String? = null

    /** Clock feeds: the epoch second the window starts at. */
    @Volatile var anchor: Long = 0L

    /** True once the slot may page to older events. */
    @Volatile var canPageOlder: Boolean = false

    /** Suggested feeds: how many author chunks are outstanding. */
    @Volatile var chunkCount: Int = 0
    val eoseChunks: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val isClock: Boolean get() = source is FeedSource.Clock
    val isGlobal: Boolean get() = source is FeedSource.Global
    val isSuggested: Boolean get() = source is FeedSource.Suggested
    val isFollows: Boolean get() = source is FeedSource.Follows

    /** A message for a slot that is still collecting should be buffered. */
    fun buffer(event: Event) {
        if (settled) events.add(event) else pending.add(event)
    }

    /** Moves everything buffered into the visible set. */
    fun flush() {
        synchronized(pending) {
            pending.forEach { events.add(it) }
            pending.clear()
        }
    }

    /** Newest-first snapshot, capped so a long-lived slot cannot grow forever. */
    fun snapshot(limit: Int): List<Event> =
        events.asSequence().take(limit).toList()

    fun clear() {
        events.clear()
        synchronized(pending) { pending.clear() }
    }

    /**
     * True when this runtime still belongs to the given account.
     *
     * The existing loaders guard against showing a previous account's follow
     * graph by comparing the pubkey a feed was loaded for; that guard now lives
     * per slot, so a layout with six slots cannot leak one account's people into
     * another's.
     */
    fun belongsTo(pubkey: String?): Boolean = loadedPubkey == pubkey

    internal fun assertExclusive(all: Collection<SlotRuntime>) {
        val shared = all.filter { it !== this }.flatMap { it.subIds }.toSet()
        val overlap = subIds.intersect(shared)
        check(overlap.isEmpty()) {
            "home slot $slotId shares subscription ids with another slot: $overlap"
        }
    }
}

/**
 * What the ViewModel should do when the active slot changes.
 *
 * Returning this instead of performing the work keeps [FeedSlotEngine] free of
 * relay and coroutine dependencies, so the hard part — deciding which
 * subscriptions to tear down and which to open — is unit-testable.
 */
sealed interface SlotChange {

    /** The active slot changed: tear down [teardown], then load [load]. */
    data class Switch(
        val teardown: List<String>,
        val load: SlotRuntime,
        val plan: FeedPlan,
        /** Slots whose subscriptions must also be closed (hidden/removed). */
        val closeSlots: List<SlotRuntime> = emptyList(),
    ) : SlotChange

    /** Same slot, but the plan changed (e.g. the clock window moved). */
    data class Reload(val runtime: SlotRuntime, val plan: FeedPlan) : SlotChange

    /** Nothing to do — the slot is already active and settled. */
    data object None : SlotChange

    /** The slot cannot load; [reason] is shown to the user. */
    data class Blocked(val runtime: SlotRuntime, val reason: String) : SlotChange
}
