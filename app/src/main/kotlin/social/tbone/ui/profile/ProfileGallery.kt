package social.tbone.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import coil.compose.AsyncImage
import coil.request.ImageRequest
import social.tbone.ui.media.LocalVideoQuality
import social.tbone.ui.media.VideoViewerDialog
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/** How many media items fit across the profile gallery. */
const val GALLERY_COLUMNS = 5

/** Decode size for gallery thumbnails — enough for a 5-wide cell, cheap to hold. */
private const val THUMBNAIL_PX = 320

/**
 * One row of the media gallery ([GALLERY_COLUMNS] items wide). Video cells use
 * an explicit play marker; tapping either type routes to its appropriate viewer.
 */
@Composable
fun ProfileGalleryRow(
    items: List<ProfileMedia>,
    baseIndex: Int,
    onMediaClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1.dp),
    ) {
        val context = LocalContext.current
        items.forEachIndexed { columnIndex, item ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .padding(1.dp)
                    .background(BonyColors.SurfaceAlt)
                    .clickable { onMediaClick(baseIndex + columnIndex) },
                contentAlignment = Alignment.Center,
            ) {
                if (item.isVideo) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▶", style = BonyType.body.copy(color = BonyColors.Accent))
                        Text("VIDEO", style = BonyType.meta.copy(color = BonyColors.TextMute))
                    }
                } else {
                    AsyncImage(
                        // Bounded decode + crossfade: decoding full-size photos for a
                        // 5-wide cell is what made flinging stutter.
                        model = remember(item.url) {
                            ImageRequest.Builder(context)
                                .data(item.url)
                                .size(THUMBNAIL_PX)
                                .crossfade(true)
                                .build()
                        },
                        contentDescription = "profile image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // Keep the last row's cells the same size as the full rows above.
        repeat(GALLERY_COLUMNS - items.size) {
            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
        }
    }
}

/** Swipeable full-screen viewer over gallery images; videos open in the app player. */
@Composable
fun ProfileGalleryViewer(
    items: List<ProfileMedia>,
    startIndex: Int,
    onClose: () -> Unit,
    onOpenNote: ((String) -> Unit)?,
) {
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size },
    )
    var playingVideoUrl by remember { mutableStateOf<String?>(null) }

    // Dismiss the gallery on system back, but let the regular video dialog
    // consume back while it is open.
    BackHandler(enabled = playingVideoUrl == null, onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 44.dp)
                        .clickable { playingVideoUrl = item.url },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▶", style = BonyType.title.copy(color = BonyColors.Accent))
                        Text(
                            "play video",
                            style = BonyType.body.copy(color = Color.White),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            } else {
                AsyncImage(
                    model = item.url,
                    contentDescription = "profile image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 44.dp),
                )
            }
        }

        Text(
            text = "${pagerState.currentPage + 1} / ${items.size}",
            style = BonyType.meta.copy(color = Color.White),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )

        val current = items.getOrNull(pagerState.currentPage)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current != null && onOpenNote != null) {
                Text(
                    text = "open note →",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier.clickable {
                        onOpenNote(current.noteId)
                        onClose()
                    },
                )
            }
            Text(
                text = "close ✕",
                style = BonyType.tag.copy(color = Color.White),
                modifier = Modifier
                    .padding(start = 18.dp)
                    .clickable { onClose() },
            )
        }
    }

    playingVideoUrl?.let { url ->
        VideoViewerDialog(
            url = url,
            quality = LocalVideoQuality.current,
            onClose = { playingVideoUrl = null },
        )
    }
}
