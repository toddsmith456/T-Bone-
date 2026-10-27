package social.tbone.ui.lists

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.lists.ListEntry
import social.tbone.nostr.Nip19
import social.tbone.nostr.identity.Phrase
import social.tbone.ui.components.SectionHeader
import social.tbone.ui.components.UserAvatar
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.settings.BonyToggle
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder
import social.tbone.ui.theme.appCornerShape
import androidx.compose.material3.MaterialTheme

enum class FollowListsTab { FOLLOWING, FOLLOWERS, BLOCKED }

@Composable
fun FollowListsScreen(
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    viewModel: FollowListsViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val followsOffline by viewModel.followsOfflineState.collectAsStateWithLifecycle()
    val mutesOffline by viewModel.mutesOfflineState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val displayedFollowing by viewModel.displayedFollowing.collectAsStateWithLifecycle()
    val onlineLoading by viewModel.onlineLoading.collectAsStateWithLifecycle()
    val onlineError by viewModel.onlineError.collectAsStateWithLifecycle()
    val effectiveMuted by viewModel.effectiveMuted.collectAsStateWithLifecycle()
    val followersEnabled by viewModel.followersEnabled.collectAsStateWithLifecycle()
    val followersDisclaimerShown by viewModel.followersDisclaimerShown.collectAsStateWithLifecycle()
    val followersState by viewModel.followers.collectAsStateWithLifecycle()
    val followersBusy by viewModel.followersBusy.collectAsStateWithLifecycle()
    val blockedOfflineEnabled by viewModel.offlineMutesEnabled.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(FollowListsTab.FOLLOWING) }
    var showFollowersDisclaimer by remember { mutableStateOf(false) }
    var pendingEnableFollowers by remember { mutableStateOf(false) }

    // Check if we need to show disclaimer when user toggles on
    LaunchedEffect(followersEnabled, followersDisclaimerShown) {
        if (followersEnabled && !followersDisclaimerShown && !showFollowersDisclaimer) {
            // Should have been shown before enabling; but if enabled without disclaimer, show it
            // We keep enabled but show popup once
            showFollowersDisclaimer = true
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            when (msg) {
                is FollowListsMessage.Text -> {
                    if (msg.undo != null) {
                        val r = snackbar.showSnackbar(msg.text, actionLabel = "UNDO", duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed) viewModel.undoRemove(msg.undo)
                    } else {
                        snackbar.showSnackbar(msg.text, duration = SnackbarDuration.Long)
                    }
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importFrom(uri) { parsed ->
            // Show import choice dialog via state
            // We'll handle via simple immediate apply: for now merge
            // To keep UI nice, we show dialog
            // Use a mutable state to hold pending import
        }
    }

    // Import chooser state
    var pendingImport by remember { mutableStateOf<social.tbone.lists.ListFileFormat.Parsed?>(null) }

    // Wrap import launcher to capture parsed
    val importLauncher2 = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importFrom(uri) { parsed -> pendingImport = parsed }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(BonyColors.Bg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("←", style = BonyType.body.copy(color = BonyColors.TextMute), modifier = Modifier.clickable { onBack() })
                Spacer(Modifier.width(8.dp))
                Text("follow lists", style = BonyType.body.copy(color = BonyColors.Text))
                Spacer(Modifier.weight(1f))
                if (followersEnabled) {
                    Text("${displayedFollowing.size} following", style = BonyType.meta.copy(color = BonyColors.TextMute))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

            if (account == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("no active account", style = BonyType.meta.copy(color = BonyColors.TextMute))
                }
                return@Column
            }

            // Tab strip
            Row(modifier = Modifier.fillMaxWidth().background(BonyColors.Surface)) {
                listOf(
                    FollowListsTab.FOLLOWING to "FOLLOWING",
                    FollowListsTab.FOLLOWERS to "FOLLOWERS",
                    FollowListsTab.BLOCKED to "BLOCKED",
                ).forEach { (t, label) ->
                    val active = tab == t
                    val shape = appCornerShape(MaterialTheme.shapes.small, RectangleShape)
                    Box(
                        modifier = Modifier.weight(1f).clickable { tab = t }
                            .background(if (active) BonyColors.AccentBg else BonyColors.Surface)
                            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule, shape)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = BonyType.tag.copy(color = if (active) BonyColors.Accent else BonyColors.TextMute))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

            when (tab) {
                FollowListsTab.FOLLOWING -> FollowingTab(
                    account = account!!,
                    followsOffline = followsOffline,
                    displayedFollowing = displayedFollowing,
                    onlineLoading = onlineLoading,
                    onlineError = onlineError,
                    profiles = profiles,
                    effectiveMuted = effectiveMuted,
                    viewModel = viewModel,
                    onProfileClick = onProfileClick,
                    exportLauncher = exportLauncher,
                    importLauncher = importLauncher2,
                    snackbarHost = snackbar,
                    onImportChoice = { pendingImport = it },
                )
                FollowListsTab.FOLLOWERS -> FollowersTab(
                    account = account!!,
                    followersEnabled = followersEnabled,
                    followersState = followersState,
                    followersBusy = followersBusy,
                    profiles = profiles,
                    effectiveMuted = effectiveMuted,
                    viewModel = viewModel,
                    onProfileClick = onProfileClick,
                    onEnableRequest = {
                        if (!followersDisclaimerShown) {
                            showFollowersDisclaimer = true
                            pendingEnableFollowers = true
                        } else {
                            viewModel.setFollowersEnabled(true)
                        }
                    },
                    onDisable = { viewModel.setFollowersEnabled(false) },
                )
                FollowListsTab.BLOCKED -> BlockedTab(
                    account = account!!,
                    mutesOffline = mutesOffline,
                    blockedOfflineEnabled = blockedOfflineEnabled,
                    profiles = profiles,
                    effectiveMuted = effectiveMuted,
                    viewModel = viewModel,
                    onProfileClick = onProfileClick,
                )
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())

        // Followers disclaimer one-time popup
        if (showFollowersDisclaimer) {
            AlertDialog(
                onDismissRequest = {
                    showFollowersDisclaimer = false
                    if (pendingEnableFollowers) {
                        viewModel.dismissFollowersDisclaimer()
                        pendingEnableFollowers = false
                    }
                },
                containerColor = BonyColors.Surface,
                title = { Text("Followers use Primal's cache", style = BonyType.body.copy(color = BonyColors.Text)) },
                text = {
                    Text(
                        "Follower list on Nostr is decentralized — relays don't reliably know who follows who. " +
                        "When you turn this on, T-Bone will ask Primal's centralized cache (relay.primal.net + cache2.primal.net) for your followers, exactly like Monostr does for follower counts via NIP-45.\n\n" +
                        "Primal sees your pubkey when you use this, just like any relay. No additional tracking. You can turn it off anytime.",
                        style = BonyType.meta.copy(color = BonyColors.TextDim)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showFollowersDisclaimer = false
                        pendingEnableFollowers = false
                        viewModel.confirmFollowersDisclaimer()
                    }) { Text("ENABLE", style = BonyType.button.copy(color = BonyColors.Accent)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showFollowersDisclaimer = false
                        pendingEnableFollowers = false
                        viewModel.dismissFollowersDisclaimer()
                    }) { Text("CANCEL", style = BonyType.button.copy(color = BonyColors.TextMute)) }
                }
            )
        }

        // Import choice dialog
        pendingImport?.let { parsed ->
            AlertDialog(
                onDismissRequest = { pendingImport = null },
                containerColor = BonyColors.Surface,
                title = { Text("Import ${parsed.entries.size} follows?", style = BonyType.body.copy(color = BonyColors.Text)) },
                text = {
                    Text(
                        buildString {
                            append("File has ${parsed.entries.size} follows")
                            if (parsed.skipped > 0) append(" (${parsed.skipped} skipped)")
                            append(".\n\nMERGE adds to your list.\nREPLACE swaps your list — current list will be lost.")
                        },
                        style = BonyType.meta.copy(color = BonyColors.TextDim)
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.applyImport(parsed, replace = false); pendingImport = null }) {
                        Text("MERGE", style = BonyType.button.copy(color = BonyColors.Accent))
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { viewModel.applyImport(parsed, replace = true); pendingImport = null }) {
                            Text("REPLACE", style = BonyType.button.copy(color = BonyColors.Danger))
                        }
                        TextButton(onClick = { pendingImport = null }) { Text("CANCEL", style = BonyType.button.copy(color = BonyColors.TextMute)) }
                    }
                }
            )
        }
    }
}

