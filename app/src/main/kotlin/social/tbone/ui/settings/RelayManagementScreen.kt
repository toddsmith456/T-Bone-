package social.tbone.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import social.tbone.settings.AppSettings
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun RelayManagementScreen(
    onBack: () -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
    globalViewModel: GlobalRelayViewModel = hiltViewModel(),
) {
    val relayStatuses by viewModel.relayStatuses.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Global relays
    val globalRelays by globalViewModel.globalRelays.collectAsStateWithLifecycle()
    val globalActive by globalViewModel.globalActiveRelays.collectAsStateWithLifecycle()
    val globalStatuses by globalViewModel.relayStatuses.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showAddGlobalDialog by remember { mutableStateOf(false) }
    var showGlobalPopup by remember { mutableStateOf(false) }

    // Snackbar messages from ViewModel
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbar.showSnackbar(msg)
        }
    }

    // Document pickers for import/export
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importFrom(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relay management") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        enabled = !syncState.syncing && !ui.busy,
                        onClick = viewModel::syncPreferredRelays,
                    ) {
                        Text(if (syncState.syncing) "Syncing…" else "Sync")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add relay")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Sync status ───────────────────────────────────────────────
            item(key = "relay_sync_status") {
                syncState.message?.let { message ->
                    Text(
                        text = message,
                        color = if (syncState.isError) Color(0xFFB3261E)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (ui.busy) {
                    Text(
                        text = ui.busyLabel,
                        color = BonyColors.Warn,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            // ── Import / Export ───────────────────────────────────────────
            item(key = "relay_import_export") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .appBorder(1.dp, BonyColors.Rule)
                        .background(BonyColors.Surface)
                        .padding(12.dp),
                ) {
                    Text("Import / Export", style = MaterialTheme.typography.titleSmall.copy(color = BonyColors.Text))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Single JSON file exactly like relays store (kind 10002 with r tags). Other apps can read it.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { exportLauncher.launch(viewModel.exportFileName()) },
                            enabled = !ui.busy,
                            modifier = Modifier.weight(1f).border(1.dp, BonyColors.Rule, RoundedCornerShape(4.dp)),
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export")
                        }
                        TextButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            enabled = !ui.busy,
                            modifier = Modifier.weight(1f).border(1.dp, BonyColors.Rule, RoundedCornerShape(4.dp)),
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import")
                        }
                    }
                }
            }

            // ── Personal relays ───────────────────────────────────────────
            item(key = "personal_header") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Your relays (${relayStatuses.size})", style = MaterialTheme.typography.titleMedium.copy(color = BonyColors.Text))
                }
            }
            if (relayStatuses.isEmpty()) {
                item(key = "personal_empty") {
                    Text(
                        "No relays yet. Add one or Sync from your published list.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            items(relayStatuses.entries.toList().sortedBy { it.key }, key = { it.key }) { (url, status) ->
                ListItem(
                    headlineContent = { Text(url, style = MaterialTheme.typography.bodyMedium) },
                    leadingContent = { RelayStatusDot(status) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeRelay(url) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove relay")
                        }
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("relay", url))
                            Toast.makeText(context, "copied $url", Toast.LENGTH_SHORT).show()
                        },
                    ),
                )
            }

            // ── Global relays compact section ─────────────────────────────
            item(key = "global_header") {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                Spacer(Modifier.height(8.dp))
            }
            item(key = "global_section") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BonyColors.Surface)
                        .appBorder(1.dp, BonyColors.Rule)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Global relays", style = MaterialTheme.typography.titleSmall.copy(color = BonyColors.Text))
                            Text(
                                "${globalRelays.size} total · ${globalActive.size} active for Global feed",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                            Text(
                                "Separate list only for Global. Tap to quickly view 1–2 relays.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // Compact chips showing active relays
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        globalRelays.sorted().forEach { url ->
                            val isActive = url in globalActive
                            val status = globalStatuses[url] ?: RelayStatus.DISCONNECTED
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isActive) BonyColors.SurfaceAlt else Color(0xFFF5F5F5))
                                    .border(1.dp, if (isActive) BonyColors.AccentDim else BonyColors.Rule, RoundedCornerShape(6.dp))
                                    .clickable { globalViewModel.toggleActive(url) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RelayStatusDot(status, modifier = Modifier.size(8.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    url.removePrefix("wss://").removePrefix("ws://"),
                                    style = MaterialTheme.typography.bodySmall.copy(color = if (isActive) BonyColors.Text else BonyColors.TextMute),
                                    modifier = Modifier.weight(1f),
                                )
                                // Solo button — view only this relay
                                Text(
                                    "SOLO",
                                    style = BonyType.tag.copy(color = if (globalActive.size == 1 && isActive) BonyColors.Accent else BonyColors.TextMute),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .border(1.dp, BonyColors.Rule, RoundedCornerShape(4.dp))
                                        .clickable { globalViewModel.solo(url) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                // Toggle dot
                                Box(
                                    modifier = Modifier.size(18.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isActive) BonyColors.Accent else BonyColors.Rule)
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isActive) Text("✓", style = MaterialTheme.typography.labelSmall.copy(color = Color.White))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { showGlobalPopup = true },
                            modifier = Modifier.border(1.dp, BonyColors.AccentDim, RoundedCornerShape(6.dp)),
                        ) { Text("Manage Global") }
                        TextButton(
                            onClick = { globalViewModel.enableAll() },
                        ) { Text("Enable all") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Tip: Tap a relay to toggle it. SOLO instantly views only that relay. Use Manage for add/remove.",
                            style = MaterialTheme.typography.bodySmall.copy(color = BonyColors.TextMute),
                        )
                    }
                }
            }

            item(key = "global_spacer") {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Long-press any relay to copy. Personal relays are for Home/Follows; Global relays are for Global tab only.",
                    style = MaterialTheme.typography.bodySmall.copy(color = BonyColors.TextMute),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showAddDialog) {
        AddRelayDialog(
            onDismiss = { showAddDialog = false },
            onAdd = viewModel::addRelay,
        )
    }

    if (showAddGlobalDialog) {
        AddRelayDialog(
            onDismiss = { showAddGlobalDialog = false },
            onAdd = globalViewModel::addRelay,
        )
    }

    if (showGlobalPopup) {
        GlobalRelayPopup(
            globalRelays = globalRelays,
            globalActive = globalActive,
            statuses = globalStatuses,
            onDismiss = { showGlobalPopup = false },
            onAdd = { showAddGlobalDialog = true },
            onRemove = globalViewModel::removeRelay,
            onToggle = globalViewModel::toggleActive,
            onSolo = globalViewModel::solo,
            onEnableAll = globalViewModel::enableAll,
            onReset = globalViewModel::reset,
        )
    }

    // Import dialog
    ui.importDialog?.let { dialog ->
        when (dialog) {
            is RelayImportDialog.ImportChoice -> {
                AlertDialog(
                    onDismissRequest = viewModel::dismissImportDialog,
                    title = { Text("Import ${dialog.parsed.relays.size} relays?") },
                    text = {
                        Column {
                            Text("File has ${dialog.parsed.relays.size} relay${if (dialog.parsed.relays.size == 1) "" else "s"}${if (dialog.parsed.skipped > 0) " (${dialog.parsed.skipped} skipped)" else ""}.")
                            dialog.parsed.ownerPubkey?.let { owner ->
                                Spacer(Modifier.height(6.dp))
                                Text("From: ${owner.take(12)}…", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("MERGE adds new relays to your current list (${dialog.currentCount}).")
                            Text("REPLACE swaps your current list for the file — current list will be lost.")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.applyImport(replace = false) }) { Text("MERGE") }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.applyImport(replace = true) }) {
                                Text("REPLACE", color = Color(0xFFB3261E))
                            }
                            TextButton(onClick = viewModel::dismissImportDialog) { Text("Cancel") }
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun RelayStatusDot(status: RelayStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        RelayStatus.CONNECTED    -> Color(0xFF4CAF50)
        RelayStatus.CONNECTING   -> Color(0xFFFFC107)
        RelayStatus.DISCONNECTED -> Color(0xFFF44336)
    }
    Surface(
        modifier = modifier.size(10.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = color,
    ) {}
}

@Composable
private fun AddRelayDialog(onDismiss: () -> Unit, onAdd: (String) -> Boolean) {
    var text by remember { mutableStateOf("wss://") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add relay") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; errorText = null },
                    label = { Text("Relay URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        text = "ws://localhost:4869"
                        errorText = null
                    },
                ) {
                    Text("Use local Citrine relay")
                }
                Text(
                    text = "Citrine on this device: ws://localhost:4869. Loopback relay traffic connects directly; other relays still follow your Tor setting.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                errorText?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFFB3261E),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = RelayPool.normalize(text)
                    if (!RelayPool.isValidRelayUrl(normalized)) {
                        errorText = "Enter a valid ws:// or wss:// relay address."
                    } else if (onAdd(normalized)) {
                        onDismiss()
                    } else {
                        errorText = "Couldn't add this relay address."
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobalRelayPopup(
    globalRelays: Set<String>,
    globalActive: Set<String>,
    statuses: Map<String, RelayStatus>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSolo: (String) -> Unit,
    onEnableAll: () -> Unit,
    onReset: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, BonyColors.Rule, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Global relays", style = MaterialTheme.typography.titleMedium.copy(color = BonyColors.Text), modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
            item(key = "description") {
                Text("Tap a relay to toggle it, or use SOLO to view only one.", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
            }
            item(key = "actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEnableAll) { Text("Enable all") }
                    TextButton(onClick = onReset) { Text("Reset") }
                    TextButton(onClick = onAdd) { Text("+ Add") }
                }
            }
            items(globalRelays.sorted(), key = { "relay:$it" }) { url ->
                val isActive = url in globalActive
                val status = statuses[url] ?: RelayStatus.DISCONNECTED
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (isActive) BonyColors.SurfaceAlt else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .border(1.dp, if (isActive) BonyColors.AccentDim else BonyColors.Rule, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onToggle(url) }) {
                        RelayStatusDot(status)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(url, style = MaterialTheme.typography.bodySmall.copy(color = if (isActive) BonyColors.Text else BonyColors.TextMute))
                            Text(if (isActive) "active · tap to disable" else "inactive · tap to enable", style = MaterialTheme.typography.labelSmall.copy(color = BonyColors.TextMute))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onSolo(url) }) { Text("SOLO") }
                        TextButton(onClick = { onRemove(url) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}
