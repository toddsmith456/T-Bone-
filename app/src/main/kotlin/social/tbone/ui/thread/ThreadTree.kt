package social.tbone.ui.thread

import social.tbone.nostr.Event
import social.tbone.nostr.replyEventId
import social.tbone.nostr.threadRootEventId

/**
 * One row of a rendered thread.
 *
 * Replies are **not** a flat chronological list: the thread is rebuilt as a
 * parent→children tree and flattened depth-first, so every reply is followed
 * immediately by its own replies. That is what turns a wall of timestamped
 * notes into readable "mini threads" where it stays obvious who is answering
 * whom.
 */
sealed interface ThreadItem {
    /** Nesting level; 0 is the thread root. Drives the UI indent. */
    val depth: Int
    val key: Any

    /**
     * A note row.
     *
     * @param descendantCount total replies below this note (excluding itself) —
     *   the "+N replies" affordance and the fold label are built from it.
     * @param highlighted the note the user opened (always kept expanded).
     * @param folded true when this note's own subtree is collapsed into the row.
     * @param connectorStartsMidAir true when this row's depth rail has no
     *   same-depth row directly above it, so the rail's top must be dashed or it
     *   looks like it starts in mid-air.
     */
    data class Note(
        val event: Event,
        override val depth: Int,
        val descendantCount: Int = 0,
        val highlighted: Boolean = false,
        val folded: Boolean = false,
        val connectorStartsMidAir: Boolean = false,
    ) : ThreadItem {
        override val key: Any get() = event.id
    }

    /** A folded subtree under [anchorId]; tapping expands it inline. */
    data class FoldedReplies(
        val anchorId: String,
        override val depth: Int,
        val hiddenCount: Int,
    ) : ThreadItem {
        override val key: Any get() = "folded_$anchorId"
    }

    /** Extra siblings under [parentId] that are hidden by the fan-out cap. */
    data class ShowMoreReplies(
        val parentId: String,
        override val depth: Int,
        val hiddenCount: Int,
    ) : ThreadItem {
        override val key: Any get() = "more_$parentId"
    }
}

/**
 * Builds and flattens a reply tree. Pure and side-effect free, so the ordering
 * rules are unit-testable without Android or a relay.
 *
 * Ordering rules:
 *  - the root is depth 0, its replies depth 1, and so on;
 *  - siblings are chronological (oldest first);
 *  - **depth-first**: a reply's whole subtree is emitted before the next
 *    sibling, which is what groups the conversation into mini threads;
 *  - replies whose parent is unknown are re-parented to the root rather than
 *    dropped (relays often return a subtree without the middle);
 *  - cycles are guarded, so a malformed thread can never hang the UI.
 *
 * Progressive disclosure keeps long threads legible:
 *  - branches deeper than [DEPTH_CAP] fold behind [ThreadItem.FoldedReplies];
 *  - a parent with more than [maxSiblingsInline] direct replies shows the first
 *    few plus [ThreadItem.ShowMoreReplies].
 *  - [expandedIds]/[expandedFanOut] override both, and the [scrollTargetId]
 *    path is always exempt so a note you opened is never hidden.
 */
object ThreadTree {

    /** Replies at depths deeper than this fold behind a "+N replies" row. */
    const val DEPTH_CAP = 3

    /** Direct replies shown inline under one parent before "show more". */
    const val MAX_SIBLINGS_INLINE = 4

