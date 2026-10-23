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

    /**
     * The tooth carries an interior T, so it needs a lighter line than the
     * single-stroke glyphs or the counter fills in at 17 dp.
     */
    private const val TOOTH_STROKE = 1.5f

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
     * t-bone — the app's mark: a tooth with a T cut into it.
     *
     * Traced from the artwork in `docs/feed-shots/tbone-mark.png` on a 24-unit
     * grid: the body is one closed stroke (its top notch descends to the bar and
     * forms the stem of the T), then the bar, then the root canal. The reference
     * hairline is ~0.97 units; this is drawn at 1.5 so it survives 17 dp without
     * filling in, which is why the interior is reduced to the T alone.
     */
    val tbone: ImageVector = ImageVector.Builder(
        name = "feed_tbone",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // tooth body
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = TOOTH_STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12.00f, 5.70f)
            curveTo(11.87f, 5.70f, 11.74f, 5.29f, 11.62f, 5.10f)
            curveTo(11.50f, 4.91f, 11.38f, 4.72f, 11.28f, 4.55f)
            curveTo(11.18f, 4.38f, 11.13f, 4.21f, 11.05f, 4.05f)
            curveTo(10.97f, 3.90f, 10.93f, 3.74f, 10.80f, 3.62f)
            curveTo(10.67f, 3.50f, 10.47f, 3.39f, 10.25f, 3.32f)
            curveTo(10.03f, 3.25f, 9.73f, 3.20f, 9.45f, 3.18f)
            curveTo(9.17f, 3.16f, 8.82f, 3.14f, 8.55f, 3.18f)
            curveTo(8.28f, 3.22f, 8.05f, 3.32f, 7.85f, 3.42f)
            curveTo(7.65f, 3.52f, 7.56f, 3.67f, 7.35f, 3.78f)
            curveTo(7.14f, 3.88f, 6.83f, 3.95f, 6.60f, 4.05f)
            curveTo(6.37f, 4.15f, 6.14f, 4.25f, 5.95f, 4.38f)
            curveTo(5.76f, 4.51f, 5.59f, 4.69f, 5.45f, 4.85f)
            curveTo(5.31f, 5.01f, 5.22f, 5.15f, 5.12f, 5.35f)
            curveTo(5.02f, 5.55f, 4.92f, 5.80f, 4.86f, 6.05f)
            curveTo(4.80f, 6.30f, 4.79f, 6.54f, 4.78f, 6.85f)
            curveTo(4.77f, 7.16f, 4.77f, 7.56f, 4.78f, 7.90f)
            curveTo(4.79f, 8.24f, 4.79f, 8.58f, 4.82f, 8.90f)
            curveTo(4.85f, 9.22f, 4.89f, 9.49f, 4.98f, 9.80f)
            curveTo(5.07f, 10.11f, 5.15f, 10.43f, 5.35f, 10.75f)
            curveTo(5.54f, 11.07f, 5.88f, 11.39f, 6.15f, 11.70f)
            curveTo(6.42f, 12.01f, 6.72f, 12.31f, 6.95f, 12.60f)
            curveTo(7.18f, 12.89f, 7.38f, 13.15f, 7.55f, 13.45f)
            curveTo(7.72f, 13.75f, 7.89f, 14.07f, 8.00f, 14.40f)
            curveTo(8.11f, 14.73f, 8.17f, 15.07f, 8.22f, 15.40f)
            curveTo(8.27f, 15.73f, 8.26f, 16.07f, 8.28f, 16.40f)
            curveTo(8.30f, 16.73f, 8.30f, 17.06f, 8.35f, 17.40f)
            curveTo(8.40f, 17.74f, 8.48f, 18.12f, 8.60f, 18.45f)
            curveTo(8.72f, 18.78f, 8.86f, 19.12f, 9.05f, 19.40f)
            curveTo(9.24f, 19.68f, 9.50f, 19.93f, 9.75f, 20.15f)
            curveTo(10.00f, 20.37f, 10.29f, 20.56f, 10.55f, 20.70f)
            curveTo(10.81f, 20.84f, 11.08f, 20.94f, 11.32f, 21.00f)
            curveTo(11.56f, 21.06f, 11.77f, 21.08f, 12.00f, 21.08f)
            curveTo(12.23f, 21.08f, 12.44f, 21.06f, 12.68f, 21.00f)
            curveTo(12.92f, 20.94f, 13.19f, 20.84f, 13.45f, 20.70f)
            curveTo(13.71f, 20.56f, 14.00f, 20.37f, 14.25f, 20.15f)
            curveTo(14.50f, 19.93f, 14.76f, 19.68f, 14.95f, 19.40f)
            curveTo(15.14f, 19.12f, 15.28f, 18.78f, 15.40f, 18.45f)
            curveTo(15.52f, 18.12f, 15.60f, 17.74f, 15.65f, 17.40f)
            curveTo(15.70f, 17.06f, 15.70f, 16.73f, 15.72f, 16.40f)
            curveTo(15.74f, 16.07f, 15.73f, 15.73f, 15.78f, 15.40f)
            curveTo(15.83f, 15.07f, 15.89f, 14.73f, 16.00f, 14.40f)
            curveTo(16.11f, 14.07f, 16.27f, 13.75f, 16.45f, 13.45f)
            curveTo(16.62f, 13.15f, 16.82f, 12.89f, 17.05f, 12.60f)
            curveTo(17.28f, 12.31f, 17.58f, 12.01f, 17.85f, 11.70f)
            curveTo(18.12f, 11.39f, 18.45f, 11.07f, 18.65f, 10.75f)
            curveTo(18.84f, 10.43f, 18.93f, 10.11f, 19.02f, 9.80f)
            curveTo(19.11f, 9.49f, 19.15f, 9.22f, 19.18f, 8.90f)
            curveTo(19.21f, 8.58f, 19.21f, 8.24f, 19.22f, 7.90f)
            curveTo(19.23f, 7.56f, 19.23f, 7.16f, 19.22f, 6.85f)
            curveTo(19.21f, 6.54f, 19.20f, 6.30f, 19.14f, 6.05f)
            curveTo(19.08f, 5.80f, 18.98f, 5.55f, 18.88f, 5.35f)
            curveTo(18.78f, 5.15f, 18.69f, 5.01f, 18.55f, 4.85f)
            curveTo(18.41f, 4.69f, 18.24f, 4.51f, 18.05f, 4.38f)
            curveTo(17.86f, 4.25f, 17.63f, 4.15f, 17.40f, 4.05f)
            curveTo(17.17f, 3.95f, 16.86f, 3.88f, 16.65f, 3.78f)
            curveTo(16.44f, 3.67f, 16.35f, 3.52f, 16.15f, 3.42f)
            curveTo(15.95f, 3.32f, 15.72f, 3.22f, 15.45f, 3.18f)
            curveTo(15.18f, 3.14f, 14.83f, 3.16f, 14.55f, 3.18f)
            curveTo(14.27f, 3.20f, 13.97f, 3.25f, 13.75f, 3.32f)
            curveTo(13.53f, 3.39f, 13.33f, 3.50f, 13.20f, 3.62f)
            curveTo(13.07f, 3.74f, 13.03f, 3.90f, 12.95f, 4.05f)
            curveTo(12.87f, 4.21f, 12.82f, 4.38f, 12.72f, 4.55f)
            curveTo(12.62f, 4.72f, 12.50f, 4.91f, 12.38f, 5.10f)
            close()
        }
        // the T bar
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = TOOTH_STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(7.18f, 5.22f)
            curveTo(7.18f, 5.02f, 7.34f, 4.95f, 7.55f, 4.95f)
            horizontalLineTo(16.45f)
            curveTo(16.66f, 4.95f, 16.82f, 5.02f, 16.82f, 5.22f)
        }
        // root canal, hanging from the bar
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = TOOTH_STROKE,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(12.00f, 5.02f)
            curveTo(12.01f, 8.00f, 12.02f, 11.20f, 11.92f, 13.35f)
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
