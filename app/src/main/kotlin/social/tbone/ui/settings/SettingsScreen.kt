package social.tbone.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import social.tbone.settings.ORBOT_ZAPSTORE_URL
import social.tbone.settings.ThemeMode
import social.tbone.settings.UiTheme
import dev.youniversal.theme.YouniversalBackgroundStyle
import dev.youniversal.theme.YouniversalContrast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import social.tbone.ui.theme.ThemeViewModel
import social.tbone.ui.components.DotAvatar
import social.tbone.ui.components.SectionHeader
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
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
    onScreenTime: () -> Unit = {},
    onProfileEdit: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val torEnabled by viewModel.torEnabled.collectAsStateWithLifecycle()
    val pinEnabled by viewModel.pinEnabled.collectAsStateWithLifecycle()
    val duressPinEnabled by viewModel.duressPinEnabled.collectAsStateWithLifecycle()
    val screenshotBlockEnabled by viewModel.screenshotBlockEnabled.collectAsStateWithLifecycle()
    val imageLoadMode by viewModel.imageLoadMode.collectAsStateWithLifecycle()
    val avatarMode by viewModel.avatarMode.collectAsStateWithLifecycle()
    val avatarAnimated by viewModel.avatarAnimated.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
    val uiTheme by viewModel.uiTheme.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    var showAccentSheet by remember { mutableStateOf(false) }
    val themeViewModel: ThemeViewModel = hiltViewModel()
    val youniversalState = themeViewModel.youniversalState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
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

        // ── Tor active strip ──────────────────────────────────────────────────
        if (torEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.WarnBg)
                    .border(
                        width = 1.dp,
                        color = BonyColors.WarnDim,
                        shape = RectangleShape,
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
                                .border(1.dp, BonyColors.WarnDim)
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

        // ── Account section ───────────────────────────────────────────────────
        SectionHeader("ACCOUNT")

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

        // ── Relays section ────────────────────────────────────────────────────
        SectionHeader("RELAYS")

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

        // ── Content filters section ───────────────────────────────────────────
        SectionHeader("CONTENT")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onContentFilters() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("content filters", style = BonyType.body.copy(color = BonyColors.TextDim), modifier = Modifier.weight(1f))
            Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        // Parental screen-time limit.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onScreenTime() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("screen time", style = BonyType.body.copy(color = BonyColors.TextDim), modifier = Modifier.weight(1f))
            Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        // ── Media section ─────────────────────────────────────────────────────
        SectionHeader("MEDIA")

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
                            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule)
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

        // ── Avatars section ───────────────────────────────────────────────────
        SectionHeader("AVATARS")

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
                            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule)
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

        // ── Theme engine (Bony vs Youniversal) ──────────────────────────────
        SectionHeader("THEME ENGINE")
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
                            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule)
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

        // ── Appearance section ────────────────────────────────────────────────
        SectionHeader("APPEARANCE")

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
                                .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule)
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
                        onClick = { showAccentSheet = true },
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

                // Background
                Text("background", style = BonyType.meta.copy(color = BonyColors.TextMute))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    YouniversalBackgroundStyle.entries.forEach { style ->
                        val active = style == youniversalState.backgroundStyle
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule)
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
                                .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule)
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
                        BonyButton(label = "−", primary = false, onClick = { youniversalState.setFontScale((youniversalState.fontScale - 0.1f).coerceIn(0.7f, 1.8f)) })
                        BonyButton(label = "+", primary = false, onClick = { youniversalState.setFontScale((youniversalState.fontScale + 0.1f).coerceIn(0.7f, 1.8f)) })
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
                        .border(1.dp, BonyColors.Rule)
                        .clickable { youniversalState.reset() }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text("RESET YOUNIVERSAL TO DEFAULTS", style = BonyType.tag.copy(color = BonyColors.TextMute))
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        // ── Privacy section ───────────────────────────────────────────────────
        SectionHeader("PRIVACY")

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

        // ── Debug section ─────────────────────────────────────────────────────
        SectionHeader("DEBUG")

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

        Spacer(Modifier.height(32.dp))
    }

    if (showAccentSheet) {
        AccentColorSheet(
            initial = parseHexColor(accentColor) ?: BonyColors.DefaultAccent,
            // Live preview while dragging — in-memory only, no disk writes.
            onPick = { color -> BonyColors.setAccent(color) },
            // Persist once when the sheet closes ("done" or swipe-away).
            onCommit = { color -> viewModel.setAccentColor(colorToHex(color)) },
            onReset = {
                BonyColors.setAccent(null)
                viewModel.setAccentColor(null)
            },
            onDismiss = { showAccentSheet = false },
        )
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
internal fun BonyToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val label = if (checked) "◉ ON" else "○ OFF"
    val color = if (!enabled) BonyColors.TextMute else if (checked) BonyColors.Accent else BonyColors.TextMute

    Box(
        modifier = Modifier
            .border(1.dp, if (checked && enabled) BonyColors.AccentDim else BonyColors.Rule)
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
