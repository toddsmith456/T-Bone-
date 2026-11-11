package social.tbone.lists

import social.tbone.nostr.Event
import social.tbone.nostr.EventKind

/**
 * NIP-51 bookmark lists, mapped onto the app's folder model.
 *
 * Online bookmarks are stored on the network, not on the device:
 *
 *  - **kind 10003** is the plain bookmark list. Its `e` tags are bookmarks that
 *    belong to no folder, which the UI shows as "Miscellaneous".
 *  - **kind 30003** is a bookmark *set* — a named folder (`d` tag = identifier,
 *    `title` tag = display name) whose `e` tags are the bookmarks inside it.
 *
 * Both are replaceable. Kind 10003 is addressable as `10003:<pubkey>:` and each
 * set as `30003:<pubkey>:<d>`.
 *
 * Everything here is pure so the tag mapping can be unit-tested without Room,
 * DataStore or a relay.
 */
object Nip51Bookmarks {

    /** The 10003 list's address. */
    fun mainAddress(pubkey: String): String = "$ADDRESS_PREFIX:${EventKind.BOOKMARKS}:$pubkey:"

    /** A 30003 set's address. */
    fun setAddress(pubkey: String, dTag: String): String =
        "$ADDRESS_PREFIX:${EventKind.BOOKMARK_SET}:$pubkey:$dTag"

    private const val ADDRESS_PREFIX = "a"

    /** A parsed remote folder: either the 10003 list or one 30003 set. */
    data class RemoteFolder(
        /** `d` tag for sets; empty for the plain 10003 list. */
        val dTag: String,
        /** Set title, or [MISCELLANEOUS] for the plain list. */
        val title: String,
        /** Bookmarked event ids, in event order. */
        val eventIds: List<String>,
        val createdAt: Long,
    ) {
        /** True for the plain 10003 list — the "Miscellaneous" bucket. */
        val isMiscellaneous: Boolean get() = title == MISCELLANEOUS

        companion object {
            /** Display name for bookmarks that are not in any folder. */
            const val MISCELLANEOUS = "Miscellaneous"
        }
    }

    /**
     * Reads a kind 10003 or 30003 event into a [RemoteFolder], or null when the
     * event is the wrong kind.
     *
     * Duplicate `e` tags collapse (a note can only be in a folder once), and
     * blank ids are dropped. A set with no `title` tag falls back to its `d`
     * identifier so a folder is never shown as an empty string.
     */
    fun folder(event: Event): RemoteFolder? {
        if (event.kind != EventKind.BOOKMARKS && event.kind != EventKind.BOOKMARK_SET) return null
        val tags = event.parsedTags
        val eventIds = tags.asSequence()
            .filter { it.name == "e" }
            .mapNotNull { it.value() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val dTag = if (event.kind == EventKind.BOOKMARK_SET) {
            tags.firstOrNull { it.name == "d" }?.value().orEmpty()
        } else {
            ""
        }
        val title = if (event.kind == EventKind.BOOKMARKS) {
            RemoteFolder.MISCELLANEOUS
        } else {
            tags.firstOrNull { it.name == "title" }?.value()?.takeIf { it.isNotBlank() }
                ?: dTag.ifBlank { RemoteFolder.MISCELLANEOUS }
        }
        return RemoteFolder(dTag = dTag, title = title, eventIds = eventIds, createdAt = event.createdAt)
    }

    /**
     * Builds the tag array for a set / the plain list.
     *
     * `e` tags carry the bookmarked event id plus a relay hint when one is known
     * (NIP-51 allows it and other clients use it to fetch the note). The `a`
     * "reference" tags some clients write are intentionally not produced: this
     * app only bookmarks events, and emitting `a` tags for ordinary notes makes
     * other clients show duplicates.
     */
    fun tags(
        kind: Int,
        title: String?,
        dTag: String?,
        bookmarks: List<Pair<String, String?>>,
    ): List<List<String>> {
        val out = mutableListOf<List<String>>()
        if (kind == EventKind.BOOKMARK_SET) {
            out += listOf("d", dTag.orEmpty())
            title?.takeIf { it.isNotBlank() }?.let { out += listOf("title", it) }
        }
        bookmarks.forEach { (id, relay) ->
            out += if (relay.isNullOrBlank()) listOf("e", id) else listOf("e", id, relay)
        }
        return out
    }

    /** Every id referenced by a folder event, for cache warming. */
    fun referencedIds(events: List<Event>): List<String> =
        events.asSequence()
            .mapNotNull(::folder)
            .flatMap { it.eventIds.asSequence() }
            .distinct()
            .toList()
}
