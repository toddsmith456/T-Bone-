package social.tbone.home

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One home-screen position.
 *
 * @property source what the tab reads.
 * @property visible hidden slots are kept, not deleted — a user who hides the
 *   clock feed for a week should not have to rebuild it on Sunday.
 */
@Serializable
data class FeedSlot(
    val source: FeedSource,
    /**
     * Stable identity, independent of [source].
     *
     * The UI keys its scroll state and its runtime subscriptions by this, so
     * renaming a tab or changing a relay URL must not be allowed to change the
     * identity — hence a separate stored id rather than one derived from the
     * source. Generated once, on first save.
     */
    val id: String,
    val visible: Boolean = true,
) {
    companion object {
        fun of(source: FeedSource, visible: Boolean = true): FeedSlot =
            FeedSlot(source = source, id = UUID.randomUUID().toString(), visible = visible)
    }
}

/**
 * The home tab layout: an ordered list of slots.
 *
 * This is the only thing persisted. Everything else — subscriptions, cached
 * events, loading state — is derived at runtime from this, which is what makes
 * a layout edit instant to apply and impossible to leave half-written on disk.
 *
 * @property lastActiveSlotId the tab to open on launch, or null to fall back to
 *   the first visible slot. Recorded on every tab switch.
 */
@Serializable
data class HomeLayout(
    val slots: List<FeedSlot>,
    val lastActiveSlotId: String? = null,
) {

    /** Visible slots, in display order — what the tab strip renders. */
    val visibleSlots: List<FeedSlot> get() = slots.filter { it.visible }

    /** The slot the app opens on. */
    val landingSlot: FeedSlot?
        get() = slots.firstOrNull { it.id == lastActiveSlotId && it.visible }
            ?: visibleSlots.firstOrNull()

    fun slot(id: String): FeedSlot? = slots.firstOrNull { it.id == id }

    fun indexOf(id: String): Int = slots.indexOfFirst { it.id == id }

    /**
     * Applies the invariants a stored layout must never violate.
     *
     * Two of them matter enough to live here rather than in the repository,
     * because they are consulted on read on every launch:
     *  1. slot 1 is always the follow feed and is always visible — the user's
     *     decision (3A) was that the app opens to follow activity, and a home
     *     screen with no feed in it is never acceptable;
     *  2. a layout with no visible slot un-hides the first one.
     */
    fun normalised(): HomeLayout {
        if (slots.isEmpty()) return default()

        val fixed = ArrayList<FeedSlot>(slots.size)
        // Invariant 1: exactly one Follows slot, always present, always first.
        val followsSlot = slots.firstOrNull { it.source is FeedSource.Follows }
        fixed += if (followsSlot == null) {
            FeedSlot.of(FeedSource.Follows())
        } else {
            followsSlot.copy(visible = true)
        }
        slots.filter { it !== followsSlot && it.source !is FeedSource.Follows }
            .forEach { fixed += it }

        // Invariant 2: never zero visible slots.
        val withVisible = if (fixed.none { it.visible }) {
            fixed.mapIndexed { index, slot -> if (index == 0) slot.copy(visible = true) else slot }
        } else {
            fixed
        }

        val validLanding = lastActiveSlotId?.takeIf { id ->
            withVisible.any { it.id == id && it.visible }
        }
        return HomeLayout(slots = withVisible, lastActiveSlotId = validLanding)
    }

    companion object {
        /** The four tabs the app ships with, in today's order. */
        fun default(): HomeLayout = HomeLayout(
            slots = listOf(
                FeedSlot.of(FeedSource.Follows()),
                FeedSlot.of(FeedSource.Clock()),
                FeedSlot.of(FeedSource.Suggested()),
                FeedSlot.of(FeedSource.Global()),
            ),
        )

        /** Maximum slots — the strip is adaptive but not unbounded. */
        const val MAX_SLOTS = 8
    }
}
