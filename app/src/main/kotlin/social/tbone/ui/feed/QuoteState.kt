package social.tbone.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Status of the referenced notes (quotes, reposts, inline `nostr:note1…`
 * links) that note cards are waiting on.
 *
 * `unresolved` holds the ids that were asked for and never arrived. Cards use
 * it to replace "loading quoted note…" with a terminal, actionable
 * "this note isn't available on any relay" row once every lookup has finished —
 * otherwise a note whose target is gone from every relay would load forever.
 *
 * Option 4: screens can pass [unresolvedFlow] instead of collecting the entire
 * set at the screen root, so only the placeholder whose own ID transitioned to
 * unresolved recomposes.
 *
 * `onRetry` optionally lets the user re-trigger a lookup for a specific ID
 * right from the placeholder row.
 */
@Immutable
data class QuoteState(
    val unresolved: Set<String> = emptySet(),
    val onRetry: ((String) -> Unit)? = null,
    val unresolvedFlow: StateFlow<Set<String>>? = null,
)

val LocalQuoteState = compositionLocalOf { QuoteState() }

/**
 * Narrowly observes whether [id] has transitioned to unresolved without
 * forcing the enclosing screen to collect the whole unresolved ID set.
 */
@Composable
fun QuoteState.isUnresolved(id: String?): Boolean {
    if (id == null) return false
    val flow = unresolvedFlow ?: return id in unresolved
    val missing by remember(flow, id) {
        flow.map { id in it }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = id in flow.value)
    return missing
}