    /**
     * @param rootId the thread root. When it is absent from [events] the
     *   highest notes we do have are rendered as depth-0 roots, so a thread
     *   still opens when a relay never returned the root.
     * @param events every known event of the thread (ancestors, the focused
     *   note and replies) in any order.
     * @param focusedId the note the user opened; highlighted and never folded.
     */
    fun build(
        rootId: String?,
        events: Collection<Event>,
        focusedId: String? = null,
        collapsedIds: Set<String> = emptySet(),
        expandedIds: Set<String> = emptySet(),
        expandedFanOut: Set<String> = emptySet(),
        scrollTargetId: String? = null,
        maxSiblingsInline: Int = MAX_SIBLINGS_INLINE,
        depthCap: Int = DEPTH_CAP,
    ): List<ThreadItem> {
        val byId = events.associateBy { it.id }
        if (byId.isEmpty()) return emptyList()

        val root = rootId?.let { byId[it] }
        val tree = parentToChildren(byId, root?.id ?: rootId)

        val subtreeSizes = subtreeSizes(tree)
        val protectedPath = scrollTargetId
            ?.let { ancestorsPlusSelf(it, tree) }
            .orEmpty() + setOfNotNull(focusedId)

        val out = mutableListOf<ThreadItem>()
        val visited = HashSet<String>()

        if (root != null) {
            visited += root.id
            out += ThreadItem.Note(
                event = root,
                depth = 0,
                descendantCount = subtreeSizes[root.id] ?: 0,
                highlighted = root.id == focusedId,
            )
            walk(
                parent = root, parentDepth = 0, tree = tree, sizes = subtreeSizes,
                collapsedIds = collapsedIds, expandedIds = expandedIds,
                expandedFanOut = expandedFanOut, protectedPath = protectedPath,
                focusedId = focusedId, visited = visited, out = out,
                maxSiblingsInline = maxSiblingsInline, depthCap = depthCap,
                insideExpanded = false,
            )
        } else {
            // The declared root never arrived. A reply whose parent is missing
            // is still a top-most note that we can render; looking only at the
            // children map used to hide these events because they were grouped
            // under the missing parent key. Build roots from the events'
            // declared parents instead, then fall back to every event for a
            // malformed cycle so nothing silently disappears.
            val roots = byId.values
                .filter { event ->
                    val parent = event.parsedTags.replyEventId
                        ?: event.parsedTags.threadRootEventId
                    parent == null || parent !in byId
                }
                .ifEmpty { byId.values.toList() }
                .sortedWith(compareBy<Event> { it.createdAt }.thenBy { it.id })

            for (candidate in roots) {
                if (candidate.id in visited) continue
                visited += candidate.id
                out += ThreadItem.Note(
                    event = candidate,
                    depth = 0,
                    descendantCount = subtreeSizes[candidate.id] ?: 0,
                    highlighted = candidate.id == focusedId,
                )
                walk(
                    parent = candidate, parentDepth = 0, tree = tree, sizes = subtreeSizes,
                    collapsedIds = collapsedIds, expandedIds = expandedIds,
                    expandedFanOut = expandedFanOut, protectedPath = protectedPath,
                    focusedId = focusedId, visited = visited, out = out,
                    maxSiblingsInline = maxSiblingsInline, depthCap = depthCap,
                    insideExpanded = false,
                )
            }
        }

        return markMidAirConnectors(out)
    }

    /** Children of every parent, sorted oldest-first. */
    private fun parentToChildren(byId: Map<String, Event>, rootId: String?): Map<String, List<Event>> {
        val map = HashMap<String, MutableList<Event>>()
        for (event in byId.values) {
            if (event.id == rootId) continue
            // NIP-22 comments (kind 1111) carry their parent in the lowercase
            // "e" tag just like a reply, with the thread root in uppercase "E".
            val declaredParent = event.parsedTags.replyEventId
                ?: event.parsedTags.threadRootEventId
            // A note with no parent reference at all is not part of this thread
            // (e.g. a note that merely mentions one of its notes) — it used to be
            // glued onto the root, showing unrelated notes as replies.
            if (declaredParent == null) continue

            // Fall back to the root when the real parent is missing, so a reply
            // is never lost just because a relay omitted its parent.
            val parent = if (declaredParent in byId) declaredParent else rootId
            if (parent == null || parent == event.id) continue
            map.getOrPut(parent) { mutableListOf() }.add(event)
        }
        map.values.forEach { it.sortBy { e -> e.createdAt } }
        return map
    }

