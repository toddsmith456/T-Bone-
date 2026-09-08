package social.tbone.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            accounts.forEach { account ->
                val isActive = account.pubkey == activeAccount?.pubkey
                val name = account.displayName
                    ?: "${account.pubkey.take(8)}…${account.pubkey.takeLast(6)}"
                ListItem(
                    headlineContent = {
                        Text(
                            text = name,
                            fontFamily = if (account.displayName == null) FontFamily.Monospace else null,
                        )
                    },
                    supportingContent = if (isActive) ({ Text("Active") }) else null,
                    trailingContent = if (accounts.size > 1) ({
                        IconButton(onClick = { viewModel.removeAccount(account.pubkey) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove account",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }) else null,
                    modifier = if (!isActive) Modifier.clickable { viewModel.switchAccount(account.pubkey) } else Modifier,
                )
                HorizontalDivider()
            }

            ListItem(
                headlineContent = { Text("Add account") },
                supportingContent = { Text("Sign in with another Nostr identity") },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onAddAccount),
            )
        }
    }
}
