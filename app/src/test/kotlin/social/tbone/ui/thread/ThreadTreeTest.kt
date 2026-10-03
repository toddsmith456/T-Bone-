package social.tbone.ui.thread

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Event

/**
 * Ordering/threading rules for the thread screen. These are the behaviours that
 * used to be wrong (flat chronological list, hallucinated gap, wrong parent).
 */
class ThreadTreeTest {

    private fun id(n: String) = n.padEnd(64, '0').take(64)

    private fun event(
        name: String,
        createdAt: Long,
        parent: String? = null,
        root: String? = null,
        quote: String? = null,
        mention: String? = null,
    ): Event {
        val tags = mutableListOf<JsonArray>()
        if (root != null) tags += tags("e", id(root), "", "root")
        if (parent != null) tags += tags("e", id(parent), "", "reply")
        if (mention != null) tags += tags("e", id(mention), "", "mention")
        if (quote != null) tags += tags("q", id(quote))
        return Event(
            id = id(name),
            pubkey = id("a") ,
            createdAt = createdAt,
            kind = 1,
            tags = tags,
            content = "",
            sig = "00",
        )
    }

    private fun tags(vararg values: String): JsonArray =
        JsonArray(values.map { JsonPrimitive(it) })

    private fun noteIds(items: List<ThreadItem>) = items
        .filterIsInstance<ThreadItem.Note>()
        .map { it.event.id }

    @Test fun rootFirstThenRepliesDepthFirst() {
        val root = event("root", 10)
        val a = event("a", 20, parent = "root")
        val a1 = event("a1", 21, parent = "a")
        val b = event("b", 30, parent = "root")

        val items = ThreadTree.build(
            rootId = id("root"),
            events = listOf(root, a, a1, b),
        )

        // Depth-first: a1 comes before b even though b is newer.
        assertEquals(listOf(id("root"), id("a"), id("a1"), id("b")), noteIds(items))
        assertEquals(listOf(0, 1, 2, 1), items.filterIsInstance<ThreadItem.Note>().map { it.depth })
    }

    @Test fun siblingsAreChronological() {
        val root = event("root", 10)
        val older = event("older", 20, parent = "root")
        val newer = event("newer", 30, parent = "root")

        val items = ThreadTree.build(id("root"), listOf(newer, root, older))
        assertEquals(listOf(id("root"), id("older"), id("newer")), noteIds(items))
    }

    @Test fun orphanIsReparentedToRootInsteadOfDropped() {
        val root = event("root", 10)
        // "missing" parent was never delivered by any relay.
        val orphan = event("orphan", 20, parent = "missing")

        val items = ThreadTree.build(id("root"), listOf(root, orphan))
        assertEquals(listOf(id("root"), id("orphan")), noteIds(items))
    }

    @Test fun focusedNoteIsHighlightedAndCountsDescendants() {
        val root = event("root", 10)
        val a = event("a", 20, parent = "root")
        val a1 = event("a1", 21, parent = "a")
        val a2 = event("a2", 22, parent = "a")

        val items = ThreadTree.build(
            rootId = id("root"),
            events = listOf(root, a, a1, a2),
            focusedId = id("a"),
        )
        val notes = items.filterIsInstance<ThreadItem.Note>()
        val aItem = notes.first { it.event.id == id("a") }
        assertTrue(aItem.highlighted)
        assertEquals(2, aItem.descendantCount)
        // root → a → (a1, a2)
        assertEquals(3, notes.first { it.event.id == id("root") }.descendantCount)
    }

    @Test fun deepBranchFoldsBehindShowMoreRow() {
        val root = event("root", 10)
        var parent = "root"
        val chain = mutableListOf(root)
        // Build a chain deeper than DEPTH_CAP + 1.
        listOf("d1", "d2", "d3", "d4", "d5").forEachIndexed { index, name ->
            chain += event(name, 20L + index, parent = parent)
            parent = name
        }

        val items = ThreadTree.build(
            rootId = id("root"),
            events = chain,
            scrollTargetId = id("root"),
        )

        val depths = items.filterIsInstance<ThreadItem.Note>().map { it.depth }
        assertTrue("depth must be capped", depths.max() <= ThreadTree.DEPTH_CAP)
        assertTrue(items.any { it is ThreadItem.FoldedReplies })
    }

    @Test fun pathToFocusedNoteIsNeverFolded() {
        val root = event("root", 10)
        var parent = "root"
        val chain = mutableListOf(root)
        listOf("d1", "d2", "d3", "d4").forEachIndexed { index, name ->
            chain += event(name, 20L + index, parent = parent)
            parent = name
        }

        val items = ThreadTree.build(
            rootId = id("root"),
            events = chain,
            focusedId = id("d4"),
            scrollTargetId = id("d4"),
        )

        // The opened note and its ancestors stay visible, however deep.
        assertTrue(noteIds(items).contains(id("d4")))
        assertTrue(noteIds(items).containsAll(listOf(id("d2"), id("d3"))))
    }