    private fun walk(
        parent: Event,
        parentDepth: Int,
        tree: Map<String, List<Event>>,
        sizes: Map<String, Int>,
        collapsedIds: Set<String>,
        expandedIds: Set<String>,
        expandedFanOut: Set<String>,
        protectedPath: Set<String>,
        focusedId: String?,
        visited: MutableSet<String>,
        out: MutableList<ThreadItem>,
        maxSiblingsInline: Int,
        depthCap: Int,
        insideExpanded: Boolean,
    ) {
        val children = tree[parent.id] ?: return
        val childDepth = parentDepth + 1
        val capped = children.size > maxSiblingsInline && parent.id !in expandedFanOut
        val visible = if (capped) children.take(maxSiblingsInline) else children

        for (child in visible) {
            if (child.id in visited) continue
            visited += child.id

            val isProtected = child.id in protectedPath
            val folded = child.id in collapsedIds && !isProtected
            val hasChildren = !tree[child.id].isNullOrEmpty()
            val capHere = childDepth >= depthCap &&
                hasChildren &&
                !isProtected &&
                !insideExpanded

            out += ThreadItem.Note(
                event = child,
                depth = childDepth,
                descendantCount = sizes[child.id] ?: 0,
                highlighted = child.id == focusedId,
                folded = folded || capHere,
            )

            when {
                folded || capHere ->
                    // Subtree hidden; the row itself carries "+N replies".
                    if (hasChildren && (sizes[child.id] ?: 0) > 0) {
                        out += ThreadItem.FoldedReplies(
                            anchorId = child.id,
                            depth = childDepth + 1,
                            hiddenCount = sizes[child.id] ?: 0,
                        )
                    }
                else -> walk(
                    parent = child, parentDepth = childDepth, tree = tree, sizes = sizes,
                    collapsedIds = collapsedIds, expandedIds = expandedIds,
                    expandedFanOut = expandedFanOut, protectedPath = protectedPath,
                    focusedId = focusedId, visited = visited, out = out,
                    maxSiblingsInline = maxSiblingsInline, depthCap = depthCap,
                    insideExpanded = insideExpanded || child.id in expandedIds,
                )
            }
        }

        if (capped) {
            out += ThreadItem.ShowMoreReplies(
                parentId = parent.id,
                depth = childDepth,
                hiddenCount = children.size - maxSiblingsInline,
            )
        }
    }

    /** Total descendants of each note (excluding itself), cycle-guarded. */
    private fun subtreeSizes(tree: Map<String, List<Event>>): Map<String, Int> {
        val sizes = HashMap<String, Int>()
        val visiting = HashSet<String>()

        fun sizeOf(id: String): Int {
            sizes[id]?.let { return it }
            if (!visiting.add(id)) return 0
            var total = 0
            for (child in tree[id].orEmpty()) total += 1 + sizeOf(child.id)
            visiting.remove(id)
            sizes[id] = total
            return total
        }

        tree.keys.forEach { sizeOf(it) }
        return sizes
    }

    /** [targetId] plus every ancestor of it — the branch that must stay open. */
    private fun ancestorsPlusSelf(targetId: String, tree: Map<String, List<Event>>): Set<String> {
        val childToParent = HashMap<String, String>()
        for ((parentId, children) in tree) {
            for (child in children) childToParent.putIfAbsent(child.id, parentId)
        }
        val result = LinkedHashSet<String>()
        var current: String? = targetId
        while (current != null && result.add(current)) current = childToParent[current]
        return result
    }

    /**
     * Dashes the top of a depth rail when the row above it is not a note at the
     * same depth — otherwise the vertical guide appears to float.
     */
    private fun markMidAirConnectors(items: List<ThreadItem>): List<ThreadItem> {
        var previousDepth = -1
        return items.map { item ->
            if (item !is ThreadItem.Note) return@map item
            val midAir = item.depth > 0 && previousDepth != item.depth
            previousDepth = item.depth
            if (midAir == item.connectorStartsMidAir) item else item.copy(connectorStartsMidAir = midAir)
        }
    }

    private fun <T> setOfNotNull(value: T?): Set<T> = if (value == null) emptySet() else setOf(value)
}
