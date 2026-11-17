package social.tbone.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import social.tbone.ui.theme.BonyColors

/**
 * Accent-colored outline T-Bone steak vector artwork.
 *
 * Rendered 100% locally via Compose Canvas paths (zero hosted images, zero
 * bandwidth) per NIP-XX Daily Claimable Tokens (`steak`). Matches the clean
 * monochrome outline weight of Material Outlined icons and adapts to every
 * T-Bone theme mode and custom accent color.
 */
@Composable
fun SteakIcon(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = BonyColors.Accent,
    dimmed: Boolean = false,
    contentDescription: String? = "Steak",
) {
    val strokeColor = if (dimmed) tint.copy(alpha = tint.alpha * 0.42f) else tint
    val detailColor = strokeColor.copy(alpha = strokeColor.alpha * 0.78f)

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

        val outerStroke = Stroke(
            width = w * 0.082f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val boneStroke = Stroke(
            width = w * 0.070f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val marbleStroke = Stroke(
            width = w * 0.048f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        // 1. Outer T-Bone steak silhouette (broader strip loin top-left, rounder tenderloin right).
        val outerPath = Path().apply {
            moveTo(x(0.14f), y(0.42f))
            cubicTo(x(0.10f), y(0.18f), x(0.32f), y(0.09f), x(0.56f), y(0.13f))
            cubicTo(x(0.76f), y(0.17f), x(0.91f), y(0.30f), x(0.90f), y(0.50f))
            cubicTo(x(0.89f), y(0.68f), x(0.76f), y(0.84f), x(0.58f), y(0.87f))
            cubicTo(x(0.45f), y(0.89f), x(0.36f), y(0.82f), x(0.28f), y(0.76f))
            cubicTo(x(0.17f), y(0.68f), x(0.16f), y(0.56f), x(0.14f), y(0.42f))
            close()
        }
        drawPath(path = outerPath, color = strokeColor, style = outerStroke)

        // 2. Iconic T-shaped bone outline centered inside the cut.
        val tCrossbar = Path().apply {
            moveTo(x(0.26f), y(0.48f))
            cubicTo(x(0.40f), y(0.46f), x(0.58f), y(0.47f), x(0.74f), y(0.50f))
        }
        drawPath(path = tCrossbar, color = strokeColor, style = boneStroke)

        val tStem = Path().apply {
            moveTo(x(0.49f), y(0.47f))
            cubicTo(x(0.49f), y(0.58f), x(0.48f), y(0.68f), x(0.45f), y(0.76f))
        }
        drawPath(path = tStem, color = strokeColor, style = boneStroke)

        // 3. Subtle marbling contour arcs on strip loin & tenderloin lobes for instant readability.
        val stripArc = Path().apply {
            moveTo(x(0.29f), y(0.30f))
            cubicTo(x(0.38f), y(0.25f), x(0.50f), y(0.25f), x(0.58f), y(0.30f))
        }
        drawPath(path = stripArc, color = detailColor, style = marbleStroke)

        val tenderloinArc = Path().apply {
            moveTo(x(0.61f), y(0.66f))
            cubicTo(x(0.67f), y(0.68f), x(0.73f), y(0.66f), x(0.76f), y(0.61f))
        }
        drawPath(path = tenderloinArc, color = detailColor, style = marbleStroke)
    }
}
