package social.tbone.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import social.tbone.ui.components.DotAvatar
import social.tbone.ui.components.SectionHeader
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.JetBrainsMono

@Composable
fun VerifyIdentityScreen(
    onBack: () -> Unit,
    viewModel: VerifyIdentityViewModel = hiltViewModel(),
) {
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showQr by remember { mutableStateOf(false) }

    val phrase = viewModel.phrase6
    val nameForDisplay = phrase.take(3).joinToString("·")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Top bar ───────────────────────────────────────────────────────
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
                    text = "verify.identity",
                    style = BonyType.body.copy(color = BonyColors.Text),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

            Column(
                modifier = Modifier.padding(horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))

                // Hero avatar + name
                DotAvatar(
                    pubkeyHex = viewModel.pubkey,
                    displayName = nameForDisplay,
                    size = 72.dp,
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = nameForDisplay,
                    style = BonyType.title.copy(color = BonyColors.Text),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(20.dp))

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

                Spacer(Modifier.height(12.dp))

                // Caption
                Text(
                    text = "─── 6-WORD FINGERPRINT · 66 BITS ───",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(12.dp))

                // 6-word fingerprint box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BonyColors.Accent)
                        .background(BonyColors.AccentBg)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        // Row 1: words 0-2
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            phrase.take(3).forEachIndexed { i, word ->
                                if (i > 0) {
                                    Text(
                                        text = " · ",
                                        style = BonyType.body.copy(
                                            color = BonyColors.RuleStrong,
                                            fontSize = 15.sp,
                                        ),
                                    )
                                }
                                Text(
                                    text = word,
                                    fontFamily = JetBrainsMono,
                                    fontSize = 15.sp,
                                    color = BonyColors.Accent,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        // Row 2: words 3-5
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            phrase.drop(3).forEachIndexed { i, word ->
                                if (i > 0) {
                                    Text(
                                        text = " · ",
                                        style = BonyType.body.copy(
                                            color = BonyColors.RuleStrong,
                                            fontSize = 15.sp,
                                        ),
                                    )
                                }
                                Text(
                                    text = word,
                                    fontFamily = JetBrainsMono,
                                    fontSize = 15.sp,
                                    color = BonyColors.Accent,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "ask ${phrase.take(3).joinToString("·")} over a side channel (call, signal,\nin person) what their fingerprint is. if all six match — you have the right key.",
                    style = BonyType.bodyDim.copy(color = BonyColors.TextDim),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(24.dp))
            }

            // ── Advanced section ──────────────────────────────────────────────
            SectionHeader("ADVANCED")

            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("full npub", style = BonyType.caption.copy(color = BonyColors.TextMute))
                Spacer(Modifier.height(6.dp))

                // Npub code block
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BonyColors.Rule)
                            .background(BonyColors.SurfaceAlt)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = viewModel.npub,
                            style = BonyType.metaDim.copy(color = BonyColors.TextDim),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BonyButton(
                        label = "COPY NPUB",
                        primary = false,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(viewModel.npub))
                            scope.launch { snackbarHostState.showSnackbar("npub copied") }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    BonyButton(
                        label = if (showQr) "HIDE QR" else "SHOW QR",
                        primary = false,
                        onClick = { showQr = !showQr },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (showQr) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BonyColors.Rule)
                            .background(BonyColors.SurfaceAlt)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        NpubQrCode(
                            content = viewModel.npub,
                            modifier = Modifier.size(220.dp),
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun NpubQrCode(content: String, modifier: Modifier = Modifier) {
    val bitMatrix = remember(content) {
        runCatching { QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512) }.getOrNull()
    } ?: return

    val fg = BonyColors.Text
    val bg = BonyColors.SurfaceAlt

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val cellW = size.width / bitMatrix.width
        val cellH = size.height / bitMatrix.height
        drawRect(color = bg)
        for (x in 0 until bitMatrix.width) {
            for (y in 0 until bitMatrix.height) {
                if (bitMatrix[x, y]) {
                    drawRect(
                        color = fg,
                        topLeft = Offset(x * cellW, y * cellH),
                        size = Size(cellW, cellH),
                    )
                }
            }
        }
    }
}