    @Test fun highFanOutShowsMoreRow() {
        val root = event("root", 10)
        val replies = (1..8).map { event("r$it", 20L + it, parent = "root") }

        val items = ThreadTree.build(id("root"), listOf(root) + replies)
        val showMore = items.filterIsInstance<ThreadItem.ShowMoreReplies>().single()
        assertEquals(8 - ThreadTree.MAX_SIBLINGS_INLINE, showMore.hiddenCount)
        assertEquals(
            ThreadTree.MAX_SIBLINGS_INLINE + 1, // root + visible replies
            items.filterIsInstance<ThreadItem.Note>().size,
        )
    }

    @Test fun expandingFanOutRevealsEveryReply() {
        val root = event("root", 10)
        val replies = (1..8).map { event("r$it", 20L + it, parent = "root") }

        val items = ThreadTree.build(
            rootId = id("root"),
            events = listOf(root) + replies,
            expandedFanOut = setOf(id("root")),
        )
        assertEquals(9, items.filterIsInstance<ThreadItem.Note>().size)
        assertFalse(items.any { it is ThreadItem.ShowMoreReplies })
    }

    @Test fun missingRootRendersTopMostNotesAsRoots() {
        val a = event("a", 20)
        val a1 = event("a1", 21, parent = "a")

        val items = ThreadTree.build(rootId = id("gone"), events = listOf(a, a1))
        assertEquals(listOf(id("a"), id("a1")), noteIds(items))
        assertEquals(listOf(0, 1), items.filterIsInstance<ThreadItem.Note>().map { it.depth })
    }

    @Test fun missingRootStillRendersRepliesWhoseParentWasNotDelivered() {
        val reply = event("reply", 20, parent = "gone")
        val nested = event("nested", 21, parent = "reply")

        val items = ThreadTree.build(rootId = id("gone"), events = listOf(reply, nested))
        assertEquals(listOf(id("reply"), id("nested")), noteIds(items))
        assertEquals(listOf(0, 1), items.filterIsInstance<ThreadItem.Note>().map { it.depth })
    }

    @Test fun cyclesDoNotHang() {
        val a = Event(
            id = id("a"), pubkey = id("b"), createdAt = 1, kind = 1, sig = "00", content = "",
            tags = listOf(tags("e", id("b"), "", "reply")),
        )
        val b = Event(
            id = id("b"), pubkey = id("a"), createdAt = 2, kind = 1, sig = "00", content = "",
            tags = listOf(tags("e", id("a"), "", "reply")),
        )

        val items = ThreadTree.build(rootId = id("a"), events = listOf(a, b))
        // Exactly one of the two is rendered as a child; nothing recurses forever.
        assertTrue(noteIds(items).size <= 2)
    }

    @Test fun midAirConnectorsAreMarked() {
        val root = event("root", 10)
        val a = event("a", 20, parent = "root")
        val a1 = event("a1", 21, parent = "a")
        val b = event("b", 30, parent = "root")

        val notes = ThreadTree.build(id("root"), listOf(root, a, a1, b))
            .filterIsInstance<ThreadItem.Note>()

        // The flag means "no row at this same depth directly above", so the rail
        // has to start with a dash instead of pretending to continue.
        assertFalse(notes[0].connectorStartsMidAir) // root: no rail at all
        assertTrue(notes[1].connectorStartsMidAir)  // a  (depth 1, arrives from depth 0)
        assertTrue(notes[2].connectorStartsMidAir)  // a1 (depth 2, arrives from depth 1)
        assertTrue(notes[3].connectorStartsMidAir)  // b  (depth 1, arrives from depth 2)
    }

    /**
     * The thread screen must show the whole conversation at once — no
     * "show more replies" rows, however deep or wide the thread is. This is how
     * ThreadViewModel builds the tree.
     */
    @Test fun threadScreenRendersEverythingExpanded() {
        val root = event("root", 10)
        // Zero-padded names: ids must stay distinct, otherwise this test would
        // be checking a malformed thread instead of a wide/deep one.
        val wide = (1..25).map { index -> event("w%02d".format(index), 20L + index, parent = "root") }
        var parent = "root"
        val deep = (1..12).map { index ->
            event("d%02d".format(index), 100L + index, parent = parent).also { parent = it.id }
        }

        val items = ThreadTree.build(
            rootId = id("root"),
            events = listOf(root) + wide + deep,
            focusedId = id("root"),
            scrollTargetId = id("root"),
            maxSiblingsInline = Int.MAX_VALUE,
            depthCap = Int.MAX_VALUE,
        )

        assertEquals(1 + wide.size + deep.size, items.filterIsInstance<ThreadItem.Note>().size)
        assertTrue(items.none { it is ThreadItem.ShowMoreReplies })
        assertTrue(items.none { it is ThreadItem.FoldedReplies })
    }

    @Test fun quotedNoteIsNotTreatedAsAReply() {
        val root = event("root", 10)
        val quoted = event("quoted", 20, parent = "root", quote = "target")
        val plain = event("plain", 30, mention = "target")

        val items = ThreadTree.build(id("root"), listOf(root, quoted, plain))
        // "plain" carries only a mention e-tag, so it must not join the thread.
        assertEquals(listOf(id("root"), id("quoted")), noteIds(items))
    }
}
