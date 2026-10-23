package social.tbone.ui.feed

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The four feed-tab glyphs, drawn in T-Bone's own stroke style.
 *
 * Material's outlined icons are pre-baked fill paths, so they cannot be scaled
 * up or down without turning muddy next to the app's hairline rules. These are
 * real ImageVectors drawn with a single 1.7-unit stroke on a 24-unit grid, which
 * matches the weight of the app's 1.dp borders and stays crisp from 16 dp to
 * 24 dp. Everything is authored with one stroke colour; `Icon(tint = …)` tints
 * the whole glyph, so each tab picks up `BonyColors.Accent` when active and
 * `BonyColors.TextMute` otherwise in every theme.
 *
 * @see FeedTabIcon for the per-tab lookup.
 */
object FeedTabIcons {

    /** Stroke weight on the 24-unit grid — a hair under 1.5.dp at 20.dp. */
    private const val STROKE = 1.7f

    private fun icon(name: String, build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = build,
            )
        }.build()

    /**
     * following — the universal "add person / follow" glyph: a head, a shoulder
     * line and a plus where the second person would be. A plus reads as
     * "follow" in every social app; a tick would read as "already following".
     */
    val following: ImageVector = icon("feed_following") {
        // head
        moveTo(9.0f, 4.4f)
        curveTo(10.93f, 4.4f, 12.5f, 5.97f, 12.5f, 7.9f)
        curveTo(12.5f, 9.83f, 10.93f, 11.4f, 9.0f, 11.4f)
        curveTo(7.07f, 11.4f, 5.5f, 9.83f, 5.5f, 7.9f)
        curveTo(5.5f, 5.97f, 7.07f, 4.4f, 9.0f, 4.4f)
        close()
        // shoulders, open on the right so the plus sits in the gap
        moveTo(2.9f, 20.2f)
        verticalLineTo(19.0f)
        curveTo(2.9f, 15.9f, 5.6f, 13.6f, 9.0f, 13.6f)
        horizontalLineTo(10.2f)
        curveTo(11.4f, 13.6f, 12.5f, 13.9f, 13.4f, 14.5f)
        // plus
        moveTo(18.2f, 13.6f)
        verticalLineTo(19.4f)
        moveTo(15.3f, 16.5f)
        horizontalLineTo(21.1f)
    }

    /**
     * clock — a dial with hands at ten past ten. The counter-clockwise 22-unit
     * sweep keeps the glyph reading as a clock rather than a stopwatch.
     */
    val clock: ImageVector = icon("feed_clock") {
        moveTo(12.0f, 3.4f)
        curveTo(16.75f, 3.4f, 20.6f, 7.25f, 20.6f, 12.0f)
        curveTo(20.6f, 16.75f, 16.75f, 20.6f, 12.0f, 20.6f)
        curveTo(7.25f, 20.6f, 3.4f, 16.75f, 3.4f, 12.0f)
        curveTo(3.4f, 7.25f, 7.25f, 3.4f, 12.0f, 3.4f)
        close()
        // hands: 12 → 12 o'clock, 12 → 4 o'clock
        moveTo(12.0f, 7.1f)
        verticalLineTo(12.0f)
        lineTo(15.4f, 14.0f)
    }

    /**
     * t-bone — the app's own mark: two hearts joined by a square joint.
     *
     * This one is the only filled glyph in the set. Traced as an outline it
     * collapsed into an hourglass at 17 dp — the two heart tips meeting at a
     * waist is exactly the silhouette of an hourglass, and the joint vanished.
     * Solid, it reads as the logo at any size, which is the whole point of
     * putting it on a tab. Geometry follows `assets/icon.png`: hearts 12 wide by
     * 10.8 tall with a 2.6-wide joint between the tips.
     */
    val tbone: ImageVector = ImageVector.Builder(
        name = "feed_tbone",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // upper heart, tip down
        path(fill = SolidColor(Color.Black)) {
            moveTo(12.0f, 12.0f)
            curveTo(9.2f, 9.9f, 6.0f, 7.4f, 6.0f, 4.6f)
            curveTo(6.0f, 2.5f, 7.6f, 1.2f, 9.4f, 1.2f)
            curveTo(10.7f, 1.2f, 11.6f, 1.9f, 12.0f, 2.8f)
            curveTo(12.4f, 1.9f, 13.3f, 1.2f, 14.6f, 1.2f)
            curveTo(16.4f, 1.2f, 18.0f, 2.5f, 18.0f, 4.6f)
            curveTo(18.0f, 7.4f, 14.8f, 9.9f, 12.0f, 12.0f)
            close()
        }
        // the joint — a square, not a dot, so the mark reads as bone
        path(fill = SolidColor(Color.Black)) {
            moveTo(10.6f, 10.4f)
            horizontalLineTo(13.4f)
            verticalLineTo(13.6f)
            horizontalLineTo(10.6f)
            close()
        }
        // lower heart, tip up
        path(fill = SolidColor(Color.Black)) {
            moveTo(12.0f, 12.0f)
            curveTo(14.8f, 14.1f, 18.0f, 16.6f, 18.0f, 19.4f)
            curveTo(18.0f, 21.5f, 16.4f, 22.8f, 14.6f, 22.8f)
            curveTo(13.3f, 22.8f, 12.4f, 22.1f, 12.0f, 21.2f)
            curveTo(11.6f, 22.1f, 10.7f, 22.8f, 9.4f, 22.8f)
            curveTo(7.6f, 22.8f, 6.0f, 21.5f, 6.0f, 19.4f)
            curveTo(6.0f, 16.6f, 9.2f, 14.1f, 12.0f, 12.0f)
            close()
        }
    }.build()

    /**
     * global — a globe: outline, equator and one meridian. Two interior lines is
     * the most that survives at 17 dp without turning into a blob.
     */
    val global: ImageVector = icon("feed_global") {
        moveTo(12.0f, 3.4f)
        curveTo(16.75f, 3.4f, 20.6f, 7.25f, 20.6f, 12.0f)
        curveTo(20.6f, 16.75f, 16.75f, 20.6f, 12.0f, 20.6f)
        curveTo(7.25f, 20.6f, 3.4f, 16.75f, 3.4f, 12.0f)
        curveTo(3.4f, 7.25f, 7.25f, 3.4f, 12.0f, 3.4f)
        close()
        // meridian
        moveTo(12.0f, 3.4f)
        curveTo(9.85f, 5.55f, 8.3f, 8.65f, 8.3f, 12.0f)
        curveTo(8.3f, 15.35f, 9.85f, 18.45f, 12.0f, 20.6f)
        curveTo(14.15f, 18.45f, 15.7f, 15.35f, 15.7f, 12.0f)
        curveTo(15.7f, 8.65f, 14.15f, 5.55f, 12.0f, 3.4f)
        close()
        // equator
        moveTo(3.4f, 12.0f)
        horizontalLineTo(20.6f)
    }

    /** The glyph shown for a feed tab. */
    fun forTab(tab: FeedTab): ImageVector = when (tab) {
        FeedTab.HOME -> following
        FeedTab.CLOCK -> clock
        FeedTab.SUGGESTED -> tbone
        FeedTab.GLOBAL -> global
    }
}
