package social.tbone.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.colorToHex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private val WHEEL_SIZE = 220.dp
private val BAR_WIDTH = 44.dp
private val BAR_HEIGHT = 220.dp

/**
 * Accent color picker: a round hue/saturation wheel paired with a vertical
 * value (brightness) bar, so you can dial in an exact color. The accent
 * live-updates via [onPick]; "DEFAULT GREEN" resets to the original accent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccentColorSheet(
    initial: Color,
    onPick: (Color) -> Unit,
    onCommit: (Color) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initialHsv = remember(initial) { rgbToHsv(initial) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    val current = hsvColor(hue, sat, value)

    // Emit every change so the app re-themes live while dragging.
    fun emit() = onPick(hsvColor(hue, sat, value))

    ModalBottomSheet(
        onDismissRequest = {
            onCommit(current)
            onDismiss()
        },
        containerColor = BonyColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "accent color",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // ── Hue + saturation wheel ───────────────────────────────────
                Box(
                    modifier = Modifier
                        .size(WHEEL_SIZE)
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                val hsv = wheelAt(pos, size)
                                hue = hsv[0]; sat = hsv[1]
                                emit()
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val hsv = wheelAt(change.position, size)
                                hue = hsv[0]; sat = hsv[1]
                                emit()
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val r = size.minDimension / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            brush = Brush.sweepGradient(
                                listOf(
                                    Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                                    Color.Blue, Color.Magenta, Color.Red,
                                ),
                                center = center,
                            ),
                            radius = r,
                            center = center,
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                                center = center,
                                radius = r,
                            ),
                            radius = r,
                            center = center,
                        )
                        val angleRad = hue * PI.toFloat() / 180f
                        val thumbR = r * sat
                        val thumb = Offset(
                            x = center.x + cos(angleRad) * thumbR,
                            y = center.y + sin(angleRad) * thumbR,
                        )
                        drawCircle(Color.White, radius = 8.dp.toPx(), center = thumb, style = Stroke(2.dp.toPx()))
                        drawCircle(hsvColor(hue, sat, 1f), radius = 4.dp.toPx(), center = thumb)
                    }
                }

                Spacer(Modifier.width(26.dp))

                // ── Value (brightness) bar ───────────────────────────────────
                var barHeightPx by remember { mutableStateOf(1) }
                Box(
                    modifier = Modifier
                        .width(BAR_WIDTH)
                        .height(BAR_HEIGHT)
                        .onSizeChanged { barHeightPx = it.height.coerceAtLeast(1) }
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                value = (1f - pos.y / barHeightPx.toFloat()).coerceIn(0f, 1f)
                                emit()
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                value = (1f - change.position.y / barHeightPx.toFloat()).coerceIn(0f, 1f)
                                emit()
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawRect(
                            brush = Brush.verticalGradient(listOf(hsvColor(hue, sat, 1f), Color.Black)),
                        )
                        val thumbY = (1f - value) * size.height
                        drawCircle(
                            Color.White,
                            radius = 8.dp.toPx(),
                            center = Offset(size.width / 2f, thumbY),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Preview + hex + actions ──────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .border(1.dp, BonyColors.RuleStrong, CircleShape)
                        .background(current, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = colorToHex(current),
                    style = BonyType.meta.copy(color = BonyColors.TextDim),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val def = BonyColors.DefaultAccent
                    val hsv = rgbToHsv(def)
                    hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                    onReset()
                }) {
                    Text("DEFAULT GREEN", style = BonyType.tag.copy(color = BonyColors.TextMute))
                }
            }

            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                onCommit(current)
                onDismiss()
            }) {
                Text("done", style = BonyType.tag.copy(color = BonyColors.Accent))
            }
        }
    }
}

/** Maps a touch position on the wheel to [hue, saturation] (value untouched). */
private fun wheelAt(pos: Offset, size: IntSize): FloatArray {
    val r = min(size.width, size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val dx = pos.x - center.x
    val dy = pos.y - center.y
    val dist = sqrt(dx * dx + dy * dy)
    if (dist < 1f) return floatArrayOf(0f, 0f)
    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    if (angle < 0f) angle += 360f
    return floatArrayOf(angle.coerceIn(0f, 360f), (dist / r).coerceIn(0f, 1f))
}

/** RGB → HSV. Returns [hue 0..360, saturation 0..1, value 0..1]. */
private fun rgbToHsv(c: Color): FloatArray {
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val delta = max - min
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max == 0f) 0f else delta / max
    return floatArrayOf(h, s, max)
}

/** HSV → Compose Color (standalone helper; no dependency on optional APIs). */
private fun hsvColor(h: Float, s: Float, v: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f
    val c = v * s
    val x = c * (1f - abs(((hh / 60f) % 2f) - 1f))
    val m = v - c
    val (rp, gp, bp) = when {
        hh < 60f -> Triple(c, x, 0f)
        hh < 120f -> Triple(x, c, 0f)
        hh < 180f -> Triple(0f, c, x)
        hh < 240f -> Triple(0f, x, c)
        hh < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(red = rp + m, green = gp + m, blue = bp + m, alpha = 1f)
}
