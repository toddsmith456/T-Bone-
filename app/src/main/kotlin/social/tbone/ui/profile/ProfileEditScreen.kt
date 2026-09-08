package social.tbone.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import social.tbone.nostr.Nip19
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Edit the active account's profile picture and banner.
 *
 * Images are automatically compressed / quality-reduced to fit the 800 KB
 * cap and every embedded EXIF/metadata field is stripped before upload.
 * The picture is stored on a Blossom server and a kind-0 event is published.
 */
@Composable
fun ProfileEditScreen(
    onBack: () -> Unit,
    viewModel: ProfileEditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pubkey by viewModel.activePubkey.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    var showNotice by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.setImage(it, banner = false) } }
    val bannerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.setImage(it, banner = true) } }

    LaunchedEffect(uiState.done) {
        if (uiState.done) showNotice = true
    }
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) showNotice = true
    }

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
            Text("profile picture & banner", style = BonyType.body.copy(color = BonyColors.Text))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            val npub = pubkey?.let { runCatching { Nip19.hexToNpub(it) }.getOrNull() }
            Text(
                text = npub?.take(24) + "…" ?: "",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            Spacer(Modifier.height(16.dp))

            // ── Banner ────────────────────────────────────────────────────────
            Text("banner", style = BonyType.caption.copy(color = BonyColors.Accent))
            Spacer(Modifier.height(8.dp))
            BannerPreview(profile?.banner, uploading = uiState.uploading)
            Spacer(Modifier.height(8.dp))
            ActionButton(
                text = if (uiState.uploading) "uploading…" else "change banner",
                enabled = !uiState.uploading,
                onClick = {
                    bannerPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )

            Spacer(Modifier.height(24.dp))

            // ── Avatar ────────────────────────────────────────────────────────
            Text("profile picture", style = BonyType.caption.copy(color = BonyColors.Accent))
            Spacer(Modifier.height(8.dp))
            AvatarPreview(profile?.picture, uploading = uiState.uploading)
            Spacer(Modifier.height(8.dp))
            ActionButton(
                text = if (uiState.uploading) "uploading…" else "change picture",
                enabled = !uiState.uploading,
                onClick = {
                    avatarPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )

            Spacer(Modifier.height(20.dp))

            if (showNotice) {
                Text(
                    text = uiState.error?.let { "failed: $it" }
                        ?: "updated — picture uploaded to blossom & your profile is published",
                    style = BonyType.meta.copy(
                        color = if (uiState.error != null) BonyColors.Danger else BonyColors.Accent,
                    ),
                )
                if (uiState.error != null) {
                    Text(
                        text = "dismiss",
                        style = BonyType.tag.copy(color = BonyColors.TextMute),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { showNotice = false; viewModel.clearError() },
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            Text(
                text = "images are re-encoded (all EXIF & metadata removed) and compressed to fit the 800 KB cap before uploading.",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            )
        }
    }
}

@Composable
private fun BannerPreview(url: String?, uploading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.SurfaceAlt),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = "banner",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = if (uploading) "uploading…" else "no banner set",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
    }
}

@Composable
private fun AvatarPreview(url: String?, uploading: Boolean) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.SurfaceAlt),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = "profile picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = if (uploading) "…" else "no picture",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
    }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = BonyType.tag.copy(
            color = if (enabled) BonyColors.Accent else BonyColors.TextMute,
        ),
        modifier = Modifier
            .border(1.dp, if (enabled) BonyColors.AccentDim else BonyColors.Rule)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
