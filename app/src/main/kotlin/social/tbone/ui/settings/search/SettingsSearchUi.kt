package social.tbone.ui.settings.search

import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import social.tbone.settings.search.SettingsCatalog
import social.tbone.settings.search.SettingsGroup
import social.tbone.settings.search.SettingsHit
import social.tbone.settings.search.SettingsSearch as SettingsSearchIndex
import social.tbone.settings.search.SettingsSections
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appCornerShape

/**
 * Search state for the screen currently composing.
 *
 * `null` means "this screen does not take part in search yet" — anchors then
 * render everything, so screens can adopt anchors without ever hiding rows.
 */
val LocalSettingsScreenSearch = staticCompositionLocalOf<SettingsScreenSearchState?> { null }

/** Anchor a jump from another screen should scroll to and highlight. */
val LocalSettingsJumpAnchor = compositionLocalOf<String?> { null }

/**
 * Result of a search on the screen that owns it.
 *
 * [query] is the live text; [hits] is every hit across Settings and its
 * sub-screens, in rank order. Screens ask [sectionVisible] while composing, so
 * filtering stays synchronous and section-level; [matchedIds] tells the result
 * list which rows a hit points at.
 */
class SettingsScreenSearchState(
    val query: String,
    val hits: List<SettingsHit>,
) {
    val isActive: Boolean get() = query.isNotBlank()

    /** Catalog ids that matched on this screen. */
    val matchedIds: Set<String> = hits.map { it.entry.id }.toSet()

    fun sectionVisible(sectionId: String): Boolean =
        query.isBlank() || SettingsSections.visible(query, sectionId)

    /** True when the live query has a hit for this catalog row. */
    fun rowMatches(entryId: String): Boolean = !isActive || matchedIds.contains(entryId)

    /** Hits that live on a different screen: the "found elsewhere" list. */
    fun elsewhere(group: SettingsGroup): List<SettingsHit> =
        if (!isActive) emptyList() else hits.filter { it.entry.group != group }.take(4)
}

@Composable
fun rememberSettingsScreenSearch(query: String, limit: Int = Int.MAX_VALUE): SettingsScreenSearchState {
    val hits = remember(query) {
        if (query.isBlank()) emptyList() else SettingsSearchIndex.search(query, limit = limit)
    }
    return remember(query, hits) { SettingsScreenSearchState(query = query, hits = hits) }
}

/** Shared search field. */
@Composable
fun SettingsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search settings",
) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
        shape = appCornerShape(androidx.compose.material3.MaterialTheme.shapes.medium),
        placeholder = { Text(placeholder, color = BonyColors.TextMute) },
        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = "Search settings", tint = BonyColors.TextMute) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Clear, contentDescription = "Clear settings search", tint = BonyColors.TextMute)
                }
            }
        },
    )
}

/**
 * Results from other settings screens. Tapping one navigates there and hands
 * the query over so the target can scroll to and flash the matched row.
 */
@Composable
fun SettingsElsewhereResults(
    state: SettingsScreenSearchState,
    group: SettingsGroup,
    onOpen: (SettingsHit, String) -> Unit,
) {
    val elsewhere = state.elsewhere(group)
    if (elsewhere.isEmpty()) return
    Text(
        text = "FOUND ELSEWHERE",
        style = BonyType.caption.copy(color = BonyColors.TextMute),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
    elsewhere.forEach { hit ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(hit, state.query) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(hit.entry.title, style = BonyType.body.copy(color = BonyColors.Text))
            Spacer(Modifier.height(2.dp))
            Text(hit.entry.breadcrumb, style = BonyType.meta.copy(color = BonyColors.TextMute))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * A block of rows that belongs to one section.
 *
 * Hides the whole block when the live query matches nothing inside it. It is
 * layout-transparent: no Box, no spacing, no extra layout node. A Box here
 * would turn the wrapped rows into one child of their Row/LazyColumn item and
 * shift everything under it — the bug that made Settings rows overlap.
 */
@Composable
fun SettingsSectionAnchor(
    section: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val search = LocalSettingsScreenSearch.current
    if (search != null && !search.sectionVisible(section)) return
    content()
}

/**
 * Marks one catalog row so a search hit can point at it.
 *
 * This is a composition scope, never a layout container. An earlier version
 * wrapped each row in a `Box`, which is what made Settings rows overlap: a Box
 * is one child of the surrounding `Row`, so the `Modifier.weight(1f)` that used
 * to sit on the row went to the Box instead, and every wrapped row in a
 * `Column`/`LazyColumn` measured its children with the wrong constraints.
 *
 * Anchors therefore draw nothing themselves. The row keeps its own modifier and
 * asks [searchRowHighlight] for the tint when a jump from another screen targets
 * it, so the highlight never changes the row's measurement.
 */
@Composable
fun SettingsAnchor(
    anchor: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    content()
}

/**
 * Highlight for the row a search jump targeted.
 *
 * Rows call this and pass the result into the modifier they already build:
 * ```
 * SettingsAnchor("app_lock") {
 *     Row(modifier = searchRowHighlight(Modifier.fillMaxWidth(), "app_lock")) { … }
 * }
 * ```
 * The Modifier is the first parameter so the call reads as a suffix on the
 * chain the row already had.
 * Because the tint is drawn by the row itself, wrapping a row in an anchor can
 * never move it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun searchRowHighlight(base: Modifier = Modifier, anchor: String): Modifier {
    val target = LocalSettingsJumpAnchor.current
    return if (target != null && target == anchor) {
        val requester = remember(anchor) { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        androidx.compose.runtime.LaunchedEffect(target) {
            androidx.compose.runtime.withFrameNanos { }
            requester.bringIntoView()
        }
        base.bringIntoViewRequester(requester).background(BonyColors.AccentBg)
    } else {
        base
    }
}
