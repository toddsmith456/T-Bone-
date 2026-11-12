package social.tbone.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One configured home-tab feed.
 *
 * A *source* is a saved, first-class object: "bitcoin devs", "relay: nostr.wine",
 * "last hour". Slots point at sources, so the same source can own a home tab and
 * also appear in the feeds tool, and renaming a tab never touches its config.
 *
 * ### Why typed, not raw JSON
 * A `Query` with an arbitrary map of filter keys would let one malformed layout
 * — restored from a backup, or hand-edited — take the app down on launch. Every
 * query field is therefore a typed field with a known default, and the decoder
 * ([HomeLayoutCodec]) is tolerant of anything it does not recognise.
 *
 * ### Why the original four are `data class`es with no fields (mostly)
 * They exist so a layout can say "this slot is the clock feed" without the UI
 * depending on an enum. The enum-backed ones are what `HomeLayout.default()`
 * ships, so a layout that has never been edited is byte-for-byte the four tabs
 * the app has today.
 */
@Serializable
sealed interface FeedSource {

    /** What the tab strip shows. Short by design — see the adaptive strip. */
    val label: String

    // ── The four feeds that exist today ──────────────────────────────────────

    /** The account's follow feed — today's HOME tab. Slots may not replace it. */
    @Serializable
    @SerialName("follows")
    data class Follows(override val label: String = "FOLLOWS") : FeedSource

    /** A rolling time window, today's CLOCK tab. */
    @Serializable
    @SerialName("clock")
    data class Clock(
        /** Window length in hours, 1..24 — matches the existing hour strip. */
        val hours: Int = 6,
        override val label: String = "CLOCK",
    ) : FeedSource

    /** The curated author set shipped with the app — today's SUGGESTED tab. */
    @Serializable
    @SerialName("suggested")
    data class Suggested(override val label: String = "SUGGESTED") : FeedSource

    /** Every relay in the global pool — today's GLOBAL tab. */
    @Serializable
    @SerialName("global")
    data class Global(override val label: String = "GLOBAL") : FeedSource

    // ── The feeds the tool adds ──────────────────────────────────────────────

    /**
     * A hand-picked group of people as a feed.
     *
     * [source] records where the list came from so it can be re-resolved later;
     * for a NIP-51 set the [dTag] is kept so a refresh re-reads the published
     * set rather than freezing a stale copy.
     */
    @Serializable
    @SerialName("people")
    data class People(
        val name: String,
        val pubkeys: List<String>,
        val origin: PeopleOrigin = PeopleOrigin.MANUAL,
        val dTag: String? = null,
        override val label: String = name,
    ) : FeedSource

    /** One or more relays read as a timeline. */
    @Serializable
    @SerialName("relay")
    data class Relay(
        val urls: List<String>,
        /** Empty means everyone on those relays. */
        val authors: List<String> = emptyList(),
        override val label: String = urls.firstOrNull()?.let(::shortRelayName) ?: "RELAY",
    ) : FeedSource

    /** Notes carrying any of these hashtags. */
    @Serializable
    @SerialName("hashtag")
    data class Hashtag(
        val tags: List<String>,
        override val label: String = tags.firstOrNull()?.let { "#$it" } ?: "TAG",
    ) : FeedSource

    /** NIP-88 polls. */
    @Serializable
    @SerialName("polls")
    data class Polls(
        /** Empty means polls from the follow set. */
        val authors: List<String> = emptyList(),
        override val label: String = "POLLS",
    ) : FeedSource

    /**
     * A slot whose source this build does not understand — a feed type added by
     * a newer version, or a name that was mistyped in a hand-edited export.
     *
     * It is rendered as an inert placeholder instead of being deleted, because
     * dropping it would silently destroy part of somebody's layout (and their
     * config would not come back on an upgrade).
     */
    @Serializable
    @SerialName("unsupported")
    data class Unsupported(
        val typeName: String,
        override val label: String = "FEED",
    ) : FeedSource
}

/** How a [FeedSource.People] list was built. */
@Serializable
enum class PeopleOrigin { MANUAL, NIP51_SET, FOLLOW_LIST, IMPORTED }

/** `"wss://nostr.wine/"` → `"nostr.wine"` — fits the tab strip. */
internal fun shortRelayName(url: String): String {
    val noScheme = url.substringAfter("://", url)
    val host = noScheme.substringBefore('/').substringBefore(':')
    return host.ifBlank { url }
}
