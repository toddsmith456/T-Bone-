package social.tbone.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab strip's windowing rules, as arithmetic.
 *
 * The Composables themselves are not unit-testable here, but the three rules the
 * user stated are pure index maths and are exactly where they got broken before:
 *
 *  - only four feeds are ever on screen, whatever the layout holds;
 *  - the window never scrolls back past the first feed;
 *  - a compact arrow appears on whichever side still has a feed behind it.
 */
class TabWindowTest {

    /** Mirrors FeedTabStrip's `start` and arrow predicates. */
    private class Window(val count: Int) {
        val maxStart = (count - VISIBLE).coerceAtLeast(0)
        var start = 0
        fun clamp() { start = start.coerceIn(0, maxStart) }
        fun right() { start = (start + 1).coerceAtMost(maxStart) }
        fun left() { start = (start - 1).coerceAtLeast(0) }
        val showsLeftArrow get() = start > 0
        val showsRightArrow get() = start < maxStart
    }

    private companion object { const val VISIBLE = 4 }

    @Test fun fourOrFewerFeedsNeedNoArrows() {
        (1..4).forEach { count ->
            val window = Window(count)
            assertFalse("$count feeds should not need a left arrow", window.showsLeftArrow)
            assertFalse("$count feeds should not need a right arrow", window.showsRightArrow)
            assertEquals(0, window.maxStart)
        }
    }

    @Test fun aFifthFeedAddsOnlyARightArrow() {
        val window = Window(5)
        assertTrue("five feeds means one is hidden", window.showsRightArrow)
        assertFalse("nothing is hidden to the left at the start", window.showsLeftArrow)
        assertEquals("exactly one page of scrolling exists", 1, window.maxStart)
    }

    @Test fun eightFeedsNeedFourPagesOfOne() {
        val window = Window(8)
        assertEquals(4, window.maxStart)
    }

    @Test fun theWindowNeverGoesBackPastTheFirstFeed() {
        val window = Window(6)
        repeat(10) { window.left() }
        assertEquals("the strip must not scroll backwards from its starting point", 0, window.start)
        assertFalse(window.showsLeftArrow)
    }

    @Test fun theWindowNeverGoesPastTheLastFeed() {
        val window = Window(6)
        repeat(10) { window.right() }
        assertEquals(2, window.start)
        assertFalse("no arrow points at nothing", window.showsRightArrow)
    }

    @Test fun arrowsAppearAndDisappearAtTheEnds() {
        val window = Window(6)
        assertFalse(window.showsLeftArrow)
        assertTrue(window.showsRightArrow)
        window.right()
        assertTrue("both directions available mid-strip", window.showsLeftArrow && window.showsRightArrow)
        window.right()
        assertTrue(window.showsLeftArrow)
        assertFalse("at the end there is nothing further right", window.showsRightArrow)
    }

    @Test fun removingFeedsClampsTheWindowRatherThanShowingA() {
        // A layout edit can shrink the list while the window is scrolled right.
        // Without the clamp the strip would render an empty window.
        val window = Window(8)
        window.start = 4
        val shrunk = Window(4).also { it.start = 4 }
        shrunk.clamp()
        assertEquals(0, shrunk.start)
    }

    @Test fun selectingATabOffWindowBringsItIntoView() {
        // Mirrors the LaunchedEffect: tapping a tab that is not visible (it can
        // happen when the app restores a remembered tab) moves the window.
        fun desiredStart(index: Int, current: Int, count: Int): Int {
            val maxStart = (count - VISIBLE).coerceAtLeast(0)
            val start = current.coerceIn(0, maxStart)
            return when {
                index < start -> index
                index > start + VISIBLE - 1 -> index - VISIBLE + 1
                else -> start
            }.coerceIn(0, maxStart)
        }
        assertEquals("a tab to the left pulls the window left", 0, desiredStart(0, 2, 8))
        // 8 feeds -> maxStart 4, so index 7 puts the window at 4, not 5: the last
        // page is always full rather than showing three feeds and a gap.
        assertEquals("a tab to the right pushes it right", 4, desiredStart(7, 2, 8))
        assertEquals("a visible tab does not move the window", 2, desiredStart(3, 2, 8))
        assertEquals("the last page is not overshot", 4, desiredStart(7, 4, 9))
    }

    @Test fun everyTabIsReachableFromTheFirstPage() {
        // Whatever the layout size, stepping right repeatedly must be able to
        // reach the last feed — otherwise a feed would be permanently hidden.
        (5..8).forEach { count ->
            val window = Window(count)
            repeat(count) { window.right() }
            val lastVisible = window.start + VISIBLE - 1
            assertTrue(
                "$count feeds: last feed $lastVisible unreachable",
                lastVisible >= count - 1,
            )
        }
    }

    @Test fun theArrowIsNarrowerThanATabSoItCostsAlmostNothing() {
        // The arrow is a bare 16 dp glyph column, 8 dp of outer padding either
        // side. Even with an arrow on BOTH sides (only possible mid-strip) a tab
        // on a 360 dp screen keeps 78 dp rather than the ~64 dp a 28 dp button
        // would leave, and on a 411 dp phone it keeps 90 dp.
        listOf(360, 411).forEach { screen ->
            val outerPadding = 16
            val arrow = 16
            val noArrows = (screen - outerPadding) / VISIBLE
            val oneArrow = (screen - outerPadding - arrow) / VISIBLE
            val bothArrows = (screen - outerPadding - arrow * 2) / VISIBLE
            assertTrue("$screen dp: one arrow costs <= 4 dp a tab", noArrows - oneArrow <= 4)
            assertTrue("$screen dp: both arrows cost <= 8 dp a tab", noArrows - bothArrows <= 8)
            assertTrue("$screen dp: a tab keeps at least 78 dp", bothArrows >= 78)
        }
    }
}
