package social.tbone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.settings.AppSettings
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Blossom media upload settings:
 *  - enable/disable the default servers (nostr.download, blossom.data.haus,
 *    blossom.ditto.pub) and remove custom ones,
 *  - add your own Blossom server,
 *  - set any one server as the DEFAULT (used when none is chosen on the
 *    compose screen),
 *  - toggle compression of image/video uploads.
 * When no server is picked on the compose screen and no default is set, a
 * random server from the enabled pool is used.
 */
@Composable
fun BlossomSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val servers by viewModel.blossomServers.collectAsStateWithLifecycle()
    val defaultServer by viewModel.blossomDefaultServer.collectAsStateWithLifecycle()
    val compress by viewModel.blossomCompress.collectAsStateWithLifecycle()
    var customInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding(),
    ) {
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
            Text("blossom uploads", style = BonyType.body.copy(color = BonyColors.Text))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = "media (images & video) is uploaded to these Blossom servers. Pick one on the compose screen, or leave it random.",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            )
            Spacer(Modifier.height(12.dp))

            servers.sorted().forEach { server ->
                val isDefault = server == defaultServer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BonyColors.Rule)
                        .background(BonyColors.Surface)
                        .padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server,
                            style = BonyType.meta.copy(color = BonyColors.Text),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isDefault) "★ default" else "set default",
                            style = BonyType.tag.copy(
                                color = if (isDefault) BonyColors.Accent else BonyColors.TextMute,
                            ),
                            modifier = Modifier
                                .border(1.dp, if (isDefault) BonyColors.AccentDim else BonyColors.Rule)
                                .clickable { viewModel.setBlossomDefaultServer(if (isDefault) null else server) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (server in AppSettings.BLOSSOM_DEFAULTS) "default server" else "custom server",
                            style = BonyType.caption.copy(color = BonyColors.TextMute),
                            modifier = Modifier.weight(1f),
                        )
                        if (server !in AppSettings.BLOSSOM_DEFAULTS) {
                            Text(
                                text = "remove",
                                style = BonyType.tag.copy(color = BonyColors.Danger),
                                modifier = Modifier
                                    .border(1.dp, BonyColors.Danger)
                                    .clickable { viewModel.removeBlossomServer(server) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        } else {
                            Text(
                                text = if (server in servers) "enabled" else "disabled",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(4.dp))

            // ── Add custom server ─────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    placeholder = { Text("https://your-blossom.server", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                    singleLine = true,
                    textStyle = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "ADD",
                    style = BonyType.tag.copy(
                        color = if (customInput.isNotBlank()) BonyColors.Accent else BonyColors.TextMute,
                    ),
                    modifier = Modifier
                        .border(1.dp, if (customInput.isNotBlank()) BonyColors.AccentDim else BonyColors.Rule)
                        .clickable(enabled = customInput.isNotBlank()) {
                            viewModel.addBlossomServer(customInput)
                            customInput = ""
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Compression toggle ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("compress uploads", style = BonyType.meta.copy(color = BonyColors.Text))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "images are re-encoded (EXIF/metadata removed) and videos are transcoded smaller",
                        style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp, 22.dp)
                        .background(if (compress) BonyColors.AccentBg else BonyColors.Bg)
                        .border(1.dp, if (compress) BonyColors.Accent else BonyColors.RuleStrong)
                        .clickable { viewModel.setBlossomCompress(!compress) }
                        .padding(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(if (compress) Alignment.CenterEnd else Alignment.CenterStart)
                            .background(if (compress) BonyColors.Accent else BonyColors.TextMute),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "when no server is chosen on the compose screen and no default is set, a random enabled server is picked for each upload.",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )
        }
    }
}
