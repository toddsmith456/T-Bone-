package social.tbone.ui.settings.search

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
import androidx.compose.runtime.CompositionLocalProvider
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

/** Section the anchor currently being composed belongs to. */
private val LocalCurrentSection = staticCompositionLocalOf<String?> { null }

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
fun rememberSettingsScreenSearch(query: String, limit: Int = 60): SettingsScreenSearchState {
    val hits = remember(query) {
        if (query.isBlank()) emptyList() else SettingsSearchIndex.search(query, limit = limit)
    }
    return remember(query, hits) { SettingsScreenSearchState(query = query, hits = hits) }
}

/** Query state that survives configuration changes and process death. */
@Composable
fun rememberSettingsSearchQuery(): androidx.compose.runtime.MutableState<String> =
    rememberSaveable { mutableStateOf("") }

/** Shared search field. */
@Composable
fun SettingsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search settings",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        singleLine = true,
        shape = appCornerShape(androidx.compose.material3.MaterialTheme.shapes.medium),
        placeholder = { Text(placeholder, color = BonyColors.TextMute) },
        leadingIcon = { Text("⌕", style = BonyType.title.copy(color = BonyColors.TextMute)) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Text("×", style = BonyType.body.copy(color = BonyColors.TextMute))
                }
            }
        },
    )
}

/** "3 matches on this screen" / "nothing here — see below". */
@Composable
fun SettingsSearchSummary(state: SettingsScreenSearchState, group: SettingsGroup) {
    if (!state.isActive) return
    val here = state.hits.count { it.entry.group == group }
    val text = when (here) {
        0 -> "no matches on this screen"
        1 -> "1 match on this screen"
        else -> "$here matches on this screen"
    }
    Text(
        text = text,
        style = BonyType.meta.copy(color = if (here == 0) BonyColors.TextMute else BonyColors.Accent),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
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
 * Sub-screen variant of the "nothing here" line, with suggestions.
 *
 * Returns the suggestion the user tapped (if any) through [onPick] so the
 * screen can replace its own query without owning search logic.
 */
@Composable
fun SettingsSearchEmptyHint(
    state: SettingsScreenSearchState,
    group: SettingsGroup,
    onPick: (String) -> Unit,
) {
    if (!state.isActive) return
    if (state.hits.any { it.entry.group == group }) return
    Text(
        text = "no matches on this screen",
        style = BonyType.meta.copy(color = BonyColors.TextMute),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
    val hints = androidx.compose.runtime.remember(state.query) {
        SettingsSearchIndex.suggestions(state.query)
    }
    if (hints.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Text("try", style = BonyType.meta.copy(color = BonyColors.TextMute))
        hints.forEach { hint ->
            Text(
                text = "  $hint",
                style = BonyType.meta.copy(color = BonyColors.Accent),
                modifier = Modifier.clickable { onPick(hint) }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * A block of rows that belongs to one section.
 *
 * Hides the whole block when the live query matches nothing inside it, and
 * tells nested [SettingsAnchor]s which section they belong to.
 */
@Composable
fun SettingsSectionAnchor(
    section: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val search = LocalSettingsScreenSearch.current
    if (search != null && !search.sectionVisible(section)) return
    CompositionLocalProvider(LocalCurrentSection provides section) {
        Box(modifier) { content() }
    }
}

/**
 * Marks one catalog row so a search hit can point at it.
 *
 * Anchors never hide their content: rows stay put and the section-level anchor
 * above them decides visibility. When a hit from another screen targets this
 * anchor, the row is tinted so the eye lands on the control that matched.
 */
@Composable
fun SettingsAnchor(
    anchor: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val highlighted = LocalSettingsJumpAnchor.current?.let { it == anchor } == true
    val base = if (highlighted) modifier.background(BonyColors.AccentBg) else modifier
    Box(base) { content() }
}
