package social.tbone.home

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the slot runtimes and decides what to subscribe and unsubscribe.
 *
 * Deliberately free of relays, coroutines and Android: it holds the map of slot
 * state and answers "what should happen next", while the ViewModel performs the
 * subscriptions. That boundary is the whole point — deciding which subscriptions
 * to tear down is the part that goes wrong quietly (leaked REQs, one slot
 * unsubscribing another's feed after an account switch), and this way it is
 * covered by ordinary unit tests.
 *
 * ### Two invariants it enforces
 *  1. **Only the active slot keeps subscriptions open.** Six live slots would
 *     mean six concurrent REQs per relay and six streams merging into one
 *     collector, on a client that already runs two relay pools.
 *  2. **No two slots share a subscription id.** Checked on every activation, so
 *     a bug that copies an id between slots fails loudly instead of silently
 *     starving a feed.
 */
@Singleton
class FeedSlotEngine @Inject constructor() {

    private val lock = Any()
    private val runtimes = LinkedHashMap<String, SlotRuntime>()

    /** The slot currently on screen, or null before the first activation. */
    @Volatile
    var activeSlotId: String? = null
        private set

    fun runtime(slotId: String): SlotRuntime? = synchronized(lock) { runtimes[slotId] }

    fun runtimeFor(source: FeedSource): SlotRuntime? = synchronized(lock) {
        runtimes.values.firstOrNull { it.source === source }
    }

    fun active(): SlotRuntime? = synchronized(lock) { activeSlotId?.let { runtimes[it] } }

    fun all(): List<SlotRuntime> = synchronized(lock) { runtimes.values.toList() }

    /**
     * Creates (or refreshes) the runtime for every slot in [layout].
     *
     * A slot keeps its runtime — and therefore its cached events — across layout
     * edits; only its source is refreshed, so renaming a tab or toggling another
     * slot's visibility does not throw away what is already loaded.
     */
    fun sync(layout: HomeLayout): List<SlotRuntime> = synchronized(lock) {
        val wanted = layout.slots.map { it.id }.toSet()
        runtimes.keys.toList().filterNot { it in wanted }.forEach { stale ->
            // A slot that left the layout must not keep a subscription alive.
            val runtime = runtimes.remove(stale)
            if (runtime != null && activeSlotId == stale) activeSlotId = null
        }
        layout.slots.forEach { slot ->
            val existing = runtimes[slot.id]
            if (existing == null) {
                runtimes[slot.id] = SlotRuntime(slotId = slot.id, source = slot.source)
            } else if (existing.source != slot.source) {
                // Source changed under a stable id: the cached events no longer
                // belong to this slot, so they go with it.
                existing.clear()
                existing.settled = false
                existing.loadedPubkey = null
                runtimes[slot.id] = SlotRuntime(slotId = slot.id, source = slot.source)
            }
        }
        runtimes.values.toList()
    }

    /**
     * What to do when [slotId] becomes the active slot.
     *
     * @param plan the exchange the planner produced for this slot right now.
     */
    fun activate(slotId: String, plan: FeedPlan): SlotChange = synchronized(lock) {
        val target = runtimes[slotId]
            ?: return SlotChange.None

        val previousId = activeSlotId
        val previous = previousId?.let { runtimes[it] }

        target.plan = plan
        if (plan is FeedPlan.Blocked) {
            // Even a blocked slot becomes the active slot: it is what the user
            // tapped, and its message is what the feed area should show.
            closeEverythingExceptLocked(slotId)
            activeSlotId = slotId
            previous?.let { if (it !== target) detach(it) }
            return SlotChange.Blocked(target, plan.reason)
        }

        // Same slot, already loaded for this plan: nothing to do.
        if (previousId == slotId && target.settled) {
            return SlotChange.None
        }

        val teardown = previous?.takeIf { it !== target }?.subIds?.toList().orEmpty()
        previous?.takeIf { it !== target }?.let { detach(it) }
        activeSlotId = slotId

        val closed = runtimes.values.filter { it !== target && it.subIds.isNotEmpty() }
        closed.forEach { if (it !== previous) detach(it) }

        target.assertExclusive(runtimes.values.toList())

        if (previousId == slotId) {
            SlotChange.Reload(target, plan)
        } else {
            SlotChange.Switch(teardown = teardown, load = target, plan = plan, closeSlots = closed)
        }
    }

    /** Marks a slot as loaded for [pubkey] and starts accepting live events. */
    fun markLoaded(slotId: String, pubkey: String, anchor: Long = 0L) = synchronized(lock) {
        val runtime = runtimes[slotId] ?: return
        runtime.loadedPubkey = pubkey
        runtime.anchor = anchor
        runtime.canPageOlder = (runtime.plan as? FeedPlan.Query)?.canPageOlder == true
    }

    fun markSettled(slotId: String) = synchronized(lock) {
        val runtime = runtimes[slotId] ?: return
        runtime.settled = true
        runtime.flush()
    }

    fun registerSub(slotId: String, subId: String) = synchronized(lock) {
        runtimes[slotId]?.subIds?.add(subId)
    }

    fun forgetSub(subId: String) = synchronized(lock) {
        runtimes.values.forEach { it.subIds.remove(subId) }
    }

    /** The slot that owns [subId], if any. */
    fun slotForSub(subId: String): SlotRuntime? = synchronized(lock) {
        runtimes.values.firstOrNull { subId in it.subIds }
    }

    /**
     * True when an account switch invalidates what is on screen.
     *
     * Every slot is reset rather than only the active one: a slot whose cached
     * contents came from the previous account would otherwise reappear with that
     * account's people as soon as it was tapped.
     */
    fun resetForAccountSwitch(): List<SlotRuntime> = synchronized(lock) {
        val snapshot = runtimes.values.toList()
        snapshot.forEach { runtime ->
            detach(runtime)
            runtime.clear()
            runtime.settled = false
            runtime.loadedPubkey = null
        }
        snapshot
    }

    private fun detach(runtime: SlotRuntime) {
        runtime.subIds.clear()
        runtime.settled = false
        runtime.eoseChunks.clear()
    }

    private fun closeEverythingExceptLocked(slotId: String) {
        runtimes.values.filter { it.slotId != slotId }.forEach { detach(it) }
    }
}
