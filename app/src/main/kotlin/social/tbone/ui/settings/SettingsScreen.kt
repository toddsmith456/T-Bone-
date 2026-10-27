package social.tbone.ui.settings

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
import social.tbone.settings.ImageLoadMode
import social.tbone.settings.VideoPlaybackMode
import social.tbone.settings.VideoQuality
import social.tbone.settings.ORBOT_ZAPSTORE_URL
import social.tbone.settings.ThemeMode
import social.tbone.settings.UiTheme
import dev.youniversal.theme.YouniversalBackgroundStyle
import dev.youniversal.theme.YouniversalContrast
import dev.youniversal.theme.YouniversalThemeState
import dev.youniversal.theme.YouniversalFontOption
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import social.tbone.ui.theme.ThemeViewModel
import social.tbone.ui.components.DotAvatar
import social.tbone.ui.components.SectionHeader
import social.tbone.ui.components.LocalUiDeclutter
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appCornerShape
import social.tbone.ui.theme.colorToHex
import social.tbone.ui.theme.parseHexColor
import androidx.compose.runtime.remember
import social.tbone.account.SignerType

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onAccountManagement: () -> Unit,
    onRelayManagement: () -> Unit,
    onContentFilters: () -> Unit = {},
    onPinSetup: () -> Unit = {},
    onDuressPinSetup: () -> Unit = {},
    onBlossom: () -> Unit = {},
    onOfflineList: (String) -> Unit = {},
    onFollowLists: () -> Unit = {},
    onProfileEdit: () -> Unit = {},
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
    val avatarMode by viewModel.avatarMode.collectAsStateWithLifecycle()
    val avatarAnimated by viewModel.avatarAnimated.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
    val uiTheme by viewModel.uiTheme.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    var showAccentSheet by remember { mutableStateOf(false) }
    var showNotepadPopup by remember { mutableStateOf(false) }
    val themeViewModel: ThemeViewModel = hiltViewModel()
    val youniversalState = themeViewModel.youniversalState
    val declutterEnabled by themeViewModel.uiDeclutterEnabled.collectAsStateWithLifecycle()
    val declutterMode = LocalUiDeclutter.current
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    fun sectionExpanded(id: String): Boolean = !declutterMode || expandedSections[id] == true
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
        SettingsSwitchRow(
            title = "UI declutter",
            description = "compact tabs and make Settings categories collapsible",
            checked = declutterEnabled,
            onCheckedChange = themeViewModel::setUiDeclutterEnabled,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        TorActiveStrip(torEnabled)
        SettingsAccountSection(
            activeAccount, onAddAccount, onAccountManagement,
            compact = declutterMode, expanded = sectionExpanded("account"),
            onToggle = { toggleSection("account") },
        )
        SettingsRelaySection(
            onRelayManagement, compact = declutterMode, expanded = sectionExpanded("relays"),
            onToggle = { toggleSection("relays") },
        )
        SettingsFollowListsLink(
            onFollowLists = onFollowLists,
            compact = declutterMode, expanded = sectionExpanded("lists"),
            onToggle = { toggleSection("lists") },
        )
        SettingsContentFiltersSection(
            onContentFilters, compact = declutterMode, expanded = sectionExpanded("content"),
            onToggle = { toggleSection("content") },
        )
        SettingsNoteActionsSection(
            noteMenuModerationEnabled, clientTagEnabled, viewModel,
            compact = declutterMode, expanded = sectionExpanded("note_actions"),
            onToggle = { toggleSection("note_actions") },
        )
        ReactionSettingsSection(
            viewModel, compact = declutterMode, expanded = sectionExpanded("reactions"),
            onToggle = { toggleSection("reactions") },
        )
        SettingsMediaSection(
            viewModel = viewModel,
            onProfileEdit = onProfileEdit,
            onBlossom = onBlossom,
            imageLoadMode = imageLoadMode,
            videoQuality = videoQuality,
            videoPlaybackMode = videoPlaybackMode,
            videoThumbnails = videoThumbnails,
            compact = declutterMode,
            expanded = sectionExpanded("media"),
            onToggle = { toggleSection("media") },
        )
        SettingsAvatarSection(
            viewModel, avatarMode, avatarAnimated,
            compact = declutterMode, expanded = sectionExpanded("avatars"),
            onToggle = { toggleSection("avatars") },
        )
        SettingsThemeEngineSection(
            viewModel, uiTheme,
            compact = declutterMode, expanded = sectionExpanded("theme_engine"),
            onToggle = { toggleSection("theme_engine") },
        )
        SettingsAppearanceSection(
            viewModel = viewModel,
            uiTheme = uiTheme,
            themeMode = themeMode,
            accentColor = accentColor,
            youniversalState = youniversalState,
            onChooseAccent = { showAccentSheet = true },
            compact = declutterMode,
            expanded = sectionExpanded("appearance"),
            onToggle = { toggleSection("appearance") },
        )
        SettingsPrivacySection(
            viewModel = viewModel,
            context = context,
            torEnabled = torEnabled,
            pinEnabled = pinEnabled,
            screenshotBlockEnabled = screenshotBlockEnabled,
            duressPinEnabled = duressPinEnabled,
            onPinSetup = onPinSetup,
            onDuressPinSetup = onDuressPinSetup,
            scrambleAppPinKeypad = scrambleAppPinKeypad,
            compact = declutterMode,
            expanded = sectionExpanded("privacy"),
            onToggle = { toggleSection("privacy") },
        )
        SettingsStealthSection(
            viewModel = viewModel,
            compact = declutterMode, expanded = sectionExpanded("stealth"),
            onToggle = { toggleSection("stealth") },
            onShowPopup = { showNotepadPopup = true },
        )
        SettingsDebugSection(
            viewModel, context, accounts, activeAccount,
            compact = declutterMode, expanded = sectionExpanded("debug"),
            onToggle = { toggleSection("debug") },
        )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(BonyColors.Warn, shape = androidx.compose.foundation.shape.CircleShape),
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
        AccountSettingsRow(account = account, onManage = onAccountManagement)
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAddAccount() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("+", style = BonyType.body.copy(color = BonyColors.TextDim))
        Spacer(Modifier.width(10.dp))
        Text("add account", style = BonyType.body.copy(color = BonyColors.TextDim))
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsRelaySection(onRelayManagement: () -> Unit, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Relays section ────────────────────────────────────────────────────
    SettingsCategoryHeader("RELAYS", compact, expanded, onToggle)
    if (compact && !expanded) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRelayManagement() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("manage relays", style = BonyType.body.copy(color = BonyColors.TextDim), modifier = Modifier.weight(1f))
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsFollowListsLink(onFollowLists: () -> Unit, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Follow lists tool (moved out of settings) ───────────────────────────
    SettingsCategoryHeader("LISTS", compact, expanded, onToggle)
    if (compact && !expanded) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onFollowLists() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("follow lists", style = BonyType.body.copy(color = BonyColors.TextDim))
            Text(
                text = "following · followers · blocked — offline or relay",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsContentFiltersSection(onContentFilters: () -> Unit, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Content filters section ───────────────────────────────────────────
    SettingsCategoryHeader("CONTENT", compact, expanded, onToggle)
    if (compact && !expanded) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onContentFilters() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsNoteActionsSection(noteMenuModerationEnabled: Boolean, clientTagEnabled: Boolean, viewModel: SettingsViewModel, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Note actions and event attribution ─────────────────────────────────
    SettingsCategoryHeader("NOTE ACTIONS", compact, expanded, onToggle)
    if (compact && !expanded) return
    SettingsSwitchRow(
        title = "show block/report actions",
        description = "Adds block poster and public NIP-56 report to note menus · off by default",
        checked = noteMenuModerationEnabled,
        onCheckedChange = viewModel::setNoteMenuModerationEnabled,
    )
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
    SettingsSwitchRow(
        title = "add T-Bone client tag",
        description = "Adds a public NIP-89 client tag to events you sign · on by default",
        checked = clientTagEnabled,
        onCheckedChange = viewModel::setClientTagEnabled,
    )
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // Screen time now lives inside the pin-locked Content Filters folder.
}

@Composable
private fun SettingsMediaSection(viewModel: SettingsViewModel, onProfileEdit: () -> Unit, onBlossom: () -> Unit, imageLoadMode: ImageLoadMode, videoQuality: VideoQuality, videoPlaybackMode: VideoPlaybackMode, videoThumbnails: Boolean, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Media section ─────────────────────────────────────────────────────
    SettingsCategoryHeader("MEDIA", compact, expanded, onToggle)
    if (compact && !expanded) return

    // Profile picture & banner upload.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onProfileEdit() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("profile picture & banner", style = BonyType.body.copy(color = BonyColors.TextDim), modifier = Modifier.weight(1f))
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // Blossom upload servers.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onBlossom() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("blossom uploads", style = BonyType.body.copy(color = BonyColors.TextDim), modifier = Modifier.weight(1f))
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // Video playback is kept separate from image loading because videos can
    // consume substantially more bandwidth and battery than a still image.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
        Text("video playback", style = BonyType.body.copy(color = BonyColors.Text))
        Spacer(Modifier.height(2.dp))
        Text(
            "These choices never loop videos. Tapping any video opens T-Bone's player.",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(8.dp))
        VideoPlaybackMode.entries.forEach { mode ->
            val active = mode == videoPlaybackMode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, appCornerShape(MaterialTheme.shapes.small))
                    .clickable { viewModel.setVideoPlaybackMode(mode) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (active) "●" else "○",
                    style = BonyType.body.copy(color = if (active) BonyColors.Accent else BonyColors.TextMute),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = mode.label,
                    style = BonyType.meta.copy(color = if (active) BonyColors.Accent else BonyColors.TextDim),
                )
            }
            Spacer(Modifier.height(5.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("video thumbnails", style = BonyType.body.copy(color = BonyColors.TextDim))
                Text(
                    "show a preview for videos that wait for a tap to load",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            BonyToggle(
                checked = videoThumbnails,
                enabled = videoQuality != VideoQuality.OFF && videoPlaybackMode == VideoPlaybackMode.LOAD_ON_TAP,
                onCheckedChange = { viewModel.setVideoThumbnails(it) },
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsAvatarSection(viewModel: SettingsViewModel, avatarMode: AvatarMode, avatarAnimated: Boolean, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Avatars section ───────────────────────────────────────────────────
    SettingsCategoryHeader("AVATARS", compact, expanded, onToggle)
    if (compact && !expanded) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
        Row(
            modifier = Modifier.fillMaxWidth(),
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
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsThemeEngineSection(viewModel: SettingsViewModel, uiTheme: UiTheme, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Theme engine (Bony vs Youniversal) ──────────────────────────────
    SettingsCategoryHeader("THEME ENGINE", compact, expanded, onToggle)
    if (compact && !expanded) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsAppearanceSection(viewModel: SettingsViewModel, uiTheme: UiTheme, themeMode: ThemeMode, accentColor: String?, youniversalState: YouniversalThemeState, onChooseAccent: () -> Unit, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Appearance section ────────────────────────────────────────────────
    SettingsCategoryHeader("APPEARANCE", compact, expanded, onToggle)
    if (compact && !expanded) return

    if (uiTheme == UiTheme.BONY) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            // App-wide typeface. Standard is intentionally the Youniversal
            // default; Soft provides a warmer, rounded sans-serif alternative.
            Text("app font", style = BonyType.meta.copy(color = BonyColors.TextMute))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                YouniversalFontOption.entries.forEach { option ->
                    val active = option == youniversalState.fontOption
                    val shape = appCornerShape(MaterialTheme.shapes.small)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(shape)
                            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, shape)
                            .clickable { youniversalState.setFontOption(option) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
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

            // Background
            Text("background", style = BonyType.meta.copy(color = BonyColors.TextMute))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                YouniversalBackgroundStyle.entries.forEach { style ->
                    val active = style == youniversalState.backgroundStyle
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, appCornerShape(MaterialTheme.shapes.small))
                            .clickable { youniversalState.setBackgroundStyle(style) }
                            .padding(vertical = 8.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            // Font scale
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            // Animate
            Row(
                modifier = Modifier.fillMaxWidth(),
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
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsPrivacySection(viewModel: SettingsViewModel, context: Context, torEnabled: Boolean, pinEnabled: Boolean, screenshotBlockEnabled: Boolean, duressPinEnabled: Boolean, onPinSetup: () -> Unit, onDuressPinSetup: () -> Unit, scrambleAppPinKeypad: Boolean, compact: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    // ── Privacy section ───────────────────────────────────────────────────
    SettingsCategoryHeader("PRIVACY", compact, expanded, onToggle)
    if (compact && !expanded) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!viewModel.orbotInstalled)
                    Modifier.clickable {
                        if (ORBOT_ZAPSTORE_URL.startsWith("https://")) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ORBOT_ZAPSTORE_URL))) }
                        }
                    }
                else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("route via tor", style = BonyType.body.copy(color = BonyColors.Text))
            if (!viewModel.orbotInstalled) {
                Text(
                    "install orbot on zapstore",
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
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (pinEnabled) viewModel.disablePin() else onPinSetup() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    SettingsSwitchRow(
        title = "scramble app PIN keypad",
        description = "randomize digit positions for each PIN entry",
        checked = scrambleAppPinKeypad,
        onCheckedChange = viewModel::setAppPinKeypadScrambled,
    )
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // ── Screenshot blocker (app-wide) ────────────────────────────────────
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // ── Duress pin ────────────────────────────────────────────────────────
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (duressPinEnabled) viewModel.disableDuressPin()
                else onDuressPinSetup()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("duress pin", style = BonyType.body.copy(color = BonyColors.Text))
            Text(
                text = when {
                    duressPinEnabled -> "entering it on the lock screen wipes all app data · tap to disable"
                    else -> "a 4-digit pin that wipes all app data instead of unlocking · tap to set"
                },
                style = BonyType.meta.copy(color = BonyColors.TextMute),
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
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SettingsStealthSection(viewModel: SettingsViewModel, compact: Boolean, expanded: Boolean, onToggle: () -> Unit, onShowPopup: () -> Unit) {
    SettingsCategoryHeader("STEALTH", compact, expanded, onToggle)
    if (compact && !expanded) return

    val notepadEnabled by viewModel.notepadFrontEnabled.collectAsStateWithLifecycle()

    SettingsSwitchRow(
        title = "Notepad front",
        description = "Disguise app as Notepad on launcher · decoy notes only · tap key storage 10× in encryption screen to unlock full app",
        checked = notepadEnabled,
        onCheckedChange = { enabled ->
            viewModel.setNotepadFrontEnabled(enabled)
            if (enabled) onShowPopup()
        },
    )
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent.createChooser(viewModel.logRepository.buildShareIntent(), "Share logs via")
                context.startActivity(intent)
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("share logs", style = BonyType.body.copy(color = BonyColors.TextDim), modifier = Modifier.weight(1f))
        Text("↗", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("version  ${social.tbone.BuildConfig.VERSION_NAME} (${social.tbone.BuildConfig.VERSION_CODE})", style = BonyType.meta.copy(color = BonyColors.TextMute))
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    // Sign out — in Danger color, only if multiple accounts
    if (accounts.size > 1) {
        activeAccount?.let { acct ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.removeAccount(acct.pubkey) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text("sign out", style = BonyType.body.copy(color = BonyColors.Danger))
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        }
    }
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
