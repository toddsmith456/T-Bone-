package social.tbone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * A subtle, non-blocking "signing…" indicator shown whenever a sign request
 * is in flight with an external or remote signer (Amber, nsecBunker).
 *
 * Without it, tapping like/reply/etc. with an external signer instantly jumps
 * to the signer app, which reads as an unexplained flash. This overlay makes
 * the handoff deliberate: it appears the moment the request starts and stays
 * until the signed result returns.
 */
@Composable
fun SigningOverlay(viewModel: SigningOverlayViewModel = hiltViewModel()) {
    val signing by viewModel.signingInProgress.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = signing,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BonyColors.Bg.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .border(1.dp, BonyColors.RuleStrong)
                    .background(BonyColors.Surface)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                CircularProgressIndicator(
                    color = BonyColors.Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "signing…",
                    style = BonyType.body.copy(color = BonyColors.Text),
                )
            }
        }
    }
}
