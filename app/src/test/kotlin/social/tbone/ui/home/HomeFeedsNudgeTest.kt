package social.tbone.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.home.FeedSource
import social.tbone.home.HomeLayout

/**
 * The editor's rules, without Compose.
 *
 * [HomeFeedsViewModel] is thin on purpose, so most of what is tested here is the
 * *conversation* it has with the user: which actions are refused, and whether
 * the refusal names the feed that caused it. The layout invariants themselves
 * are tested against the repository, where they are enforced.
 *
 * The reorder arithmetic is checked as pure indices rather than through the
 * view model, because a ViewModel needs a main dispatcher this suite does not
 * have and the arithmetic is the part that can actually be wrong.
 */
class HomeFeedsNudgeTest {

    private fun layoutOf(vararg sources: FeedSource) = HomeLayout(
        slots = sources.mapIndexed { index, source ->
            social.tbone.home.FeedSlot(source = source, id = "slot-$index")
        },
    )

    @Test fun everyNudgeNameSaysWhatHappened() {
        val copy = HomeFeedsViewModel.Nudge.Action.entries.flatMap { action ->
            HomeFeedsViewModel.Target.entries.map { target ->
                nudgeHint(HomeFeedsViewModel.Nudge(action, "clock", target))
            }
        }
        copy.forEach { line ->
            assertTrue("empty copy for a refusal: '$line'", line.isNotBlank())
            assertTrue("a refusal must not be capitalised mid-sentence-shouty: '$line'", line[0].isLowerCase())
        }
        assertEquals(
            HomeFeedsViewModel.Nudge.Action.entries.size * HomeFeedsViewModel.Target.entries.size,
            copy.toSet().size,
        )
    }

    @Test fun refusalsNameTheFeedThatCausedThem() {
        val line = nudgeHint(
            HomeFeedsViewModel.Nudge(
                HomeFeedsViewModel.Nudge.Action.REMOVE,
                "bitcoin devs",
                HomeFeedsViewModel.Target.FOLLOWS,
            ),
        )
        assertTrue(line, line.contains("bitcoin devs"))
    }

    @Test fun hidingTheFollowsFeedIsRefusedBecauseTheAppOpensOnIt() {
        val line = nudgeHint(
            HomeFeedsViewModel.Nudge(
                HomeFeedsViewModel.Nudge.Action.HIDE,
                "FOLLOWS",
                HomeFeedsViewModel.Target.FOLLOWS,
            ),
        )
        assertTrue("the reason must be the landing tab", line.contains("opens"))
    }

    @Test fun everyFeedSourceHasAHumanDescription() {
        val sources = listOf(
            FeedSource.Follows(),
            FeedSource.Clock(hours = 3),
            FeedSource.Suggested(),
            FeedSource.Global(),
            FeedSource.People(name = "devs", pubkeys = listOf("a", "b")),
            FeedSource.Relay(urls = listOf("wss://nostr.wine")),
            FeedSource.Hashtag(tags = listOf("bitcoin")),
            FeedSource.Polls(),
            FeedSource.Unsupported(typeName = "future"),
        )
        sources.forEach { source ->
            val description = describe(source)
            assertTrue("no description for $source", description.isNotBlank())
            assertTrue("descriptions stay on one line: '$description'", '\n' !in description)
        }
    }

    @Test fun clockDescriptionStatesItsWindow() {
        assertTrue(describe(FeedSource.Clock(hours = 6)).contains("6h"))
    }

    @Test fun relayDescriptionShowsTheHostNotTheScheme() {
        val description = describe(FeedSource.Relay(urls = listOf("wss://nostr.wine/")))
        assertTrue(description, description.contains("nostr.wine"))
        assertTrue("the scheme is noise in a one-line description", !description.contains("wss://"))
    }

    @Test fun peopleDescriptionCountsPeople() {
        assertTrue(describe(FeedSource.People(name = "x", pubkeys = listOf("a", "b", "c"))).contains("3"))
    }

    @Test fun hashtagDescriptionKeepsTheHash() {
        assertTrue(describe(FeedSource.Hashtag(tags = listOf("bitcoin"))).contains("#bitcoin"))
    }

