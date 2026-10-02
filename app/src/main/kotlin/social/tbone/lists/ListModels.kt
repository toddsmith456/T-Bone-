package social.tbone.lists

import kotlinx.serialization.Serializable

/**
 * The two lists that can be kept fully local ("offline lists").
 *
 *  - [FOLLOWS] mirrors the NIP-02 contact list (kind 3).
 *  - [MUTES]   mirrors the NIP-51 mute list (kind 10000). In the UI the mute
 *              list is surfaced as BLOCK / UNBLOCK.
 *
 * Both lists follow exactly the same rules, so everything below is generic
 * over [ListType].
 */
enum class ListType(
    /** Nostr event kind of the relay-side list this local list mirrors. */
    val kind: Int,
    /** Stable id used in storage keys, nav routes and file names. */
    val slug: String,
    /** Lower-case noun used in UI strings ("follow", "mute"). */
    val noun: String,
    /** Title used for screens ("offline follow list"). */
    val title: String,
    /** Name of the list itself, for sentences ("follow list", "block (mute) list"). */
    val listName: String,
    /** Plural for counts ("follows", "muted users"). */
    val plural: String,
    /** Extra kinds accepted when importing (e.g. NIP-51 follow sets). */
    val acceptedImportKinds: Set<Int>,
) {
    FOLLOWS(
        kind = 3,
        slug = "follows",
        noun = "follow",
        title = "offline follow list",
        listName = "follow list",
        plural = "follows",
        acceptedImportKinds = setOf(3, 30000),
    ),
    MUTES(
        kind = 10000,
        slug = "mutes",
        noun = "block",
        title = "offline block (mute) list",
        listName = "block (mute) list",
        plural = "blocked users",
        acceptedImportKinds = setOf(10000),
    );

    companion object {
        fun fromSlug(slug: String?): ListType? = entries.firstOrNull { it.slug == slug }

        /** Human name of a kind we recognise, for import error messages. */
        fun describeKind(kind: Int): String = when (kind) {
            3 -> "follow list (kind 3)"
            10000 -> "mute list (kind 10000)"
            30000 -> "follow set (kind 30000)"
            else -> "kind $kind event"
        }
    }
}

/**
 * One entry of a local list. Only the pubkey is required. The relay hint and
 * petname are NIP-02 extras that are preserved so a round trip through the
 * local list (or an export/import) never loses information.
 *
 * Deliberately NO profile picture / avatar URL here: offline lists never cache
 * profile pictures.
 */
@Serializable
data class ListEntry(
    val pubkey: String,
    val relay: String = "",
    val petname: String = "",
)

/**
 * Persisted state of one local list for ONE account.
 *
 * @property enabled          the on/off switch. While true the client uses
 *                            [entries] INSTEAD of the relay-backed list and never
 *                            reads or writes the relay list.
 * @property initialSyncDone  true once the one and only automatic sync has run
 *                            (or the user explicitly started from an empty list).
 *                            After this the list is never synced automatically.
 * @property lastSyncedAt     epoch seconds of the last online → offline sync (0 = never).
 */
@Serializable
data class LocalListState(
    val enabled: Boolean = false,
    val initialSyncDone: Boolean = false,
    val lastSyncedAt: Long = 0L,
    val entries: List<ListEntry> = emptyList(),
) {
    val size: Int get() = entries.size

    fun contains(pubkey: String): Boolean = entries.any { it.pubkey == pubkey }

    /** Set view used for fast filtering (feed / notifications). */
    fun pubkeySet(): Set<String> = entries.mapTo(LinkedHashSet(entries.size)) { it.pubkey }
}
