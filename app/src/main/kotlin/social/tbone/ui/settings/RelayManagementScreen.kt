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
import social.tbone.nostr.relay.RelayStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RelayManagementScreen(
    onBack: () -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    val relayStatuses by viewModel.relayStatuses.collectAsStateWithLifecycle()
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
            onAdd = { url ->
                viewModel.addRelay(url)
                showAddDialog = false
            },
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
private fun AddRelayDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("wss://") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add relay") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Relay URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
