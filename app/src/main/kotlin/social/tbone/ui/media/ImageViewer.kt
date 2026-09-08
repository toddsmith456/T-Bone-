package social.tbone.ui.media

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import social.tbone.di.ImageClientProvider
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Full-screen in-app image viewer.
 *
 * Opens from a note's inline image (or placeholder) so viewing never leaves
 * the app or opens a browser. Supports:
 *  - pinch to zoom (unbounded up to a generous 100× ceiling), drag to pan
 *  - double-tap to reset the zoom
 *  - a close button, and a download button that saves the original file to
 *    the device's Pictures/T-bone folder and confirms with a small popup.
 *
 * The image is fetched through the same Tor-aware client as the feed, and the
 * download reuses that client so both paths respect the user's proxy setting.
 */
@Composable
fun ImageViewer(
    url: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var confirmText by remember { mutableStateOf<String?>(null) }

    // Container size in pixels — used to keep panning bounded while zoomed.
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // Auto-dismiss the small confirmation popup after a moment.
    LaunchedEffect(confirmText) {
        if (confirmText != null) {
            kotlinx.coroutines.delay(1_800)
            confirmText = null
        }
    }

    val saveToDevice: (String) -> Unit = { imageUrl ->
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(imageUrl).build()
                    val bytes = ImageClientProvider.client.newCall(request)
                        .execute().use { it.body?.bytes() } ?: return@runCatching false
                    writeToGallery(context, bytes, imageUrl)
                }.getOrDefault(false)
            }
            confirmText = if (saved) "saved to pictures" else "download failed"
        }
    }

    // Android 12L and below (API 26-28) need this to write to shared storage.
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) saveToDevice(url) else confirmText = "storage permission needed"
    }

    val download: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDevice(url)
        } else if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            saveToDevice(url)
        } else {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it },
    ) {
        val maxW = containerSize.width.toFloat().coerceAtLeast(1f)
        val maxH = containerSize.height.toFloat().coerceAtLeast(1f)

        AsyncImage(
            model = ImageRequest.Builder(context).data(url).build(),
            contentDescription = "image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    })
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 100f)
                        // Keep the panned image from flying off screen entirely.
                        val limitX = (scale - 1f) * maxW / 2f + 64f
                        val limitY = (scale - 1f) * maxH / 2f + 64f
                        offset = Offset(
                            x = (offset.x + pan.x).coerceIn(-limitX, limitX),
                            y = (offset.y + pan.y).coerceIn(-limitY, limitY),
                        )
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )

        // Top control bar — close and download, styled like the rest of Bony.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            ViewerButton(label = "✕", contentDescription = "Close") { onClose() }
            Spacer(Modifier.width(10.dp))
            ViewerButton(label = "↓", contentDescription = "Download") { download() }
        }

        // Small confirmation popup (auto-dismisses).
        confirmText?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .border(1.dp, BonyColors.AccentDim)
                    .background(BonyColors.Surface)
                    .clickable { confirmText = null }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = text,
                    style = BonyType.body.copy(color = BonyColors.Text),
                )
            }
        }
    }
}

@Composable
private fun ViewerButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .border(1.dp, BonyColors.RuleStrong)
            .background(BonyColors.Surface.copy(alpha = 0.9f))
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = BonyType.body.copy(color = BonyColors.Text),
        )
    }
}

/**
 * Writes the raw bytes into the shared MediaStore gallery under
 * Pictures/T-bone. Returns true on success.
 */
private fun writeToGallery(context: android.content.Context, bytes: ByteArray, sourceUrl: String): Boolean {
    val resolver = context.contentResolver

    val (name, mime) = fileNameFor(sourceUrl)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/T-bone")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
    }.getOrDefault(false)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    if (!ok) resolver.delete(uri, null, null)
    return ok
}

/** Generates a unique, URL-derived filename plus a MIME type. */
private fun fileNameFor(sourceUrl: String): Pair<String, String> {
    val raw = sourceUrl.substringAfterLast('/').substringBefore('?').substringBefore('#')
    val ext = raw.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }
        ?: "jpg"
    val mime = when (ext) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "jpeg" -> "image/jpeg"
        else -> "image/jpeg"
    }
    val base = raw.removeSuffix(".$ext").ifBlank { "image" }
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(40)
        .ifBlank { "image" }
    val stamp = System.currentTimeMillis()
    return "$base-$stamp.$ext" to mime
}
