package social.tbone.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Full-color client-side T-Bone steak vector artwork.
 *
 * Rendered 100% locally via Compose Canvas paths (zero hosted images, zero
 * bandwidth) per NIP-XX Daily Claimable Tokens (`steak`). Scales cleanly from
 * compact 16–20 dp badges to 48+ dp headers across Light, Dark, Cream, and
 * Terminal themes.
 */
@Composable
fun SteakIcon(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    dimmed: Boolean = false,
    contentDescription: String? = "Steak",
) {
    val alpha = if (dimmed) 0.42f else 1f
    val crustColor = Color(0xFF5D2218).copy(alpha = alpha)
    val fatRimColor = Color(0xFFF0DAC0).copy(alpha = alpha)
    val meatBaseColor = Color(0xFFB8392D).copy(alpha = alpha)
    val meatHighlightColor = Color(0xFFD65344).copy(alpha = alpha)
    val marblingColor = Color(0xFFF6DDD0).copy(alpha = 0.72f * alpha)
    val boneFillColor = Color(0xFFFAF3E6).copy(alpha = alpha)
    val boneStrokeColor = Color(0xFFCBB79B).copy(alpha = alpha)

    val semModifier = if (contentDescription != null) {
        modifier
            .size(size)
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Image
            }
    } else {
        modifier.size(size)
    }

    Canvas(modifier = semModifier) {
        val w = this.size.width
        val h = this.size.height
        fun x(nx: Float) = nx * w
        fun y(ny: Float) = ny * h

        // 1. Outer T-Bone cut silhouette (larger strip loin left/top, rounder tenderloin right).
        val outerPath = Path().apply {
            moveTo(x(0.14f), y(0.42f))
            cubicTo(x(0.10f), y(0.18f), x(0.32f), y(0.08f), x(0.56f), y(0.13f))
            cubicTo(x(0.76f), y(0.17f), x(0.92f), y(0.30f), x(0.91f), y(0.50f))
            cubicTo(x(0.90f), y(0.68f), x(0.76f), y(0.84f), x(0.58f), y(0.87f))
            cubicTo(x(0.45f), y(0.89f), x(0.36f), y(0.82f), x(0.28f), y(0.76f))
            cubicTo(x(0.17f), y(0.68f), x(0.16f), y(0.56f), x(0.14f), y(0.42f))
            close()
        }

        // Subtle warm crust rim + cream fat cap.
        drawPath(path = outerPath, color = fatRimColor)
        drawPath(
            path = outerPath,
            color = crustColor,
            style = Stroke(width = w * 0.065f, join = StrokeJoin.Round, cap = StrokeCap.Round),
        )

        // 2. Inner rich beef fill (inset inside fat cap).
        val meatPath = Path().apply {
            moveTo(x(0.20f), y(0.43f))
            cubicTo(x(0.17f), y(0.24f), x(0.35f), y(0.15f), x(0.55f), y(0.20f))
            cubicTo(x(0.72f), y(0.24f), x(0.85f), y(0.35f), x(0.84f), y(0.50f))
            cubicTo(x(0.83f), y(0.64f), x(0.72f), y(0.77f), x(0.57f), y(0.80f))
            cubicTo(x(0.46f), y(0.82f), x(0.39f), y(0.76f), x(0.32f), y(0.71f))
            cubicTo(x(0.23f), y(0.64f), x(0.22f), y(0.54f), x(0.20f), y(0.43f))
            close()
        }
        drawPath(path = meatPath, color = meatBaseColor)

        // 3. Juicy seared highlight lobes (strip side + tenderloin side).
        val stripHighlight = Path().apply {
            moveTo(x(0.25f), y(0.38f))
            cubicTo(x(0.26f), y(0.25f), x(0.42f), y(0.21f), x(0.54f), y(0.26f))
            cubicTo(x(0.58f), y(0.30f), x(0.50f), y(0.40f), x(0.37f), y(0.42f))
            cubicTo(x(0.29f), y(0.43f), x(0.25f), y(0.41f), x(0.25f), y(0.38f))
            close()
        }
        drawPath(path = stripHighlight, color = meatHighlightColor)

        val tenderloinHighlight = Path().apply {
            moveTo(x(0.58f), y(0.57f))
            cubicTo(x(0.67f), y(0.54f), x(0.78f), y(0.57f), x(0.77f), y(0.65f))
            cubicTo(x(0.75f), y(0.73f), x(0.63f), y(0.76f), x(0.57f), y(0.72f))
            cubicTo(x(0.53f), y(0.68f), x(0.53f), y(0.60f), x(0.58f), y(0.57f))
            close()
        }
        drawPath(path = tenderloinHighlight, color = meatHighlightColor)

        // 4. Delicate marbling lines.
        val marbleStroke = Stroke(width = w * 0.026f, cap = StrokeCap.Round)
        val marble1 = Path().apply {
            moveTo(x(0.27f), y(0.32f))
            cubicTo(x(0.34f), y(0.28f), x(0.42f), y(0.29f), x(0.48f), y(0.33f))
        }
        drawPath(path = marble1, color = marblingColor, style = marbleStroke)

        val marble2 = Path().apply {
            moveTo(x(0.64f), y(0.33f))
            cubicTo(x(0.70f), y(0.36f), x(0.76f), y(0.41f), x(0.78f), y(0.47f))
        }
        drawPath(path = marble2, color = marblingColor, style = marbleStroke)

        val marble3 = Path().apply {
            moveTo(x(0.60f), y(0.66f))
            cubicTo(x(0.65f), y(0.68f), x(0.70f), y(0.67f), x(0.74f), y(0.63f))
        }
        drawPath(path = marble3, color = marblingColor, style = marbleStroke)

        // 5. Iconic ivory T-Bone (top crossbar + vertical stem curving cleanly into the cut).
        val bonePath = Path().apply {
            // Left end of T crossbar
            moveTo(x(0.24f), y(0.49f))
            cubicTo(x(0.24f), y(0.44f), x(0.34f), y(0.44f), x(0.49f), y(0.45f))
            // Right end of T crossbar
            cubicTo(x(0.64f), y(0.46f), x(0.74f), y(0.45f), x(0.75f), y(0.50f))
            cubicTo(x(0.76f), y(0.55f), x(0.66f), y(0.55f), x(0.55f), y(0.54f))
            // Down right side of T stem
            cubicTo(x(0.52f), y(0.63f), x(0.53f), y(0.75f), x(0.47f), y(0.79f))
            // Rounded tip of stem
            cubicTo(x(0.42f), y(0.81f), x(0.38f), y(0.76f), x(0.40f), y(0.70f))
            // Up left side of T stem
            cubicTo(x(0.43f), y(0.62f), x(0.44f), y(0.56f), x(0.43f), y(0.53f))
            // Back along underside of left crossbar
            cubicTo(x(0.33f), y(0.53f), x(0.24f), y(0.54f), x(0.24f), y(0.49f))
            close()
        }
        drawPath(path = bonePath, color = boneFillColor)
        drawPath(
            path = bonePath,
            color = boneStrokeColor,
            style = Stroke(width = w * 0.028f, join = StrokeJoin.Round),
        )

        // Small marrow dimple on the T junction for crisp character even at small sizes.
        drawCircle(
            color = boneStrokeColor.copy(alpha = 0.55f * alpha),
            radius = w * 0.025f,
            center = Offset(x(0.49f), y(0.50f)),
        )
    }
}
