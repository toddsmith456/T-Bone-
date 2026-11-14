package social.tbone.ui.feed

import androidx.compose.runtime.compositionLocalOf

/**
 * Status of the referenced notes (quotes, reposts, inline `nostr:note1…`
 * links) that note cards are waiting on.
 *
 * `unresolved` holds the ids that were asked for and never arrived. Cards use
 * it to replace "loading quoted note…" with a terminal, actionable
 * "this note isn't available on any relay" row once every lookup has finished —
 * otherwise a note whose target is gone from every relay would load forever.
 *
 * `onRetry` optionally lets the user re-trigger a lookup for a specific ID
 * right from the placeholder row.
 */
data class QuoteState(
    val unresolved: Set<String> = emptySet(),
    val onRetry: ((String) -> Unit)? = null,
)

val LocalQuoteState = compositionLocalOf { QuoteState() }
