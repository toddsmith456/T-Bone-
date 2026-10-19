package social.tbone.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RelayManagementScreen(
    onBack: () -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    val relayStatuses by viewModel.relayStatuses.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
                        enabled = !syncState.syncing,
                        onClick = viewModel::syncPreferredRelays,
                    ) {
                        Text(if (syncState.syncing) "Syncing…" else "Sync list")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add relay")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item(key = "relay_sync_status") {
                syncState.message?.let { message ->
                    Text(
                        text = message,
                        color = if (syncState.isError) androidx.compose.ui.graphics.Color(0xFFB3261E)
                        else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(relayStatuses.entries.toList(), key = { it.key }) { (url, status) ->
                ListItem(
                    headlineContent = { Text(url) },
                    leadingContent = { RelayStatusDot(status) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeRelay(url) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove relay")
                        }
                    },
                    // Long-press a relay address to copy it to the clipboard.
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
        }
    }

    if (showAddDialog) {
        AddRelayDialog(
            onDismiss = { showAddDialog = false },
            onAdd = viewModel::addRelay,
        )
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
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                errorText?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFFB3261E),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
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