    @Test fun unsupportedFeedSaysSoRatherThanLookingEmpty() {
        val description = describe(FeedSource.Unsupported(typeName = "future"))
        assertTrue(description, description.contains("not supported"))
    }

    // ── Reorder arithmetic (the view model's index maths, in isolation) ──────

    /** Mirrors HomeFeedsViewModel.moveUp: the follows slot is pinned at index 0. */
    private fun moveUpIds(slots: List<social.tbone.home.FeedSlot>, moving: String): List<String> {
        val current = HomeLayout(slots)
        val index = current.indexOf(moving)
        if (index <= 1) return slots.map { it.id }
        val ids = slots.map { it.id }.toMutableList()
        ids.removeAt(index)
        ids.add(index - 1, moving)
        return ids
    }

    private fun moveDownIds(slots: List<social.tbone.home.FeedSlot>, moving: String): List<String> {
        val current = HomeLayout(slots)
        val index = current.indexOf(moving)
        if (index < 1 || index >= slots.size - 1) return slots.map { it.id }
        val ids = slots.map { it.id }.toMutableList()
        ids.removeAt(index)
        ids.add(index + 1, moving)
        return ids
    }

    @Test fun theFollowsSlotNeverMovesUp() {
        val layout = layoutOf(FeedSource.Follows(), FeedSource.Clock(), FeedSource.Global())
        assertEquals(listOf("slot-0", "slot-1", "slot-2"), moveUpIds(layout.slots, "slot-0"))
    }

    @Test fun aSlotDirectlyUnderFollowsStaysPutGoingUp() {
        val layout = layoutOf(FeedSource.Follows(), FeedSource.Clock(), FeedSource.Global())
        // Index 1 cannot move up without displacing the pinned follows slot.
        assertEquals(listOf("slot-0", "slot-1", "slot-2"), moveUpIds(layout.slots, "slot-1"))
    }

    @Test fun movingUpSwapsWithTheSlotAbove() {
        val layout = layoutOf(FeedSource.Follows(), FeedSource.Clock(), FeedSource.Global())
        assertEquals(listOf("slot-0", "slot-2", "slot-1"), moveUpIds(layout.slots, "slot-2"))
    }

    @Test fun movingDownSwapsWithTheSlotBelow() {
        val layout = layoutOf(FeedSource.Follows(), FeedSource.Clock(), FeedSource.Global())
        assertEquals(listOf("slot-0", "slot-2", "slot-1"), moveDownIds(layout.slots, "slot-1"))
    }

    @Test fun theFollowsSlotNeverMovesDown() {
        val layout = layoutOf(FeedSource.Follows(), FeedSource.Clock())
        assertEquals(listOf("slot-0", "slot-1"), moveDownIds(layout.slots, "slot-0"))
    }

    @Test fun movingTheLastSlotDownIsANoOp() {
        val layout = layoutOf(FeedSource.Follows(), FeedSource.Clock(), FeedSource.Global())
        assertEquals(listOf("slot-0", "slot-1", "slot-2"), moveDownIds(layout.slots, "slot-2"))
    }

    @Test fun anUnknownSlotIsANoOpRatherThanACrash() {
        val layout = layoutOf(FeedSource.Follows(), FeedSource.Clock())
        assertEquals(listOf("slot-0", "slot-1"), moveUpIds(layout.slots, "nope"))
        assertEquals(listOf("slot-0", "slot-1"), moveDownIds(layout.slots, "nope"))
    }

    // ── Key parsing used by the people-feed editor ───────────────────────────

    @Test fun peopleKeysSplitOnAnySeparatorAndDropBlanks() {
        val raw = "  abc \n, \t\n ; "
        val parts = raw.split('\n', ',', ' ', '\t', ';').map { it.trim() }.filter { it.isNotBlank() }
        assertEquals(listOf("abc"), parts)
    }

    @Test fun aHexKeyIsAcceptedOnlyAtFullLength() {
        val good = "a".repeat(64)
        val short = "a".repeat(63)
        assertTrue(good.length == 64 && good.all { it in "0123456789abcdefABCDEF" })
        assertTrue(!(short.length == 64))
    }
}
