package social.tbone.ui.feed

import androidx.compose.runtime.compositionLocalOf

/**
 * Status of the referenced notes (quotes, reposts, inline `nostr:note1…`
 * links) that note cards are waiting on.
 *
 * `unresolved` holds the ids that were asked for and never arrived. Cards use
 * it to replace "loading quoted note…" with a terminal, actionable
 * "quoted note unavailable · open" once every lookup has finished — otherwise
 * a note whose target is gone from every relay would load forever.
 */
data class QuoteState(val unresolved: Set<String> = emptySet())

val LocalQuoteState = compositionLocalOf { QuoteState() }
