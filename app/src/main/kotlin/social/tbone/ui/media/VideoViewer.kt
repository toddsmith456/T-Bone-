package social.tbone.ui.media

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import social.tbone.di.ImageClientProvider
import social.tbone.settings.VideoQuality
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * The in-app video player. It deliberately mirrors ImageViewer: a dark,
 * edge-to-edge themed surface, a close control, and a download control. The
 * player is released with the dialog and repeat mode is always OFF.
 */
@Composable
fun VideoViewer(
    url: String,
    quality: VideoQuality,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var confirmText by remember { mutableStateOf<String?>(null) }
    val player = rememberVideoPlayer(url, quality, playWhenReady = true)

    LaunchedEffect(confirmText) {
        if (confirmText != null) {
            kotlinx.coroutines.delay(1_800)
            confirmText = null
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val saveToDevice: () -> Unit = {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(url).build()
                    val bytes = ImageClientProvider.client.newCall(request)
                        .execute().use { it.body?.bytes() } ?: return@runCatching false
                    writeVideoToGallery(context, bytes, url)
                }.getOrDefault(false)
            }
            confirmText = if (saved) "saved to videos" else "download failed"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) saveToDevice() else confirmText = "storage permission needed"
    }
    val download = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            saveToDevice()
        } else {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 3_000
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            VideoViewerButton("✕", "Close", onClose)
            Spacer(Modifier.width(10.dp))
            VideoViewerButton("↓", "Download", download)
        }

        confirmText?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .border(1.dp, BonyColors.AccentDim)
                    .background(BonyColors.Surface)
                    .clickable { confirmText = null }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text, style = BonyType.body.copy(color = BonyColors.Text))
            }
        }
    }
}

/** Opens the same full-screen player as a composable dialog. */
@Composable
fun VideoViewerDialog(
    url: String,
    quality: VideoQuality,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        VideoViewer(url = url, quality = quality, onClose = onClose)
    }
}

/**
 * Build a player through the Tor-aware OkHttp client. For Low quality, Media3
 * is asked to choose a <=640x360 track when the source offers variants; a
 * single-track MP4 correctly falls back to that source because it has no lower
 * representation to choose.
 */
@Composable
fun rememberVideoPlayer(
    url: String,
    quality: VideoQuality,
    playWhenReady: Boolean,
): ExoPlayer {
    val context = LocalContext.current
    val player = remember(url, quality) {
        val dataSourceFactory = OkHttpDataSource.Factory(ImageClientProvider.client)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { created ->
                created.repeatMode = Player.REPEAT_MODE_OFF
                if (quality == VideoQuality.LOW) {
                    created.trackSelectionParameters = created.trackSelectionParameters
                        .buildUpon()
                        .setMaxVideoSize(640, 360)
                        .build()
                }
                created.setMediaItem(MediaItem.fromUri(url))
                created.prepare()
            }
    }

    LaunchedEffect(player, playWhenReady) {
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = playWhenReady
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    return player
}

@Composable
private fun VideoViewerButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, BonyColors.RuleStrong)
            .background(BonyColors.Surface.copy(alpha = 0.9f))
            .semantics { contentDescription = description }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = BonyType.body.copy(color = BonyColors.Text))
    }
}

private fun writeVideoToGallery(
    context: android.content.Context,
    bytes: ByteArray,
    sourceUrl: String,
): Boolean {
    val resolver = context.contentResolver
    val (name, mime) = videoFileNameFor(sourceUrl)
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/T-bone")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
    }.getOrDefault(false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, ready, null, null)
    }
    if (!ok) resolver.delete(uri, null, null)
    return ok
}

private fun videoFileNameFor(sourceUrl: String): Pair<String, String> {
    val raw = sourceUrl.substringAfterLast('/').substringBefore('?').substringBefore('#')
    val extension = raw.substringAfterLast('.', "").lowercase()
        .takeIf { it.length in 2..5 } ?: "mp4"
    val mime = when (extension) {
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "m4v" -> "video/x-m4v"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }
    val base = raw.removeSuffix(".$extension")
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(40)
        .ifBlank { "video" }
    return "$base-${System.currentTimeMillis()}.$extension" to mime
}
