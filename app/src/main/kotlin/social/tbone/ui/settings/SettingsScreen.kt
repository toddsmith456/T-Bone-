package social.tbone.ui.settings

import social.tbone.ui.components.AutoLockMinutesRow
import social.tbone.ui.theme.appBorder
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.account.Account
import social.tbone.nostr.identity.Phrase
import social.tbone.settings.AvatarMode
import social.tbone.settings.CachePreset
import social.tbone.settings.ImageLoadMode
import social.tbone.settings.VideoPlaybackMode
import social.tbone.settings.VideoQuality
import social.tbone.settings.WriteAccountSwitcherStyle
import social.tbone.settings.openOrbotInstallPage
import social.tbone.settings.ThemeMode
import social.tbone.settings.UiTheme
import dev.youniversal.theme.YouniversalBackgroundStyle
import dev.youniversal.theme.YouniversalContrast
import dev.youniversal.theme.YouniversalThemeState
import dev.youniversal.theme.YouniversalFontOption
import dev.youniversal.theme.YouniversalSwitch
import dev.youniversal.theme.YouniversalTheme
import dev.youniversal.theme.YouniversalLook
import dev.youniversal.theme.youniversalLookScheme
import dev.youniversal.theme.youniversalScheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import social.tbone.ui.theme.ThemeViewModel
import social.tbone.ui.components.DotAvatar
import social.tbone.ui.components.SectionHeader
import social.tbone.ui.components.LocalUiDeclutter
import social.tbone.ui.settings.search.LocalSettingsJumpAnchor
import social.tbone.ui.settings.search.LocalSettingsScreenSearch
import social.tbone.ui.settings.search.SettingsAnchor
import social.tbone.ui.settings.search.SettingsElsewhereResults
import social.tbone.ui.settings.search.SettingsSearchField
import social.tbone.ui.settings.search.SettingsSectionAnchor
import social.tbone.ui.settings.search.rememberSettingsScreenSearch
import social.tbone.ui.settings.search.searchRowHighlight
import social.tbone.settings.search.SettingsGroup
import social.tbone.settings.search.SettingsSearch
import social.tbone.settings.search.SettingsSearch as SettingsSearchIndex
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appCornerShape
import social.tbone.ui.theme.colorToHex
import social.tbone.ui.theme.parseHexColor
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.tbone.account.SignerType
import social.tbone.logging.LogExportOptions
import social.tbone.logging.LogFormatter
import social.tbone.logging.LogPreviewCounts
import social.tbone.logging.LogRepository
import social.tbone.logging.LogTimeRange

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onAccountManagement: () -> Unit,
    onRelayManagement: () -> Unit,
    onContentFilters: () -> Unit = {},
    onHomeFeeds: () -> Unit = {},
    onPinSetup: () -> Unit = {},
    onDuressPinSetup: () -> Unit = {},
    onBlossom: () -> Unit = {},
    onOfflineList: (String) -> Unit = {},
    onFollowLists: () -> Unit = {},
    onProfileEdit: () -> Unit = {},
    initialSearch: String = "",
    onOpenSearchHit: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val torEnabled by viewModel.torEnabled.collectAsStateWithLifecycle()
    val pinEnabled by viewModel.pinEnabled.collectAsStateWithLifecycle()
    val duressPinEnabled by viewModel.duressPinEnabled.collectAsStateWithLifecycle()
    val scrambleAppPinKeypad by viewModel.appPinKeypadScrambled.collectAsStateWithLifecycle()
    val screenshotBlockEnabled by viewModel.screenshotBlockEnabled.collectAsStateWithLifecycle()
    val noteMenuModerationEnabled by viewModel.noteMenuModerationEnabled.collectAsStateWithLifecycle()
    val clientTagEnabled by viewModel.clientTagEnabled.collectAsStateWithLifecycle()
    val imageLoadMode by viewModel.imageLoadMode.collectAsStateWithLifecycle()
    val videoQuality by viewModel.videoQuality.collectAsStateWithLifecycle()
    val videoPlaybackMode by viewModel.videoPlaybackMode.collectAsStateWithLifecycle()
    val videoThumbnails by viewModel.videoThumbnails.collectAsStateWithLifecycle()
    val mediaBackgroundPlayback by viewModel.mediaBackgroundPlayback.collectAsStateWithLifecycle()
    val mediaFittedLayout by viewModel.mediaFittedLayout.collectAsStateWithLifecycle()
    val cachePreset by viewModel.cachePreset.collectAsStateWithLifecycle()
    val cacheStats by viewModel.cacheStats.collectAsStateWithLifecycle()
    val cacheStatusMessage by viewModel.cacheStatusMessage.collectAsStateWithLifecycle()
    val cacheBusy by viewModel.cacheBusy.collectAsStateWithLifecycle()
    val avatarMode by viewModel.avatarMode.collectAsStateWithLifecycle()
    val avatarAnimated by viewModel.avatarAnimated.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
    val uiTheme by viewModel.uiTheme.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    var showAccentSheet by remember { mutableStateOf(false) }
    var showNotepadPopup by remember { mutableStateOf(false) }
    var settingsSearch by rememberSaveable { mutableStateOf(initialSearch) }
    val screenSearch = rememberSettingsScreenSearch(settingsSearch)
    // Arriving from another screen's "found elsewhere" carries the query; the
    // top hit on this screen becomes the row to flash.
    // Only a handed-over query (from another screen's "found elsewhere") flashes a
    // row; typing in this screen's own field must not highlight anything.
    val jumpAnchor = remember(initialSearch) {
        if (initialSearch.isBlank()) null
        else SettingsSearchIndex.search(initialSearch)
            .firstOrNull { it.entry.group == SettingsGroup.SETTINGS }?.entry?.anchor
    }
    val writeAccountSwitcherStyle by viewModel.writeAccountSwitcherStyle.collectAsStateWithLifecycle()
    val themeViewModel: ThemeViewModel = hiltViewModel()
    val youniversalState = themeViewModel.youniversalState
    val declutterEnabled by themeViewModel.uiDeclutterEnabled.collectAsStateWithLifecycle()
    val declutterMode = LocalUiDeclutter.current
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    fun sectionExpanded(id: String): Boolean = settingsSearch.isNotBlank() || !declutterMode || expandedSections[id] == true
    fun toggleSection(id: String) {
        expandedSections[id] = expandedSections[id] != true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsTopBar(onBack)
        SettingsSearchField(value = settingsSearch, onValueChange = { settingsSearch = it })
        if (settingsSearch.isNotBlank()) {
            val localHits = screenSearch.hits.count { it.entry.group == SettingsGroup.SETTINGS }
            Text(
                text = if (localHits == 0) "no matching settings on this screen" else "$localHits matches on this screen",
                style = BonyType.meta.copy(
                    color = if (localHits == 0) BonyColors.TextMute else BonyColors.Accent,
                ),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
            if (localHits == 0) {
                val hints = SettingsSearch.suggestions(settingsSearch)
                if (hints.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("try", style = BonyType.meta.copy(color = BonyColors.TextMute))
                        hints.forEach { hint ->
                            Text(
                                text = hint,
                                style = BonyType.meta.copy(color = BonyColors.Accent),
                                modifier = Modifier
                                    .appBorder(1.dp, BonyColors.AccentDim)
                                    .clickable { settingsSearch = hint }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            SettingsElsewhereResults(
                state = screenSearch,
                group = SettingsGroup.SETTINGS,
                onOpen = { _, query -> onOpenSearchHit(query) },
            )
        }
        CompositionLocalProvider(
            LocalSettingsScreenSearch provides screenSearch,
            LocalSettingsJumpAnchor provides jumpAnchor,
        ) {
        SettingsSectionAnchor("set_ui_declutter") {
        SettingsAnchor("ui_declutter") {
            SettingsSwitchRow(
                title = "UI declutter",
                description = "compact tabs and make Settings categories collapsible",
                checked = declutterEnabled,
                onCheckedChange = themeViewModel::setUiDeclutterEnabled,
            )
        }
            Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        }
        SettingsSectionAnchor("set_tor_strip") { TorActiveStrip(torEnabled) }
        SettingsSectionAnchor("set_account") {
            SettingsAccountSection(
                activeAccount, onAddAccount, onAccountManagement,
                compact = declutterMode, expanded = sectionExpanded("account"),
                onToggle = { toggleSection("account") },
            )
        }
        SettingsSectionAnchor("set_relays") {
            SettingsRelaySection(onRelayManagement, compact = declutterMode, expanded = sectionExpanded("relays"), onToggle = { toggleSection("relays") })
        }
        SettingsSectionAnchor("set_lists") {
            SettingsFollowListsLink(onFollowLists = onFollowLists, compact = declutterMode, expanded = sectionExpanded("lists"), onToggle = { toggleSection("lists") })
        }
        SettingsSectionAnchor("set_content_filters") {
            SettingsContentFiltersSection(onContentFilters, compact = declutterMode, expanded = sectionExpanded("content"), onToggle = { toggleSection("content") })
        }
        SettingsSectionAnchor("set_home_feeds") {
            SettingsHomeFeedsLink(onHomeFeeds = onHomeFeeds, compact = declutterMode, expanded = sectionExpanded("home_feeds"), onToggle = { toggleSection("home_feeds") })
        }
        SettingsSectionAnchor("set_note_actions") {
            SettingsNoteActionsSection(
                noteMenuModerationEnabled, clientTagEnabled, writeAccountSwitcherStyle, viewModel,
                compact = declutterMode, expanded = sectionExpanded("note_actions"),
                onToggle = { toggleSection("note_actions") },
            )
        }
        SettingsSectionAnchor("set_reactions") {
            ReactionSettingsSection(viewModel, compact = declutterMode, expanded = sectionExpanded("reactions"), onToggle = { toggleSection("reactions") })
        }
        SettingsSectionAnchor("set_media") {
            SettingsMediaSection(viewModel, onProfileEdit, onBlossom, imageLoadMode, videoQuality, videoPlaybackMode, videoThumbnails, mediaBackgroundPlayback, mediaFittedLayout, compact = declutterMode, expanded = sectionExpanded("media"), onToggle = { toggleSection("media") })
        }
        SettingsSectionAnchor("set_avatars") {
            SettingsAvatarSection(viewModel, avatarMode, avatarAnimated, compact = declutterMode, expanded = sectionExpanded("avatars"), onToggle = { toggleSection("avatars") })
        }
        SettingsSectionAnchor("set_storage_cache") {
            SettingsStorageCacheSection(viewModel, cachePreset, cacheStats, cacheStatusMessage, cacheBusy, torEnabled, compact = declutterMode, expanded = sectionExpanded("storage_cache"), onToggle = { toggleSection("storage_cache") })
        }
        SettingsSectionAnchor("set_theme_engine") {
            SettingsThemeEngineSection(viewModel, uiTheme, compact = declutterMode, expanded = sectionExpanded("theme_engine"), onToggle = { toggleSection("theme_engine") })
        }
        SettingsSectionAnchor("set_appearance") {
            SettingsAppearanceSection(viewModel, uiTheme, themeMode, accentColor, youniversalState, onChooseAccent = { showAccentSheet = true }, compact = declutterMode, expanded = sectionExpanded("appearance"), onToggle = { toggleSection("appearance") })
        }
        SettingsSectionAnchor("set_privacy") {
            SettingsPrivacySection(viewModel, context, torEnabled, pinEnabled, screenshotBlockEnabled, duressPinEnabled, onPinSetup, onDuressPinSetup, scrambleAppPinKeypad, compact = declutterMode, expanded = sectionExpanded("privacy"), onToggle = { toggleSection("privacy") })
        }
        SettingsSectionAnchor("set_stealth") {
            SettingsStealthSection(viewModel, compact = declutterMode, expanded = sectionExpanded("stealth"), onToggle = { toggleSection("stealth") }, onShowPopup = { showNotepadPopup = true })
        }
        SettingsSectionAnchor("set_debug") {
            SettingsDebugSection(viewModel, context, accounts, activeAccount, compact = declutterMode, expanded = sectionExpanded("debug"), onToggle = { toggleSection("debug") })
            }
        }
        Spacer(Modifier.height(96.dp))
    }

    if (showAccentSheet) {
        AccentColorSheet(
            initial = parseHexColor(accentColor) ?: BonyColors.DefaultAccent,
            onPick = { color -> BonyColors.setAccent(color) },
            onCommit = { color -> viewModel.setAccentColor(colorToHex(color)) },
            onReset = {
                BonyColors.setAccent(null)
                viewModel.setAccentColor(null)
            },
            onDismiss = { showAccentSheet = false },
        )
    }
    if (showNotepadPopup) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BonyColors.Bg.copy(alpha = 0.85f))
                .clickable { showNotepadPopup = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .appBorder(1.dp, BonyColors.RuleStrong)
                    .background(BonyColors.Surface)
                    .padding(16.dp),
            ) {
                Text("Notepad disguise ON", style = BonyType.body.copy(color = BonyColors.Text))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Launcher now shows \u201CNotepad\u201D. Opening it shows only the decoy notes \u2014 real notes are invisible.\n\nTo get back to T-Bone: open Notepad \u2192 tap \u24D8 encryption \u2192 tap \u201Ckey storage\u201D 10 times quickly.\n\nNext launch will start in Notepad until you unlock. Turn this off in Settings to restore T-Bone icon.",
                    style = BonyType.meta.copy(color = BonyColors.TextDim),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "GOT IT",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier
                        .appBorder(1.dp, BonyColors.AccentDim)
                        .clickable { showNotepadPopup = false }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    // ── Top bar ───────────────────────────────────────────────────────────
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.Bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "←",
            style = BonyType.body.copy(color = BonyColors.TextMute),
            modifier = Modifier.clickable { onBack() },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "settings",
            style = BonyType.body.copy(color = BonyColors.Text),
        )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun TorActiveStrip(torEnabled: Boolean) {
    // ── Tor active strip ──────────────────────────────────────────────────
    if (torEnabled) {
        val torStripShape = appCornerShape(MaterialTheme.shapes.medium, RectangleShape)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(torStripShape)
                .background(BonyColors.WarnBg, torStripShape)
                .border(
                    width = 1.dp,
                    color = BonyColors.WarnDim,
                    shape = torStripShape,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column {
            SettingsAnchor("tor_strip") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = searchRowHighlight(Modifier
                            .size(8.dp)
                            .background(BonyColors.Warn, shape = androidx.compose.foundation.shape.CircleShape), "tor_strip"),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("TOR ACTIVE", style = BonyType.tag.copy(color = BonyColors.Warn))
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .appBorder(1.dp, BonyColors.WarnDim)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("ON", style = BonyType.tag.copy(color = BonyColors.Warn))
                    }
                }
            }
                Spacer(Modifier.height(4.dp))
                Text(
                    "all relay traffic routed through orbot",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
        }
    }
}

@Composable
private fun SettingsAccountSection(activeAccount: Account?, onAddAccount: () -> Unit, onAccountManagement: () -> Unit, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Account section ───────────────────────────────────────────────────
    SettingsCategoryHeader("ACCOUNT", compact, expanded, onToggle)
    if (compact && !expanded) return

    activeAccount?.let { account ->
    SettingsAnchor("account") {
        AccountSettingsRow(account = account, onManage = onAccountManagement)
    }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
    }

SettingsAnchor("add_account") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .clickable { onAddAccount() }
            .padding(horizontal = 14.dp, vertical = 12.dp), "add_account"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("+", style = BonyType.body.copy(color = BonyColors.TextDim))
        Spacer(Modifier.width(10.dp))
        Text("add account", style = BonyType.body.copy(color = BonyColors.TextDim))
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsRelaySection(onRelayManagement: () -> Unit, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Relays section ────────────────────────────────────────────────────
    SettingsCategoryHeader("RELAYS", compact, expanded, onToggle)
    if (compact && !expanded) return

SettingsAnchor("relays") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .clickable { onRelayManagement() }
            .padding(horizontal = 14.dp, vertical = 12.dp), "relays"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("manage relays", style = BonyType.body.copy(color = BonyColors.TextDim), modifier = Modifier.weight(1f))
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsFollowListsLink(onFollowLists: () -> Unit, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Follow lists tool (moved out of settings) ───────────────────────────
    SettingsCategoryHeader("LISTS", compact, expanded, onToggle)
    if (compact && !expanded) return

SettingsAnchor("lists") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .clickable { onFollowLists() }
            .padding(horizontal = 14.dp, vertical = 12.dp), "lists"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("follow lists", style = BonyType.body.copy(color = BonyColors.TextDim))
            Text(
                text = "following · followers · mutes — offline or relay",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsContentFiltersSection(onContentFilters: () -> Unit, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Content filters section ───────────────────────────────────────────
    SettingsCategoryHeader("CONTENT", compact, expanded, onToggle)
    if (compact && !expanded) return

SettingsAnchor("content_filters") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .clickable { onContentFilters() }
            .padding(horizontal = 14.dp, vertical = 12.dp), "content_filters"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("content filters", style = BonyType.body.copy(color = BonyColors.TextDim))
            Text(
                text = "filters · parental controls · screen time",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

/** Entry point to the home-feed layout editor. */
@Composable
private fun SettingsHomeFeedsLink(onHomeFeeds: () -> Unit, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    SettingsCategoryHeader("HOME FEEDS", compact, expanded, onToggle)
    if (compact && !expanded) return

SettingsAnchor("home_feeds") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .clickable { onHomeFeeds() }
            .padding(horizontal = 14.dp, vertical = 12.dp), "home_feeds"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("home feeds", style = BonyType.body.copy(color = BonyColors.TextDim))
            Text(
                text = "swap, reorder or hide the tabs on the home screen",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsNoteActionsSection(noteMenuModerationEnabled: Boolean, clientTagEnabled: Boolean, writeAccountSwitcherStyle: WriteAccountSwitcherStyle, viewModel: SettingsViewModel, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Note actions and event attribution ─────────────────────────────────
    SettingsCategoryHeader("NOTE ACTIONS", compact, expanded, onToggle)
    if (compact && !expanded) return
SettingsAnchor("note_moderation") {
    SettingsSwitchRow(
        title = "show mute/report actions",
        description = "Adds mute poster and public NIP-56 report to note menus · off by default",
        checked = noteMenuModerationEnabled,
        onCheckedChange = viewModel::setNoteMenuModerationEnabled,
    )
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
SettingsAnchor("client_tag") {
    SettingsSwitchRow(
        title = "add T-Bone client tag",
        description = "Adds a public NIP-89 client tag to events you sign · on by default",
        checked = clientTagEnabled,
        onCheckedChange = viewModel::setClientTagEnabled,
    )
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

SettingsAnchor("write_switcher") {
    Text("Write-tab account switcher", style = BonyType.body.copy(color = BonyColors.Text), modifier = searchRowHighlight(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), "write_switcher"))
}
    Text("Choose how the active account appears beside SEND", style = BonyType.meta.copy(color = BonyColors.TextMute), modifier = Modifier.padding(horizontal = 14.dp))
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        WriteAccountSwitcherStyle.entries.forEach { option ->
            val selected = option == writeAccountSwitcherStyle
            Box(
                modifier = Modifier.weight(1f)
                    .appBorder(1.dp, if (selected) BonyColors.Accent else BonyColors.Rule)
                    .background(if (selected) BonyColors.AccentBg else BonyColors.Surface)
                    .clickable { viewModel.setWriteAccountSwitcherStyle(option) }
                    .padding(horizontal = 3.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(option.label, style = BonyType.meta.copy(color = if (selected) BonyColors.Accent else BonyColors.TextMute), maxLines = 1)
            }
        }
    }

    // Screen time now lives inside the pin-locked Content Filters folder.
}

@Composable
private fun SettingsMediaSection(viewModel: SettingsViewModel, onProfileEdit: () -> Unit, onBlossom: () -> Unit, imageLoadMode: ImageLoadMode, videoQuality: VideoQuality, videoPlaybackMode: VideoPlaybackMode, videoThumbnails: Boolean, mediaBackgroundPlayback: Boolean, mediaFittedLayout: Boolean, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Media section ─────────────────────────────────────────────────────
    SettingsCategoryHeader("MEDIA", compact, expanded, onToggle)
    if (compact && !expanded) return

    // Profile picture & banner upload.
SettingsAnchor("profile_picture") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .clickable { onProfileEdit() }
            .padding(horizontal = 14.dp, vertical = 12.dp), "profile_picture"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("profile picture & banner", style = BonyType.body.copy(color = BonyColors.TextDim), modifier = Modifier.weight(1f))
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // Blossom upload servers.
SettingsAnchor("blossom") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .clickable { onBlossom() }
            .padding(horizontal = 14.dp, vertical = 12.dp), "blossom"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("blossom uploads", style = BonyType.body.copy(color = BonyColors.TextDim), modifier = Modifier.weight(1f))
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

SettingsAnchor("inline_images") {
    Column(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp), "inline_images"),
    ) {
        Text("inline images", style = BonyType.body.copy(color = BonyColors.Text))
        Spacer(Modifier.height(2.dp))
        Text(
            text = when (imageLoadMode) {
                ImageLoadMode.OFF -> "off · tap images to view in-app"
                ImageLoadMode.ON -> "on · auto-load in feed (default)"
                ImageLoadMode.LOW_QUALITY -> "low · lower resolution, faster"
            },
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(10.dp))
        Row {
            ImageLoadMode.entries.forEach { mode ->
                val active = mode == imageLoadMode
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, appCornerShape(MaterialTheme.shapes.small))
                        .clickable { viewModel.setImageLoadMode(mode) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = mode.label,
                        style = BonyType.tag.copy(
                            color = if (active) BonyColors.Accent else BonyColors.TextMute,
                        ),
                    )
                }
            }
        }
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // Video playback is kept separate from image loading because videos can
    // consume substantially more bandwidth and battery than a still image.
SettingsAnchor("video_quality") {
    Column(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp), "video_quality"),
    ) {
        Text("video quality", style = BonyType.body.copy(color = BonyColors.Text))
        Spacer(Modifier.height(2.dp))
        Text(
            text = when (videoQuality) {
                VideoQuality.OFF -> "off · videos wait for a tap"
                VideoQuality.LOW -> "low · requests smaller tracks where available"
                VideoQuality.REGULAR -> "regular · posted/original quality"
            },
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VideoQuality.entries.forEach { quality ->
                val active = quality == videoQuality
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, appCornerShape(MaterialTheme.shapes.small))
                        .clickable { viewModel.setVideoQuality(quality) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = quality.label,
                        style = BonyType.tag.copy(
                            color = if (active) BonyColors.Accent else BonyColors.TextMute,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
    SettingsAnchor("video_playback") {
        Text("video playback", style = BonyType.body.copy(color = BonyColors.Text))
        Spacer(Modifier.height(2.dp))
        Text(
            text = when {
                videoQuality == VideoQuality.OFF ->
                    "video quality is off · videos wait for a tap until you turn quality back on"
                videoPlaybackMode == VideoPlaybackMode.LOAD_ON_TAP ->
                    "on tap · lightweight placeholder until tapped (default)"
                videoPlaybackMode == VideoPlaybackMode.PLAY_ON_TAP ->
                    "preload · prepares first frame inline, plays when tapped"
                else ->
                    "autoplay · loads and plays one inline video automatically"
            },
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VideoPlaybackMode.entries.forEach { mode ->
                val active = mode == videoPlaybackMode
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, appCornerShape(MaterialTheme.shapes.small))
                        .clickable { viewModel.setVideoPlaybackMode(mode) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = mode.shortLabel,
                        style = BonyType.tag.copy(
                            color = if (active) BonyColors.Accent else BonyColors.TextMute,
                        ),
                    )
                }
            }
        }
    }
        Spacer(Modifier.height(10.dp))
        Text(
            "Only one inline video plays at a time. When a post supplies a preview still, it is shown on placeholders — nothing is extracted from videos on this device.",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(12.dp))
    SettingsAnchor("media_background_playback") {
        Row(
            modifier = searchRowHighlight(Modifier.fillMaxWidth(), "media_background_playback"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("background playback", style = BonyType.body.copy(color = BonyColors.TextDim))
                Text(
                    "when on, full-screen video audio and Audio Player music continue after leaving the player; closing a playing video keeps its audio in the background",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            BonyToggle(
                checked = mediaBackgroundPlayback,
                enabled = true,
                onCheckedChange = { viewModel.setMediaBackgroundPlayback(it) },
            )
        }
    }
        Spacer(Modifier.height(12.dp))
    SettingsAnchor("video_thumbnails") {
        Row(
            modifier = searchRowHighlight(Modifier.fillMaxWidth(), "video_thumbnails"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("video thumbnails", style = BonyType.body.copy(color = BonyColors.TextDim))
                Text(
                    "show the preview still a post supplies for its video",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            BonyToggle(
                checked = videoThumbnails,
                enabled = true,
                onCheckedChange = { viewModel.setVideoThumbnails(it) },
            )
        }
    }
        Spacer(Modifier.height(12.dp))
    SettingsAnchor("media_fitted") {
        Row(
            modifier = searchRowHighlight(Modifier.fillMaxWidth(), "media_fitted"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("fit media to its real shape", style = BonyType.body.copy(color = BonyColors.TextDim))
                Text(
                    "on by default · pictures and videos take their own proportions (using the size stated in the post when available) instead of the standard box · blurred previews while loading",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            BonyToggle(
                checked = mediaFittedLayout,
                enabled = true,
                onCheckedChange = { viewModel.setMediaFittedLayout(it) },
            )
        }
    }
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsAvatarSection(viewModel: SettingsViewModel, avatarMode: AvatarMode, avatarAnimated: Boolean, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Avatars section ───────────────────────────────────────────────────
    SettingsCategoryHeader("AVATARS", compact, expanded, onToggle)
    if (compact && !expanded) return

SettingsAnchor("avatar_style") {
    Column(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp), "avatar_style"),
    ) {
        Text("avatar style", style = BonyType.body.copy(color = BonyColors.Text))
        Spacer(Modifier.height(2.dp))
        Text(
            text = when (avatarMode) {
                AvatarMode.INITIAL -> "initial · deterministic letter squares"
                AvatarMode.LOW -> "low · real avatars, lower resolution, faster"
                AvatarMode.REGULAR -> "regular · real avatars at full quality"
            },
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(10.dp))
        Row {
            AvatarMode.entries.forEach { mode ->
                val active = mode == avatarMode
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, appCornerShape(MaterialTheme.shapes.small))
                        .clickable { viewModel.setAvatarMode(mode) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = mode.label,
                        style = BonyType.tag.copy(
                            color = if (active) BonyColors.Accent else BonyColors.TextMute,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    SettingsAnchor("avatar_animated") {
        Row(
            modifier = searchRowHighlight(Modifier.fillMaxWidth(), "avatar_animated"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("animated avatars", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    text = if (avatarAnimated) "gif avatars play" else "gif avatars show a still picture",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            BonyToggle(
                checked = avatarAnimated,
                enabled = avatarMode != AvatarMode.INITIAL,
                onCheckedChange = { viewModel.setAvatarAnimated(it) },
            )
        }
    }
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsStorageCacheSection(
    viewModel: SettingsViewModel,
    cachePreset: CachePreset,
    cacheStats: social.tbone.cache.CacheStorageStats,
    cacheStatusMessage: String?,
    cacheBusy: Boolean,
    torEnabled: Boolean,
    compact: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SettingsCategoryHeader("STORAGE & CACHE", compact, expanded, onToggle)
    if (compact && !expanded) return

    LaunchedEffect(expanded) {
        viewModel.refreshCacheStats()
    }

    val smallShape = appCornerShape(MaterialTheme.shapes.small)
SettingsAnchor("cache_preset") {
    Column(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp), "cache_preset"),
    ) {
        Text("cache level", style = BonyType.body.copy(color = BonyColors.Text))
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${cachePreset.label.lowercase()} (${cachePreset.approxTotalLabel}) · ${cachePreset.summary}",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CachePreset.entries.forEach { preset ->
                val active = preset == cachePreset
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, smallShape)
                        .background(if (active) BonyColors.AccentBg else Color.Transparent, smallShape)
                        .clickable(enabled = !cacheBusy) { viewModel.setCachePreset(preset) }
                        .padding(vertical = 8.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = preset.shortLabel,
                        style = BonyType.tag.copy(
                            color = if (active) BonyColors.Accent else BonyColors.TextMute,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        text = preset.approxTotalLabel,
                        style = BonyType.meta.copy(
                            color = if (active) BonyColors.Accent else BonyColors.TextMute,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (torEnabled) {
                "avatars stay isolated from feed photos · followed accounts stay pinned · off-screen preloading pauses while Tor is on"
            } else {
                "avatars stay isolated from feed photos · followed accounts stay pinned"
            },
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )

        Spacer(Modifier.height(14.dp))
    SettingsAnchor("cache_usage") {
        Column(
            modifier = searchRowHighlight(Modifier
                .fillMaxWidth()
                .border(1.dp, BonyColors.Rule, smallShape)
                .background(BonyColors.SurfaceAlt, smallShape)
                .padding(horizontal = 10.dp, vertical = 8.dp), "cache_usage"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CacheUsageRow(
                label = "avatars (protected)",
                value = "${social.tbone.cache.CacheStorageManager.formatBytes(cacheStats.avatarBytes)} / ${social.tbone.cache.CacheStorageManager.formatBudget(cachePreset.avatarDiskBytes)}",
            )
            CacheUsageRow(
                label = "feed photos",
                value = "${social.tbone.cache.CacheStorageManager.formatBytes(cacheStats.mediaBytes)} / ${social.tbone.cache.CacheStorageManager.formatBudget(cachePreset.mediaDiskBytes)}",
            )
            CacheUsageRow(
                label = "audio details (clear with photos)",
                value = "${social.tbone.cache.CacheStorageManager.formatBytes(cacheStats.audioDetailsBytes)} / ${social.tbone.cache.CacheStorageManager.formatBudget(social.tbone.media.audio.AudioCachePolicy.diskBudget(cachePreset))}",
            )
            CacheUsageRow(
                label = "videos & audio",
                value = if (cachePreset.videoDiskBytes <= 0L) {
                    "off"
                } else {
                    "${social.tbone.cache.CacheStorageManager.formatBytes(cacheStats.videoBytes)} / ${social.tbone.cache.CacheStorageManager.formatBudget(cachePreset.videoDiskBytes)}"
                },
            )
            CacheUsageRow(
                label = "database",
                value = "${social.tbone.cache.CacheStorageManager.formatBytes(cacheStats.databaseBytes)} · ${cacheStats.profileCount} profiles · ${cacheStats.eventCount} posts",
            )
        }
    }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        SettingsAnchor("clear_photos") {
            CacheActionButton(
                label = "clear photos",
                enabled = !cacheBusy,
                modifier = searchRowHighlight(Modifier.weight(1f), "clear_photos"),
                onClick = { viewModel.clearFeedMediaCache() },
            )
        }
        SettingsAnchor("clear_videos") {
            CacheActionButton(
                label = "clear videos",
                enabled = !cacheBusy,
                modifier = searchRowHighlight(Modifier.weight(1f), "clear_videos"),
                onClick = { viewModel.clearVideoAudioCache() },
            )
        }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        SettingsAnchor("clear_avatars") {
            CacheActionButton(
                label = "clear avatars",
                enabled = !cacheBusy,
                modifier = searchRowHighlight(Modifier.weight(1f), "clear_avatars"),
                onClick = { viewModel.clearAvatarCache() },
            )
        }
        SettingsAnchor("clear_all_cache") {
            CacheActionButton(
                label = "clear all cache",
                enabled = !cacheBusy,
                danger = true,
                modifier = searchRowHighlight(Modifier.weight(1f), "clear_all_cache"),
                onClick = { viewModel.clearAllCaches() },
            )
        }
        }

        cacheStatusMessage?.let { status ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = status,
                style = BonyType.meta.copy(color = BonyColors.Accent),
            )
        }
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun CacheUsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = BonyType.meta.copy(color = BonyColors.TextMute))
        Text(value, style = BonyType.meta.copy(color = BonyColors.TextDim))
    }
}

@Composable
private fun CacheActionButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = appCornerShape(MaterialTheme.shapes.small)
    Box(
        modifier = modifier
            .border(1.dp, if (danger) BonyColors.Danger else BonyColors.Rule, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = BonyType.tag.copy(
                color = when {
                    !enabled -> BonyColors.TextMute
                    danger -> BonyColors.Danger
                    else -> BonyColors.TextDim
                },
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun SettingsThemeEngineSection(viewModel: SettingsViewModel, uiTheme: UiTheme, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Theme engine (Bony vs Youniversal) ──────────────────────────────
    SettingsCategoryHeader("THEME ENGINE", compact, expanded, onToggle)
    if (compact && !expanded) return
SettingsAnchor("ui_theme") {
    Column(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp), "ui_theme"),
    ) {
        Text("UI theme", style = BonyType.body.copy(color = BonyColors.Text))
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (uiTheme == UiTheme.BONY) "T-Bone classic · JetBrains Mono" else "Youniversal · Light/Dark/Cream/Auto + Material You",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UiTheme.entries.forEach { theme ->
                val active = theme == uiTheme
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, appCornerShape(MaterialTheme.shapes.small))
                        .clickable { viewModel.setUiTheme(theme) }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = theme.label,
                        style = BonyType.tag.copy(
                            color = if (active) BonyColors.Accent else BonyColors.TextMute,
                        ),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = theme.description,
                        style = BonyType.metaDim.copy(
                            color = if (active) BonyColors.TextDim else BonyColors.TextMute,
                        ),
                        maxLines = 2,
                    )
                }
            }
        }
        if (uiTheme == UiTheme.YOUNIVERSAL) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Youniversal adds Auto, Dynamic Color (Android 12+ wallpaper), high contrast, accent seeds, and polished shapes. Use the controls below — they save instantly and survive restarts.",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsAppearanceSection(viewModel: SettingsViewModel, uiTheme: UiTheme, themeMode: ThemeMode, accentColor: String?, youniversalState: YouniversalThemeState, onChooseAccent: () -> Unit, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Appearance section ────────────────────────────────────────────────
    SettingsCategoryHeader("APPEARANCE", compact, expanded, onToggle)
    if (compact && !expanded) return

    if (uiTheme == UiTheme.BONY) {
    SettingsAnchor("theme_mode") {
        Column(
            modifier = searchRowHighlight(Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp), "theme_mode"),
        ) {
            Text("theme", style = BonyType.body.copy(color = BonyColors.Text))
            Spacer(Modifier.height(2.dp))
            Text(
                text = when (themeMode) {
                    ThemeMode.LIGHT -> "light · bright paper"
                    ThemeMode.DARK -> "dark · pure black, the default look"
                    ThemeMode.CREAM -> "cream · warm old-book-page"
                },
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
            Spacer(Modifier.height(10.dp))
            Row {
                ThemeMode.entries.forEach { mode ->
                    val active = mode == themeMode
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, appCornerShape(MaterialTheme.shapes.small))
                            .clickable { viewModel.setThemeMode(mode) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = mode.label,
                            style = BonyType.tag.copy(
                                color = if (active) BonyColors.Accent else BonyColors.TextMute,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        SettingsAnchor("accent_color") {
            Row(
                modifier = searchRowHighlight(Modifier.fillMaxWidth(), "accent_color"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("accent color", style = BonyType.body.copy(color = BonyColors.Text))
                    Text(
                        text = "replaces the default green · tap to choose",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
                // Live preview of the current accent.
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .border(1.dp, BonyColors.RuleStrong, CircleShape)
                        .background(
                            parseHexColor(accentColor) ?: BonyColors.DefaultAccent,
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                BonyButton(
                    label = "CHOOSE",
                    primary = false,
                    onClick = { onChooseAccent() },
                )
            }
        }
        }
    }
    } else {
        // Youniversal controls — live, persisted via YouniversalThemeState (SharedPreferences)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Youniversal theme", style = BonyType.body.copy(color = BonyColors.Text))
            Text(
                "Light · Dark · Cream · Auto + Material You on Android 12+. Everything below saves instantly.",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )

            // One-tap looks: coordinated bundles of the fine-tune controls
            // below, each with a live mini preview of its own palette.
            Text("looks", style = BonyType.meta.copy(color = BonyColors.TextMute))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                YouniversalLook.entries.forEach { look ->
                    LookPreviewCard(
                        look = look,
                        selected = youniversalState.activeLook == look,
                        state = youniversalState,
                        onClick = { youniversalState.applyLook(look) },
                    )
                }
            }
            Text(
                "tap a look to set everything below at once — tweak any control and it becomes Custom",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            )

            // Fine-tune controls. Any manual change here marks the mix Custom.
            Text("fine tune", style = BonyType.meta.copy(color = BonyColors.TextMute))

            // App-wide typeface. Standard is intentionally the Youniversal
            // default; Soft provides a warmer, rounded sans-serif alternative.
            Text("app font", style = BonyType.meta.copy(color = BonyColors.TextMute))
        SettingsAnchor("background") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                YouniversalFontOption.entries.forEach { option ->
                    val active = option == youniversalState.fontOption
                    val shape = appCornerShape(MaterialTheme.shapes.small)
                    Column(
                        modifier = searchRowHighlight(Modifier
                            .weight(1f)
                            .clip(shape)
                            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, shape)
                            .clickable { youniversalState.setFontOption(option) }
                            .padding(horizontal = 6.dp, vertical = 8.dp), "background"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = option.label,
                            style = BonyType.tag.copy(
                                color = if (active) BonyColors.Accent else BonyColors.TextMute,
                            ),
                            maxLines = 1,
                        )
                        Text(
                            text = option.description,
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                            maxLines = 2,
                        )
                    }
                }
            }
        }

            // Background
            Text("background", style = BonyType.meta.copy(color = BonyColors.TextMute))
        SettingsAnchor("contrast") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                YouniversalBackgroundStyle.entries.forEach { style ->
                    val active = style == youniversalState.backgroundStyle
                    Column(
                        modifier = searchRowHighlight(Modifier
                            .weight(1f)
                            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, appCornerShape(MaterialTheme.shapes.small))
                            .clickable { youniversalState.setBackgroundStyle(style) }
                            .padding(vertical = 8.dp), "contrast"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = style.label,
                            style = BonyType.tag.copy(
                                color = if (active) BonyColors.Accent else BonyColors.TextMute,
                            ),
                        )
                    }
                }
            }
        }

            // Contrast
            Text("contrast", style = BonyType.meta.copy(color = BonyColors.TextMute))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                YouniversalContrast.entries.forEach { c ->
                    val active = c == youniversalState.contrast
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, appCornerShape(MaterialTheme.shapes.small))
                            .clickable { youniversalState.setContrast(c) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = c.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = BonyType.tag.copy(
                                color = if (active) BonyColors.Accent else BonyColors.TextMute,
                            ),
                        )
                    }
                }
            }

            // Dynamic color
        SettingsAnchor("material_you") {
            Row(
                modifier = searchRowHighlight(Modifier.fillMaxWidth(), "material_you"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Material You", style = BonyType.body.copy(color = BonyColors.Text))
                    val available = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    val seedActive = youniversalState.accentSeed != androidx.compose.ui.graphics.Color.Unspecified
                    Text(
                        text = when {
                            !available -> "Requires Android 12+"
                            seedActive -> "Paused while accent seed is active"
                            else -> "Derive palette from wallpaper"
                        },
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
                BonyToggle(
                    checked = youniversalState.dynamicColor,
                    enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && youniversalState.accentSeed == androidx.compose.ui.graphics.Color.Unspecified,
                    onCheckedChange = { youniversalState.setDynamicColor(it) },
                )
            }
        }

            // Accent seed
            Text("accent seed", style = BonyType.meta.copy(color = BonyColors.TextMute))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val seeds = listOf(
                    "Brand" to androidx.compose.ui.graphics.Color.Unspecified,
                    "Teal" to androidx.compose.ui.graphics.Color(0xFF0F9C8E),
                    "Rose" to androidx.compose.ui.graphics.Color(0xFFC2185B),
                    "Forest" to androidx.compose.ui.graphics.Color(0xFF1B7F3B),
                    "Amber" to androidx.compose.ui.graphics.Color(0xFFB26A00),
                    "Violet" to androidx.compose.ui.graphics.Color(0xFF6A3FB5),
                )
                seeds.forEach { (name, color) ->
                    val selected = youniversalState.accentSeed == color
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { youniversalState.setAccentSeed(color) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) BonyColors.Accent else BonyColors.Rule,
                                    shape = CircleShape,
                                )
                                .background(
                                    if (color == androidx.compose.ui.graphics.Color.Unspecified) BonyColors.SurfaceAlt else color,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (color == androidx.compose.ui.graphics.Color.Unspecified) {
                                Text("Y", style = BonyType.tag.copy(color = BonyColors.TextMute))
                            } else if (selected) {
                                Text("✓", style = BonyType.tag.copy(color = androidx.compose.ui.graphics.Color.White))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(name, style = BonyType.metaDim.copy(color = BonyColors.TextMute))
                    }
                }
            }
            Text(
                "A seed generates all colors from one brand color — works on any Android version.",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )

            // Corner scale
        SettingsAnchor("corner_radius") {
            Row(
                modifier = searchRowHighlight(Modifier.fillMaxWidth(), "corner_radius"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("corner radius", style = BonyType.body.copy(color = BonyColors.Text))
                    Text(
                        "${(youniversalState.cornerScale * 100).toInt()}% of default",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BonyButton(label = "−", primary = false, onClick = { youniversalState.setCornerScale((youniversalState.cornerScale - 0.2f).coerceIn(0f, 2f)) })
                    BonyButton(label = "+", primary = false, onClick = { youniversalState.setCornerScale((youniversalState.cornerScale + 0.2f).coerceIn(0f, 2f)) })
                }
            }
        }

            // Font scale
        SettingsAnchor("text_size") {
            Row(
                modifier = searchRowHighlight(Modifier.fillMaxWidth(), "text_size"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("text size", style = BonyType.body.copy(color = BonyColors.Text))
                    Text(
                        "${(youniversalState.fontScale * 100).toInt()}% of default",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BonyButton(label = "−", primary = false, onClick = { youniversalState.setFontScale((youniversalState.fontScale - 0.1f).coerceIn(0.7f, 2f)) })
                    BonyButton(label = "+", primary = false, onClick = { youniversalState.setFontScale((youniversalState.fontScale + 0.1f).coerceIn(0.7f, 2f)) })
                }
            }
        }

            // Animate
        SettingsAnchor("animate_theme") {
            Row(
                modifier = searchRowHighlight(Modifier.fillMaxWidth(), "animate_theme"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("animate theme changes", style = BonyType.body.copy(color = BonyColors.Text))
                    Text("morph colors instead of snapping", style = BonyType.meta.copy(color = BonyColors.TextMute))
                }
                BonyToggle(
                    checked = youniversalState.animateTransitions,
                    enabled = true,
                    onCheckedChange = { youniversalState.setAnimateTransitions(it) },
                )
            }
        }

            // Reset
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .appBorder(1.dp, BonyColors.Rule)
                    .clickable { youniversalState.reset() }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("RESET YOUNIVERSAL TO DEFAULTS", style = BonyType.tag.copy(color = BonyColors.TextMute))
            }
        }
    }
    // Launcher-icon presets close the section so they collapse together with
    // the theme settings under UI declutter.
    IconSettings()
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

/**
 * Mini live mock of one Youniversal look for the settings looks row:
 * the look's own background, a stand-in post card, name/body lines and the
 * accent dot. [YouniversalLook.CUSTOM] previews the current fine-tune mix.
 */
@Composable
private fun LookPreviewCard(
    look: YouniversalLook,
    selected: Boolean,
    state: YouniversalThemeState,
    onClick: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = remember(
        look,
        systemDark,
        state.backgroundStyle,
        state.contrast,
        state.accentSeed,
        state.dynamicColor,
    ) {
        val wallpaper: ((Boolean) -> ColorScheme)? =
            if (
                state.dynamicColor &&
                state.accentSeed == Color.Unspecified &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            ) {
                { isDark ->
                    if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                }
            } else {
                null
            }
        if (look.isBundle) {
            youniversalLookScheme(look, systemDark, wallpaper)
        } else {
            youniversalScheme(
                backgroundStyle = state.backgroundStyle,
                contrast = state.contrast,
                accentSeed = state.accentSeed,
                isSystemDark = systemDark,
                dynamicScheme = wallpaper,
            )
        }
    }
    val cornerScale = if (look.isBundle) look.cornerScale else state.cornerScale
    val shape = RoundedCornerShape((10 * cornerScale).dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(76.dp)
                .clip(shape)
                .background(scheme.background, shape)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) BonyColors.Accent else BonyColors.Rule,
                    shape,
                ),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 8.dp, end = 26.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.72f)
                        .height(6.dp)
                        .background(scheme.onSurface.copy(alpha = 0.92f), appCornerShape(MaterialTheme.shapes.extraSmall, RoundedCornerShape(3.dp))),
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.95f)
                        .height(4.dp)
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.7f), appCornerShape(MaterialTheme.shapes.extraSmall, RoundedCornerShape(2.dp))),
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.58f)
                        .height(4.dp)
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.55f), appCornerShape(MaterialTheme.shapes.extraSmall, RoundedCornerShape(2.dp))),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp)
                    .size(10.dp)
                    .background(scheme.primary, CircleShape),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = look.label,
            style = BonyType.tag.copy(
                color = if (selected) BonyColors.Accent else BonyColors.TextMute,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun SettingsPrivacySection(viewModel: SettingsViewModel, context: Context, torEnabled: Boolean, pinEnabled: Boolean, screenshotBlockEnabled: Boolean, duressPinEnabled: Boolean, onPinSetup: () -> Unit, onDuressPinSetup: () -> Unit, scrambleAppPinKeypad: Boolean, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Privacy section ───────────────────────────────────────────────────
    SettingsCategoryHeader("PRIVACY", compact, expanded, onToggle)
    if (compact && !expanded) return
    val confirmLinks by viewModel.confirmExternalLinks.collectAsStateWithLifecycle()
    SettingsSwitchRow(
        title = "confirm before opening links",
        description = "ask before opening an external link in another app",
        checked = confirmLinks,
        onCheckedChange = viewModel::setConfirmExternalLinks,
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

SettingsAnchor("orbot") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .then(
                if (!viewModel.orbotInstalled)
                    Modifier.clickable {
                        if (!openOrbotInstallPage(context)) {
                            android.widget.Toast.makeText(context, "No app could open the Orbot installation page", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp), "orbot"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("route via tor", style = BonyType.body.copy(color = BonyColors.Text))
            if (!viewModel.orbotInstalled) {
                Text(
                    "install orbot from its official download page",
                    style = BonyType.meta.copy(color = BonyColors.Link),
                )
            }
        }
        BonyToggle(
            checked = torEnabled,
            enabled = viewModel.orbotInstalled,
            onCheckedChange = { viewModel.setTorEnabled(it) },
        )
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // This is a preflight check, not continuous monitoring of active requests.
SettingsAnchor("orbot_check_upload") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp), "orbot_check_upload"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("check Orbot before upload", style = BonyType.body.copy(color = BonyColors.Text))
            Text("checks Orbot before Blossom uploads; not a network-wide kill switch and active uploads are not monitored", style = BonyType.meta.copy(color = BonyColors.TextMute))
        }
        val killSwitch by viewModel.torKillSwitch.collectAsStateWithLifecycle()
        BonyToggle(checked = killSwitch, enabled = torEnabled, onCheckedChange = viewModel::setTorKillSwitch)
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

SettingsAnchor("app_lock") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .clickable { if (pinEnabled) viewModel.disablePin() else onPinSetup() }
            .padding(horizontal = 14.dp, vertical = 12.dp), "app_lock"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("app lock (PIN)", style = BonyType.body.copy(color = BonyColors.Text))
            if (pinEnabled) {
                Text(
                    "tap to disable",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
        }
        BonyToggle(
            checked = pinEnabled,
            enabled = true,
            onCheckedChange = { enabled ->
                if (enabled) onPinSetup() else viewModel.disablePin()
            },
        )
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    if (pinEnabled) {
        val pinAutoLockMinutes by viewModel.pinAutoLockMinutes.collectAsStateWithLifecycle()
    SettingsAnchor("auto_lock") {
        AutoLockMinutesRow(
            currentMinutes = pinAutoLockMinutes,
            onSelect = viewModel::setPinAutoLockMinutes,
            title = "app auto-lock timeout",
            description = "require PIN after the app has been in the background this long",
        )
    }
    }

SettingsAnchor("scramble_keypad") {
    SettingsSwitchRow(
        title = "scramble app PIN keypad",
        description = "randomize digit positions for each PIN entry",
        checked = scrambleAppPinKeypad,
        onCheckedChange = viewModel::setAppPinKeypadScrambled,
    )
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // ── Screenshot blocker (app-wide) ────────────────────────────────────
SettingsAnchor("screenshot_block") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp), "screenshot_block"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("screenshot blocker", style = BonyType.body.copy(color = BonyColors.Text))
            Text(
                text = "prevents screenshots of the whole app",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
        BonyToggle(
            checked = screenshotBlockEnabled,
            enabled = true,
            onCheckedChange = viewModel::setScreenshotBlockEnabled,
        )
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // ── Duress pin ────────────────────────────────────────────────────────
SettingsAnchor("duress_pin") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .clickable {
                if (duressPinEnabled) viewModel.disableDuressPin()
                else onDuressPinSetup()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp), "duress_pin"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("duress pin", style = BonyType.body.copy(color = BonyColors.Text))
            Text(
                text = when {
                    duressPinEnabled -> "entering it attempts to clear local app data · tap to disable"
                    else -> "a 4-digit pin that attempts to clear local app data instead of unlocking · tap to set"
                },
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
            Text(
                "Pending legacy mini-app browser cleanup must finish before the wipe can complete. Exported or shared copies may remain; deletion is not secure erasure.",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )
        }
        BonyToggle(
            checked = duressPinEnabled,
            enabled = true,
            onCheckedChange = { enabled ->
                if (enabled) onDuressPinSetup() else viewModel.disableDuressPin()
            },
        )
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsStealthSection(viewModel: SettingsViewModel, compact: Boolean, expanded: Boolean, onToggle: () -> Unit, onShowPopup: () -> Unit) {
    SettingsCategoryHeader("STEALTH", compact, expanded, onToggle)
    if (compact && !expanded) return

    val notepadEnabled by viewModel.notepadFrontEnabled.collectAsStateWithLifecycle()

SettingsAnchor("notepad_front") {
    SettingsSwitchRow(
        title = "Notepad front",
        description = "Disguise app as Notepad on launcher · decoy notes only · tap key storage 10× in encryption screen to unlock full app",
        checked = notepadEnabled,
        onCheckedChange = { enabled ->
            viewModel.setNotepadFrontEnabled(enabled)
            if (enabled) onShowPopup()
        },
    )
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    if (notepadEnabled) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Decoy vault active", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    "Real notes are hidden while Notepad icon is on · unlock via key storage taps",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
    }
}

@Composable
private fun SettingsDebugSection(viewModel: SettingsViewModel, context: Context, accounts: List<Account>, activeAccount: Account?, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Debug section ─────────────────────────────────────────────────────
    SettingsCategoryHeader("DEBUG", compact, expanded, onToggle)
    if (compact && !expanded) return

    var showShareLogsDialog by remember { mutableStateOf(false) }

SettingsAnchor("share_logs") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .clickable { showShareLogsDialog = true }
            .padding(horizontal = 14.dp, vertical = 12.dp), "share_logs"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("share logs", style = BonyType.body.copy(color = BonyColors.TextDim), modifier = Modifier.weight(1f))
        Text("↗", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    if (showShareLogsDialog) {
        ShareLogsDialog(
            logRepository = viewModel.logRepository,
            context = context,
            onDismiss = { showShareLogsDialog = false },
        )
    }

SettingsAnchor("version") {
    Row(
        modifier = searchRowHighlight(Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp), "version"),
    ) {
        Text("version  ${social.tbone.BuildConfig.VERSION_NAME} (${social.tbone.BuildConfig.VERSION_CODE})", style = BonyType.meta.copy(color = BonyColors.TextMute))
    }
}
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // Sign out — in Danger color, only if multiple accounts
    if (accounts.size > 1) {
        activeAccount?.let { acct ->
        SettingsAnchor("sign_out") {
            Row(
                modifier = searchRowHighlight(Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.removeAccount(acct.pubkey) }
                    .padding(horizontal = 14.dp, vertical = 12.dp), "sign_out"),
            ) {
                Text("sign out", style = BonyType.body.copy(color = BonyColors.Danger))
            }
        }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        }
    }
}

@Composable
private fun ShareLogsDialog(
    logRepository: LogRepository,
    context: Context,
    onDismiss: () -> Unit,
) {
    var selectedRange by remember { mutableStateOf(LogTimeRange.LAST_DAY) }
    var crashesOnly by remember { mutableStateOf(false) }
    // Performance numbers are a separate stream from crash data: different
    // question, different content, no message text or keys either way.
    var includePerformance by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<LogPreviewCounts?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedRange, crashesOnly, includePerformance) {
        preview = null
        val opts = LogExportOptions(
            range = selectedRange,
            crashesOnly = crashesOnly,
            includePerformance = includePerformance,
        )
        preview = withContext(Dispatchers.IO) {
            runCatching { logRepository.previewCounts(opts) }.getOrNull()
        }
    }

    val rangeRows = remember {
        listOf(
            listOf(LogTimeRange.LAST_DAY, LogTimeRange.LAST_WEEK),
            listOf(LogTimeRange.LAST_MONTH, LogTimeRange.ALL_TIME),
        )
    }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        containerColor = BonyColors.Surface,
        title = {
            Text("share logs", style = BonyType.body.copy(color = BonyColors.Text))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("TIME RANGE", style = BonyType.tag.copy(color = BonyColors.TextMute))
                    rangeRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { range ->
                                val selected = range == selectedRange
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(
                                            if (selected) BonyColors.Accent.copy(alpha = 0.12f) else Color.Transparent,
                                        )
                                        .appBorder(
                                            1.dp,
                                            if (selected) BonyColors.Accent else BonyColors.Rule,
                                        )
                                        .clickable(enabled = !isExporting) { selectedRange = range }
                                        .padding(horizontal = 6.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = range.label,
                                        style = BonyType.meta.copy(
                                            color = if (selected) BonyColors.Accent else BonyColors.TextDim,
                                        ),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isExporting) { crashesOnly = !crashesOnly }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("crashes only", style = BonyType.body.copy(color = BonyColors.Text))
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (crashesOnly) {
                                "uncaught crashes, system kills & errors only"
                            } else {
                                "crashes plus cleaned, trimmed activity logs"
                            },
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    BonyToggle(
                        checked = crashesOnly,
                        enabled = !isExporting,
                        onCheckedChange = { crashesOnly = it },
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isExporting) { includePerformance = !includePerformance }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("performance", style = BonyType.body.copy(color = BonyColors.Text))
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (includePerformance) {
                                "startup timings & counters — separate from crash data"
                            } else {
                                "crashes and logs only, no performance section"
                            },
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    BonyToggle(
                        checked = includePerformance,
                        enabled = !isExporting,
                        onCheckedChange = { includePerformance = it },
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .appBorder(1.dp, BonyColors.Rule)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = preview?.summaryLine(crashesOnly) ?: "scanning logs…",
                        style = BonyType.meta.copy(
                            color = if ((preview?.totalCrashSignals ?: 0) > 0) BonyColors.Accent else BonyColors.TextDim,
                        ),
                    )
                }

                Text(
                    text = "may include relay URLs, short key prefixes & crash details. " +
                        "performance adds only timings & counts. share only with trusted parties.",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isExporting,
                onClick = {
                    isExporting = true
                    val options = LogExportOptions(
                        range = selectedRange,
                        crashesOnly = crashesOnly,
                        maxChars = LogFormatter.DEFAULT_SHARE_MAX_CHARS,
                        includePerformance = includePerformance,
                    )
                    scope.launch {
                        val intent = withContext(Dispatchers.IO) {
                            runCatching { logRepository.buildShareIntent(options) }.getOrNull()
                        }
                        isExporting = false
                        if (intent != null) {
                            onDismiss()
                            runCatching {
                                context.startActivity(Intent.createChooser(intent, "Share logs via"))
                            }.onFailure {
                                Toast.makeText(context, "Could not open share sheet.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Could not prepare logs.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) {
                Text("SHARE", style = BonyType.button.copy(color = BonyColors.Accent))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !isExporting,
                    onClick = onDismiss,
                ) {
                    Text("CANCEL", style = BonyType.button.copy(color = BonyColors.TextMute))
                }
                TextButton(
                    enabled = !isExporting,
                    onClick = {
                        isExporting = true
                        val options = LogExportOptions(
                            range = selectedRange,
                            crashesOnly = crashesOnly,
                            maxChars = LogFormatter.DEFAULT_CLIPBOARD_MAX_CHARS,
                        )
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                runCatching { logRepository.buildLogText(options) }.getOrNull()
                            }
                            isExporting = false
                            if (text != null) {
                                val copied = runCatching {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val label = if (crashesOnly) "T-Bone crashes" else "T-Bone logs"
                                    cm.setPrimaryClip(ClipData.newPlainText(label, text))
                                }.isSuccess
                                if (copied) {
                                    val kind = if (crashesOnly) "crashes" else "logs"
                                    Toast.makeText(
                                        context,
                                        "Copied $kind (${selectedRange.label})",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, "Could not copy to clipboard.", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Could not prepare logs.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Text("COPY", style = BonyType.button.copy(color = BonyColors.Text))
                }
            }
        },
    )
}


@Composable
private fun AccountSettingsRow(account: Account, onManage: () -> Unit) {
    val phrase = remember(account.pubkey) { Phrase.wordsFor(account.pubkey, 3).joinToString("·") }
    val signerLabel = when (account.signerType) {
        SignerType.AMBER -> "amber · NIP-55"
        SignerType.NSEC_BUNKER -> "bunker · NIP-46"
        SignerType.LOCAL_KEY -> "keystore · NIP-06"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onManage() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DotAvatar(
            pubkeyHex = account.pubkey,
            displayName = account.displayName,
            size = 36.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            account.displayName?.let { name ->
                Text(name, style = BonyType.bodyDim.copy(color = BonyColors.Text), maxLines = 1)
            }
            Text(phrase, style = BonyType.metaDim.copy(color = BonyColors.TextMute), maxLines = 1)
            Text(signerLabel, style = BonyType.meta.copy(color = BonyColors.Accent))
        }
        Text("↻", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}

@Composable
private fun SettingsCategoryHeader(
    title: String,
    compact: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    if (!compact) {
        SectionHeader(title)
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.SurfaceAlt)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = BonyType.caption.copy(color = BonyColors.TextMute))
        Spacer(Modifier.weight(1f))
        Text(
            text = if (expanded) "⌄" else "›",
            style = BonyType.body.copy(color = BonyColors.TextMute),
        )
    }
    if (!expanded) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = BonyType.body.copy(color = BonyColors.Text))
            Spacer(Modifier.height(2.dp))
            Text(description, style = BonyType.meta.copy(color = BonyColors.TextMute))
        }
        Spacer(Modifier.width(10.dp))
        BonyToggle(checked = checked, enabled = true, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun BonyToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (YouniversalTheme.enabled) {
        // Kit swap: a real switch under Youniversal; classic keeps the ◉/○ chip.
        YouniversalSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        return
    }
    val label = if (checked) "◉ ON" else "○ OFF"
    val color = if (!enabled) BonyColors.TextMute else if (checked) BonyColors.Accent else BonyColors.TextMute

    Box(
        modifier = Modifier
            .appBorder(1.dp, if (checked && enabled) BonyColors.AccentDim else BonyColors.Rule)
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = BonyType.tag.copy(color = color))
    }
}

// Keep the RelayStatusDot used by other screens
@Composable
fun RelayStatusDot(status: social.tbone.nostr.relay.RelayStatus) {
    // Fixed semantic colors — relay health must never follow the user's
    // custom accent color (green always means connected).
    val color = when (status) {
        social.tbone.nostr.relay.RelayStatus.CONNECTED -> Color(0xFF4CAF50)
        social.tbone.nostr.relay.RelayStatus.CONNECTING -> Color(0xFFFFC107)
        social.tbone.nostr.relay.RelayStatus.DISCONNECTED -> Color(0xFFF44336)
    }
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(color, shape = androidx.compose.foundation.shape.CircleShape),
    )
}
