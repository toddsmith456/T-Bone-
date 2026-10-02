package social.tbone.ui.settings

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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import social.tbone.lists.ListEntry
import social.tbone.lists.ListType
import social.tbone.nostr.Nip19
import social.tbone.nostr.identity.Phrase
import social.tbone.ui.components.SectionHeader
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Offline (fully local) follow list / block list.
 *
 * One screen, two lists — the nav argument `type` selects [ListType.FOLLOWS] or
 * [ListType.MUTES]. Both lists share exactly the same rules and UI.
 */
@Composable
fun OfflineListScreen(
    onBack: () -> Unit,
    viewModel: OfflineListViewModel = hiltViewModel(),
) {
    val type = viewModel.type
    val account by viewModel.account.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            if (msg.undo != null) {
                val r = snackbar.showSnackbar(msg.text, actionLabel = "UNDO", duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) viewModel.undoRemove(msg.undo)
            } else {
                snackbar.showSnackbar(msg.text, duration = SnackbarDuration.Long)
            }
        }
    }

    // Storage Access Framework: no storage permission needed for export/import.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importFrom(uri) }

    var addInput by rememberSaveable { mutableStateOf("") }
    // The browse list is collapsed by default.
    var browseOpen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }

    val enabled = state?.enabled == true
    val entries = state?.entries.orEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "←",
                    style = BonyType.body.copy(color = BonyColors.TextMute),
                    modifier = Modifier.clickable { onBack() },
                )
                Spacer(Modifier.width(8.dp))
                Text(type.title, style = BonyType.body.copy(color = BonyColors.Text))
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

            if (account == null || state == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("no active account", style = BonyType.meta.copy(color = BonyColors.TextMute))
                }
                return@Column
            }

            val acct = account!!
            val st = state!!
            val accountPhrase = remember(acct.pubkey) { Phrase.wordsFor(acct.pubkey, 3).joinToString("·") }

            // Filtered rows (only computed when the browse list is open).
            val shown by remember(entries, filter, profiles, browseOpen) {
                derivedStateOf {
                    if (!browseOpen) emptyList()
                    else {
                        val q = filter.trim().lowercase()
                        if (q.isEmpty()) entries
                        else entries.filter { e ->
                            val name = profiles[e.pubkey]?.bestName?.lowercase().orEmpty()
                            name.contains(q) || e.petname.lowercase().contains(q) ||
                                e.pubkey.startsWith(q) || Nip19.hexToNpub(e.pubkey).startsWith(q)
                        }
                    }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {

                // ── Switch ────────────────────────────────────────────────────
                item {
                    ListSection(title = "mode") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "use offline ${type.noun} list",
                                    style = BonyType.body.copy(color = BonyColors.Text),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (enabled) "on · fully local, relays are not used"
                                    else "off · using the list stored on your relays",
                                    style = BonyType.meta.copy(color = if (enabled) BonyColors.Accent else BonyColors.TextMute),
                                )
                            }
                            if (ui.busy && !st.initialSyncDone) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = BonyColors.Accent,
                                    strokeWidth = 1.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            BonyToggle(
                                checked = enabled,
                                enabled = !ui.busy,
                                onCheckedChange = { viewModel.onSwitch(it) },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when {
                                enabled && type == ListType.FOLLOWS ->
                                    "Your relay follow list is not read and never changed. FOLLOW / UNFOLLOW on profiles edit this local list only."
                                enabled ->
                                    "Your relay mute list is not read and never changed. BLOCK / UNBLOCK on profiles edit this local list only."
                                !st.initialSyncDone ->
                                    "The first time you turn this on, your ${type.plural} are copied once from your relays. After that it never syncs by itself."
                                else ->
                                    "Turn on to use your local list again. It was kept exactly as you left it."
                            },
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                        if (ui.busy) {
                            Spacer(Modifier.height(6.dp))
                            Text(ui.busyLabel, style = BonyType.meta.copy(color = BonyColors.Warn))
                        }
                    }
                }

                // ── Status + sync ─────────────────────────────────────────────
                item {
                    ListSection(title = "this account's list") {
                        Text(
                            text = "${st.size} ${type.plural} stored on this device",
                            style = BonyType.body.copy(color = BonyColors.Text),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "account: ${acct.displayName?.let { "$it · " } ?: ""}$accountPhrase",
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                        Text(
                            text = "last synced: " + if (st.lastSyncedAt > 0)
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(st.lastSyncedAt * 1000))
                            else "never",
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                        Text(
                            text = "This list belongs to this account only. To use it on another account, export it, then import it while logged into that account.",
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        BonyButton(
                            label = "⇣ SYNC ONLINE → OFFLINE",
                            primary = false,
                            enabled = enabled && !ui.busy,
                            onClick = viewModel::requestSync,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (enabled)
                                "one way only: relays → this device. Never automatic, and it never changes your relay list."
                            else "turn the offline list on to enable manual sync.",
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                    }
                }

                // ── Add by npub ───────────────────────────────────────────────
                item {
                    ListSection(title = "add by npub") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = addInput,
                                onValueChange = { addInput = it },
                                placeholder = {
                                    Text("npub1… or hex", style = BonyType.meta.copy(color = BonyColors.TextMute))
                                },
                                singleLine = true,
                                textStyle = BonyType.meta.copy(color = BonyColors.Text),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            val canAdd = addInput.isNotBlank() && !ui.busy
                            Text(
                                text = "ADD",
                                style = BonyType.tag.copy(color = if (canAdd) BonyColors.Accent else BonyColors.TextMute),
                                modifier = Modifier
                                    .border(1.dp, if (canAdd) BonyColors.AccentDim else BonyColors.Rule)
                                    .clickable(enabled = canAdd) {
                                        viewModel.addByInput(addInput) { ok -> if (ok) addInput = "" }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Adds to the local list. Remove entries from the list below (✕).",
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                    }
                }

                // ── Export / import ───────────────────────────────────────────
                item {
                    ListSection(title = "export / import") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BonyButton(
                                label = "↑ EXPORT",
                                primary = false,
                                enabled = !ui.busy,
                                onClick = { exportLauncher.launch(viewModel.exportFileName()) },
                                modifier = Modifier.weight(1f),
                            )
                            BonyButton(
                                label = "↓ IMPORT",
                                primary = false,
                                enabled = !ui.busy,
                                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "One file, standard Nostr format (a ${if (type == ListType.FOLLOWS) "NIP-02" else "NIP-51"} " +
                                "kind ${type.kind} list as JSON). Other apps can read it, and plain npub lists import too.",
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                    }
                }

                // ── Browse (collapsed by default) ─────────────────────────────
                item {
                    SectionHeader("LIST")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { browseOpen = !browseOpen }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = (if (browseOpen) "▾ " else "▸ ") + "browse ${type.plural} (${st.size})",
                            style = BonyType.body.copy(color = BonyColors.TextDim),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (browseOpen) "hide" else "show",
                            style = BonyType.meta.copy(color = BonyColors.TextMute),
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                }

                if (browseOpen) {
                    item {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            placeholder = { Text("filter by name / npub", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                            singleLine = true,
                            textStyle = BonyType.meta.copy(color = BonyColors.Text),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                text = if (entries.isEmpty()) "the list is empty" else "no matches",
                                style = BonyType.meta.copy(color = BonyColors.TextMute),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(shown, key = { it.pubkey }) { entry ->
                        CompactRow(
                            entry = entry,
                            name = profiles[entry.pubkey]?.bestName,
                            onRemove = { viewModel.remove(entry.pubkey) },
                        )
                    }
                }

                item { Spacer(Modifier.height(48.dp)) }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    when (val d = ui.dialog) {
        null -> Unit

        is OfflineListDialog.InitialSyncFailed -> ListDialog(
            title = "Couldn't reach your relays",
            body = "The first sync needs to read your ${type.listName} from a relay once, and no relay answered.\n\n" +
                "You can retry, or switch the offline list on right now with " +
                if (d.cachedCopy.isNotEmpty()) "the last copy this device had (${d.cachedCopy.size} ${type.plural})."
                else "an empty list you build yourself (add by npub or import a file).",
            confirmLabel = "RETRY",
            onConfirm = viewModel::retryInitialSync,
            secondLabel = if (d.cachedCopy.isNotEmpty()) "USE LAST COPY (${d.cachedCopy.size})" else "START EMPTY",
            onSecond = { viewModel.enableWithoutSync(d.cachedCopy) },
            onDismiss = viewModel::dismissDialog,
        )

        is OfflineListDialog.ConfirmSync -> ListDialog(
            title = "⚠ Replace your offline list?",
            body = "Syncing copies the list currently on your relays to this device and REPLACES your offline list " +
                "(${d.localCount} ${type.plural} now).\n\n" +
                "Anything you added or removed locally — manually, by profile buttons or by import — can be destroyed and cannot be undone.\n\n" +
                "Your relay list is never changed.",
            confirmLabel = "SYNC & REPLACE",
            confirmDanger = true,
            onConfirm = viewModel::confirmSync,
            secondLabel = "CANCEL",
            onSecond = viewModel::dismissDialog,
            onDismiss = viewModel::dismissDialog,
        )

        is OfflineListDialog.ImportChoice -> ListDialog(
            title = "Import ${d.parsed.entries.size} ${type.plural}?",
            body = buildString {
                append("The file has ${d.parsed.entries.size} valid ${type.plural}")
                if (d.parsed.skipped > 0) append(" (${d.parsed.skipped} unreadable items will be skipped)")
                append(".\n\n")
                if (d.fromOtherAccount) {
                    append("⚠ This file was exported from a DIFFERENT account")
                    d.parsed.ownerPubkey?.let { append(" (${Nip19.hexToNpub(it).take(14)}…)") }
                    append(". Importing it here is fine — it only becomes this account's local list.\n\n")
                }
                append("MERGE adds the new ones to your current list (${d.currentCount}).\n")
                append("REPLACE swaps your current list for the file — your current list will be lost.")
            },
            confirmLabel = "MERGE",
            onConfirm = { viewModel.applyImport(replace = false) },
            secondLabel = "REPLACE",
            secondDanger = true,
            onSecond = { viewModel.applyImport(replace = true) },
            onDismiss = viewModel::dismissDialog,
        )
    }
}

// ── Pieces ────────────────────────────────────────────────────────────────────

@Composable
private fun ListSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.Surface)
            .padding(12.dp),
    ) {
        Text(
            text = title,
            style = BonyType.caption.copy(color = BonyColors.Accent),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

/**
 * One compact row: name (if already known from the profile cache) or the 3-word
 * handle, plus a short npub. Deliberately NO avatar/picture — the list never
 * loads or caches profile pictures.
 */
@Composable
private fun CompactRow(entry: ListEntry, name: String?, onRemove: () -> Unit) {
    val handle = remember(entry.pubkey) { Phrase.wordsFor(entry.pubkey, 3).joinToString("·") }
    val npubShort = remember(entry.pubkey) {
        val n = Nip19.hexToNpub(entry.pubkey)
        n.take(10) + "…" + n.takeLast(6)
    }
    val title = name ?: entry.petname.takeIf { it.isNotBlank() } ?: handle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = BonyType.bodyDim.copy(color = BonyColors.Text), maxLines = 1)
            Text(
                text = if (title == handle) npubShort else "$handle · $npubShort",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                maxLines = 1,
            )
        }
        Text(
            text = "✕",
            style = BonyType.body.copy(color = BonyColors.TextMute),
            modifier = Modifier
                .clickable { onRemove() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun ListDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    secondLabel: String,
    onSecond: () -> Unit,
    onDismiss: () -> Unit,
    confirmDanger: Boolean = false,
    secondDanger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BonyColors.Surface,
        title = { Text(title, style = BonyType.body.copy(color = BonyColors.Text)) },
        text = { Text(body, style = BonyType.meta.copy(color = BonyColors.TextDim)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    style = BonyType.button.copy(color = if (confirmDanger) BonyColors.Danger else BonyColors.Accent),
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSecond) {
                    Text(
                        secondLabel,
                        style = BonyType.button.copy(color = if (secondDanger) BonyColors.Danger else BonyColors.TextMute),
                    )
                }
                if (secondLabel != "CANCEL") {
                    TextButton(onClick = onDismiss) {
                        Text("CANCEL", style = BonyType.button.copy(color = BonyColors.TextMute))
                    }
                }
            }
        },
    )
}
