package social.tbone.feed

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The curated author set behind the T-Bone suggested feed.
 *
 * The list is a plain text asset (`assets/tbone-suggested-follows.txt`): one
 * 64-char hex pubkey per line, `#` comments allowed. It comes from a kind-3
 * follow list of the accounts that are actually posting, so the feed shows a
 * cross-section of live activity rather than an arbitrary relay sample.
 *
 * A single REQ carrying 1,300+ authors is rejected or truncated by most relays
 * (the message alone is ~90 kB), so the set is queried in [chunkSize] slices.
 * Slices are merged back into one timeline in [social.tbone.ui.feed.FeedViewModel].
 */
@Singleton
class SuggestedFollows @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val ASSET = "tbone-suggested-follows.txt"

        /** Authors per relay filter. 150 × 66 chars ≈ 10 kB — accepted everywhere. */
        const val CHUNK_SIZE = 150

        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** Parses the asset; tolerant of comments, blank lines and CRLF. */
        fun parse(text: String): List<String> =
            text.lineSequence()
                .map { it.substringBefore('#').trim().lowercase() }
                .filter { HEX64.matches(it) }
                .distinct()
                .toList()

        /** Splits a parsed list into relay-sized filters. */
        fun chunk(pubkeys: List<String>, size: Int = CHUNK_SIZE): List<List<String>> =
            if (pubkeys.isEmpty()) emptyList() else pubkeys.chunked(size)
    }

    val pubkeys: List<String> by lazy {
        runCatching {
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                parse(reader.readText())
            }
        }.onFailure { Timber.w(it, "suggested follows asset missing") }
            .getOrDefault(emptyList())
    }

    /** The set split into relay-sized filters. */
    fun chunks(size: Int = CHUNK_SIZE): List<List<String>> = chunk(pubkeys, size)

    val size: Int get() = pubkeys.size
}
