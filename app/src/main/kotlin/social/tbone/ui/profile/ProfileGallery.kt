package social.tbone.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/** How many pictures fit across the media gallery. */
const val GALLERY_COLUMNS = 5

/** Decode size for gallery thumbnails — enough for a 5-wide cell, cheap to hold. */
private const val THUMBNAIL_PX = 320

/**
 * One row of the media gallery ([GALLERY_COLUMNS] pictures wide).
 *
 * The screen emits one row per lazy item so a profile with hundreds of images
 * only ever composes the rows on screen — that is what keeps flinging smooth.
 */
@Composable
fun ProfileGalleryRow(
    images: List<ProfileImage>,
    baseIndex: Int,
    onImageClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1.dp),
    ) {
        val context = LocalContext.current
        images.forEachIndexed { columnIndex, image ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .padding(1.dp)
                    .background(BonyColors.SurfaceAlt)
                    .clickable { onImageClick(baseIndex + columnIndex) },
            ) {
                AsyncImage(
                    // Bounded decode + crossfade: decoding full-size photos for a
                    // 5-wide cell is what made the gallery stutter.
                    model = remember(image.url) {
                        ImageRequest.Builder(context)
                            .data(image.url)
                            .size(THUMBNAIL_PX)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Keep the last row's cells the same size as the full rows above.
        repeat(GALLERY_COLUMNS - images.size) {
            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
        }
    }
}

/** Swipeable full-screen viewer over the gallery, starting at [startIndex]. */
@Composable
fun ProfileGalleryViewer(
    images: List<ProfileImage>,
    startIndex: Int,
    onClose: () -> Unit,
    onOpenNote: ((String) -> Unit)?,
) {
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
        pageCount = { images.size },
    )

    // Dismiss on system back.
    androidx.activity.compose.BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onClose() },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val image = images.getOrNull(page) ?: return@HorizontalPager
            AsyncImage(
                model = image.url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 24.dp),
            )
        }

        Text(
            text = "${pagerState.currentPage + 1} / ${images.size}",
            style = BonyType.meta.copy(color = Color.White),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
        )

        val current = images.getOrNull(pagerState.currentPage)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(18.dp),
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
}
