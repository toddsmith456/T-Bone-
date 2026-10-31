package social.tbone.ui.feed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.wallet.ExternalWalletHandoff

internal enum class WalletHandoffRail { LIGHTNING, MONERO }

internal data class WalletHandoffRequest(
    val rail: WalletHandoffRail,
    val value: String,
)

@Composable
internal fun WalletHandoffDialog(
    request: WalletHandoffRequest,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val isLightning = request.rail == WalletHandoffRail.LIGHTNING
    val title = if (isLightning) "Lightning invoice" else "Monero address"
    val kindName = if (isLightning) "Lightning" else "Monero"
    val qrBitmap = remember(request.value) { makeQrBitmap(request.value) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = BonyColors.Surface,
        titleContentColor = BonyColors.Text,
        textContentColor = BonyColors.TextDim,
        title = { Text(title, style = BonyType.body.copy(color = BonyColors.Text)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "No compatible $kindName wallet app was found. Scan this QR with another wallet or copy the value below.",
                    style = BonyType.caption.copy(color = BonyColors.TextDim),
                    textAlign = TextAlign.Center,
                )
                qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "$kindName payment QR code",
                        modifier = Modifier
                            .size(200.dp)
                            .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    )
                }
                SelectionContainer {
                    Text(
                        text = request.value,
                        style = BonyType.caption.copy(color = BonyColors.Text, fontFamily = FontFamily.Monospace),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        copyToClipboard(context, request.value, title)
                        Toast.makeText(context, "$kindName value copied", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = BonyColors.Accent),
                ) { Text("copy") }
                TextButton(
                    onClick = {
                        val opened = if (isLightning) ExternalWalletHandoff.openLightningWallet(context, request.value) else ExternalWalletHandoff.openMoneroWallet(context, request.value)
                        if (opened) onDismiss()
                        else Toast.makeText(context, "No compatible wallet app is available", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = BonyColors.Accent),
                ) { Text("try wallet") }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = BonyColors.TextMute),
                ) { Text("close") }
            }
        },
    )
}

private fun copyToClipboard(context: Context, value: String, label: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun makeQrBitmap(value: String): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(
        value,
        BarcodeFormat.QR_CODE,
        512,
        512,
        mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        ),
    )
    Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
    }
}.getOrNull()