@Composable
private fun FollowingTab(
    account: social.tbone.account.Account,
    followsOffline: social.tbone.lists.LocalListState?,
    displayedFollowing: List<ListEntry>,
    onlineLoading: Boolean,
    onlineError: String?,
    profiles: Map<String, social.tbone.nostr.ProfileContent>,
    effectiveMuted: Set<String>,
    viewModel: FollowListsViewModel,
    onProfileClick: (String) -> Unit,
    exportLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    importLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    snackbarHost: SnackbarHostState,
    onImportChoice: (social.tbone.lists.ListFileFormat.Parsed) -> Unit,
) {
    var addInput by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    val enabled = followsOffline?.enabled == true
    val phrase = remember(account.pubkey) { Phrase.wordsFor(account.pubkey, 3).joinToString("·") }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            // Mode switch
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp)
                    .appBorder(1.dp, BonyColors.Rule).background(BonyColors.Surface).padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("use offline follow list", style = BonyType.body.copy(color = BonyColors.Text))
                        Text(
                            if (enabled) "on · fully local, relays not used" else "off · using relay list",
                            style = BonyType.meta.copy(color = if (enabled) BonyColors.Accent else BonyColors.TextMute)
                        )
                    }
                    if (onlineLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = BonyColors.Accent, strokeWidth = 1.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    BonyToggle(checked = enabled, enabled = !onlineLoading, onCheckedChange = { viewModel.onSwitchOfflineWant(it) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (enabled) "Your relay follow list is not read and never changed. FOLLOW/UNFOLLOW here edits this local list only."
                    else "Using your relay contact list (kind 3). FOLLOW/UNFOLLOW publishes to relays. Turn on offline to keep a local copy.",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute)
                )
                if (onlineError != null) {
                    Text(onlineError, style = BonyType.meta.copy(color = BonyColors.Warn), modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).appBorder(1.dp, BonyColors.Rule).background(BonyColors.Surface).padding(12.dp)
            ) {
                Text("${displayedFollowing.size} following stored", style = BonyType.body.copy(color = BonyColors.Text))
                Text("account: ${account.displayName?.let { "$it · " } ?: ""}$phrase", style = BonyType.metaDim.copy(color = BonyColors.TextMute))
                Text("tap avatar or name to open profile · follow/unfollow & mute from the list", style = BonyType.metaDim.copy(color = BonyColors.TextMute), modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BonyButton(label = "↻ REFRESH", primary = false, enabled = !enabled && !onlineLoading, onClick = { viewModel.refreshOnlineFollowing(account.pubkey, account.relays) }, modifier = Modifier.weight(1f))
                    BonyButton(label = "⇣ SYNC → OFFLINE", primary = false, enabled = enabled && !onlineLoading, onClick = { viewModel.refreshOnlineFollowing(account.pubkey, account.relays) }, modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp).appBorder(1.dp, BonyColors.Rule).background(BonyColors.Surface).padding(12.dp)) {
                Text("add by npub", style = BonyType.caption.copy(color = BonyColors.Accent), modifier = Modifier.padding(bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = addInput,
                        onValueChange = { addInput = it },
                        placeholder = { Text("npub1… or hex", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                        singleLine = true,
                        textStyle = BonyType.meta.copy(color = BonyColors.Text),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    val canAdd = addInput.isNotBlank()
                    Text(
                        "ADD",
                        style = BonyType.tag.copy(color = if (canAdd) BonyColors.Accent else BonyColors.TextMute),
                        modifier = Modifier.appBorder(1.dp, if (canAdd) BonyColors.AccentDim else BonyColors.Rule)
                            .clickable(enabled = canAdd) { viewModel.addByInput(addInput) { ok -> if (ok) addInput = "" } }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                }
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).appBorder(1.dp, BonyColors.Rule).background(BonyColors.Surface).padding(12.dp)) {
                Text("export / import", style = BonyType.caption.copy(color = BonyColors.Accent), modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BonyButton(label = "↑ EXPORT", primary = false, onClick = { exportLauncher.launch(viewModel.exportFileName()) }, modifier = Modifier.weight(1f))
                    BonyButton(label = "↓ IMPORT", primary = false, onClick = { importLauncher.launch(arrayOf("application/json","text/plain","*/*")) }, modifier = Modifier.weight(1f))
                }
                Text("Standard NIP-02 JSON — other apps can read it", style = BonyType.metaDim.copy(color = BonyColors.TextMute), modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            SectionHeader("FOLLOWING")
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("filter by name / npub", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                singleLine = true,
                textStyle = BonyType.meta.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        val q = filter.trim().lowercase()
        val shown = if (q.isEmpty()) displayedFollowing else displayedFollowing.filter { e ->
            val name = profiles[e.pubkey]?.bestName?.lowercase().orEmpty()
            name.contains(q) || e.petname.lowercase().contains(q) || e.pubkey.lowercase().contains(q) || Nip19.hexToNpub(e.pubkey).lowercase().contains(q)
        }
        if (shown.isEmpty()) {
            item { Text(if (displayedFollowing.isEmpty()) "no follows yet — add by npub or sync from relays" else "no matches", style = BonyType.meta.copy(color = BonyColors.TextMute), modifier = Modifier.padding(14.dp)) }
        } else {
            items(shown, key = { it.pubkey }) { entry ->
                FollowRow(
                    entry = entry,
                    name = profiles[entry.pubkey]?.bestName,
                    picture = profiles[entry.pubkey]?.picture,
                    isMuted = entry.pubkey in effectiveMuted,
                    onOpen = { onProfileClick(entry.pubkey) },
                    onUnfollow = { viewModel.remove(entry.pubkey) },
                    onToggleMute = { viewModel.toggleMute(entry.pubkey) },
                )
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun FollowersTab(
    account: social.tbone.account.Account,
    followersEnabled: Boolean,
    followersState: social.tbone.lists.FollowerListRepository.FollowersState?,
    followersBusy: Boolean,
    profiles: Map<String, social.tbone.nostr.ProfileContent>,
    effectiveMuted: Set<String>,
    viewModel: FollowListsViewModel,
    onProfileClick: (String) -> Unit,
    onEnableRequest: () -> Unit,
    onDisable: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("") }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp).appBorder(1.dp, BonyColors.Rule).background(BonyColors.Surface).padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("followers (Primal cache)", style = BonyType.body.copy(color = BonyColors.Text))
                        Text(
                            if (followersEnabled) "on · from relay.primal.net + counting relays" else "off · tap to enable",
                            style = BonyType.meta.copy(color = if (followersEnabled) BonyColors.Accent else BonyColors.TextMute)
                        )
                    }
                    BonyToggle(checked = followersEnabled, enabled = true, onCheckedChange = { wantOn -> if (wantOn) onEnableRequest() else onDisable() })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Off by default. When on, T-Bone asks Primal's centralized cache for who follows you — same approach Monostr uses for follower counts (largest NIP-45 COUNT wins, cached 5 min). The list may be incomplete; relays are the lower bound.",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute)
                )
                if (followersEnabled) {
                    Spacer(Modifier.height(10.dp))
                    BonyButton(label = "↻ REFRESH FOLLOWERS", primary = false, enabled = !followersBusy, onClick = { viewModel.refreshFollowers() }, modifier = Modifier.fillMaxWidth())
                    if (followersState?.source != null) {
                        Text("source: ${followersState.source} · ${followersState.followers?.size ?: 0} followers", style = BonyType.metaDim.copy(color = BonyColors.TextMute), modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        if (!followersEnabled) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("followers are off — turn on to load from Primal", style = BonyType.meta.copy(color = BonyColors.TextMute))
                }
            }
            return@LazyColumn
        }
        if (followersBusy && followersState?.followers == null) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BonyColors.Accent, strokeWidth = 1.dp) } }
            return@LazyColumn
        }
        val list = followersState?.followers.orEmpty()
        if (list.isEmpty()) {
            item { Text("no followers found (or Primal is unreachable) — try refresh", style = BonyType.meta.copy(color = BonyColors.TextMute), modifier = Modifier.padding(14.dp)) }
            return@LazyColumn
        }
        item {
            SectionHeader("FOLLOWERS · ${list.size}")
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("filter by name / npub", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                singleLine = true,
                textStyle = BonyType.meta.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        val q = filter.trim().lowercase()
        val shown = if (q.isEmpty()) list else list.filter { pk ->
            val name = profiles[pk]?.bestName?.lowercase().orEmpty()
            name.contains(q) || pk.lowercase().contains(q) || Nip19.hexToNpub(pk).lowercase().contains(q)
        }
        items(shown, key = { it }) { pk ->
            val isMuted = pk in effectiveMuted
            FollowRow(
                entry = ListEntry(pk),
                name = profiles[pk]?.bestName,
                picture = profiles[pk]?.picture,
                isMuted = isMuted,
                onOpen = { onProfileClick(pk) },
                onUnfollow = null, // Followers are not necessarily followed; show follow button if not following
                onToggleMute = { viewModel.toggleMute(pk) },
                isFollowing = null, // We could compute but keep simple: show follow button
                onFollow = { viewModel.follow(pk) },
            )
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun BlockedTab(
    account: social.tbone.account.Account,
    mutesOffline: social.tbone.lists.LocalListState?,
    blockedOfflineEnabled: Boolean,
    profiles: Map<String, social.tbone.nostr.ProfileContent>,
    effectiveMuted: Set<String>,
    viewModel: FollowListsViewModel,
    onProfileClick: (String) -> Unit,
) {
    // For blocked we reuse muteRepository.effectiveMuted as displayed list
    // But also show offline state for toggle
    var filter by rememberSaveable { mutableStateOf("") }
    val mutedList = effectiveMuted.toList()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp).appBorder(1.dp, BonyColors.Rule).background(BonyColors.Surface).padding(12.dp)
            ) {
                Text("${mutedList.size} blocked", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    if (blockedOfflineEnabled) "offline block list on · local only" else "using relay mute list (kind 10000)",
                    style = BonyType.meta.copy(color = if (blockedOfflineEnabled) BonyColors.Accent else BonyColors.TextMute)
                )
                Spacer(Modifier.height(8.dp))
                Text("Block/unblock on profiles edits ${if (blockedOfflineEnabled) "only this device" else "your relays"}. Manage blocked users here or from their profile.", style = BonyType.metaDim.copy(color = BonyColors.TextMute))
            }
        }
        item {
            SectionHeader("BLOCKED")
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("filter by name / npub", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                singleLine = true,
                textStyle = BonyType.meta.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        val q = filter.trim().lowercase()
        val shown = if (q.isEmpty()) mutedList else mutedList.filter { pk ->
            val name = profiles[pk]?.bestName?.lowercase().orEmpty()
            name.contains(q) || pk.lowercase().contains(q)
        }
        if (shown.isEmpty()) {
            item { Text("no blocked users", style = BonyType.meta.copy(color = BonyColors.TextMute), modifier = Modifier.padding(14.dp)) }
        } else {
            items(shown, key = { it }) { pk ->
                FollowRow(
                    entry = ListEntry(pk),
                    name = profiles[pk]?.bestName,
                    picture = profiles[pk]?.picture,
                    isMuted = true,
                    onOpen = { onProfileClick(pk) },
                    onUnfollow = null,
                    onToggleMute = { viewModel.toggleMute(pk) },
                    muteLabel = "UNBLOCK",
                )
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun FollowRow(
    entry: ListEntry,
    name: String?,
    picture: String?,
    isMuted: Boolean,
    onOpen: () -> Unit,
    onUnfollow: (() -> Unit)?,
    onToggleMute: () -> Unit,
    isFollowing: Boolean? = null,
    onFollow: (() -> Unit)? = null,
    muteLabel: String = if (isMuted) "UNBLOCK" else "MUTE",
) {
    val handle = remember(entry.pubkey) { Phrase.wordsFor(entry.pubkey, 3).joinToString("·") }
    val npubShort = remember(entry.pubkey) { Nip19.hexToNpub(entry.pubkey).take(10) + "…" + Nip19.hexToNpub(entry.pubkey).takeLast(6) }
    val title = name ?: entry.petname.takeIf { it.isNotBlank() } ?: handle
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).appBorder(1.dp, BonyColors.Rule).background(BonyColors.Surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar + name block opens profile
        Row(
            modifier = Modifier.weight(1f).clickable { onOpen() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(pubkeyHex = entry.pubkey, profile = null, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = BonyType.bodyDim.copy(color = BonyColors.Text), maxLines = 1)
                Text(if (title == handle) npubShort else "$handle · $npubShort", style = BonyType.metaDim.copy(color = BonyColors.TextMute), maxLines = 1)
                if (entry.petname.isNotBlank() && name != entry.petname) {
                    Text("“${entry.petname}”", style = BonyType.caption.copy(color = BonyColors.TextMute), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
            if (onFollow != null && isFollowing == null) {
                // In followers tab, show follow button
                Text(
                    "FOLLOW",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier.appBorder(1.dp, BonyColors.Accent).clickable { onFollow() }.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            if (onUnfollow != null) {
                Text(
                    "UNFOLLOW",
                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier.appBorder(1.dp, BonyColors.Rule).clickable { onUnfollow() }.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Text(
                muteLabel,
                style = BonyType.tag.copy(color = if (isMuted) BonyColors.Danger else BonyColors.TextMute),
                modifier = Modifier.appBorder(1.dp, if (isMuted) BonyColors.Danger else BonyColors.Rule).clickable { onToggleMute() }.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
