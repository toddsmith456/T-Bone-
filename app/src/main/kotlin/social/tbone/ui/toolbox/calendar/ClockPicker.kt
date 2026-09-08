package social.tbone.ui.toolbox.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class ClockMode { HOUR, MINUTE }

/**
 * An analog clock face for picking a time, in the app's style: tap or drag
 * the hand to the hour, then the minute, and choose AM/PM. Smooth, simple,
 * and precise to the minute — no dials to fiddle with.
 */
@Composable
fun ClockPicker(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(ClockMode.HOUR) }
    var hour12 by remember { mutableStateOf(((initial.hour + 11) % 12) + 1) } // 1..12
    var minute by remember { mutableStateOf(initial.minute) }
    var am by remember { mutableStateOf(initial.hour < 12) }

    val clockSize = 260.dp
    val radiusPx = with(LocalDensity.current) { (clockSize / 2).toPx() }
    val numberRadiusPx = radiusPx * 0.80f
    val textMeasurer = rememberTextMeasurer()

    fun pick(size: androidx.compose.ui.unit.IntSize, position: Offset) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dx = position.x - cx
        val dy = position.y - cy
        val angleDeg = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90 + 360) % 360).toFloat()
        when (mode) {
            ClockMode.HOUR -> {
                val idx = ((angleDeg / 30f).roundToInt()) % 12
                hour12 = if (idx == 0) 12 else idx
            }
            ClockMode.MINUTE -> {
                minute = ((angleDeg / 6f).roundToInt()) % 60
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg.copy(alpha = 0.85f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "pick a time",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            // Selected time readout.
            val displayHour = if (am) hour12 % 12 else (hour12 % 12) + 12
            val displayTime = LocalTime.of(displayHour, minute)
            Text(
                text = formatTime(displayTime.atDate(java.time.LocalDate.now()).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()),
                style = BonyType.body.copy(color = BonyColors.Accent),
                modifier = Modifier.padding(bottom = 10.dp),
            )

            // Mode chips.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClockChip("hour", mode == ClockMode.HOUR) { mode = ClockMode.HOUR }
                Spacer(Modifier.width(8.dp))
                ClockChip("minute", mode == ClockMode.MINUTE) { mode = ClockMode.MINUTE }
            }
            Spacer(Modifier.height(12.dp))

            // Clock face.
            Box(
                modifier = Modifier
                    .size(clockSize)
                    .pointerInput(mode) {
                        detectTapGestures { pick(this.size, it) }
                    }
                    .pointerInput(mode) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            pick(this.size, change.position)
                        }
                    },
            ) {
                val handDeg = when (mode) {
                    ClockMode.HOUR -> (hour12 % 12) * 30f - 90f
                    ClockMode.MINUTE -> minute * 6f - 90f
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(color = BonyColors.Bg, radius = size.minDimension / 2f)
                    drawCircle(
                        color = BonyColors.RuleStrong,
                        radius = size.minDimension / 2f - 1f,
                        style = Stroke(width = 1.5f),
                    )
                    // 12 tick marks.
                    for (i in 0 until 12) {
                        val a = (i * 30f - 90f) * (PI.toFloat() / 180f)
                        val inner = c.x - radiusPx * 0.94f
                        val outer = c.x - radiusPx * 0.88f
                        drawLine(
                            color = BonyColors.TextMute,
                            start = Offset(c.x + inner * cos(a), c.y + inner * sin(a)),
                            end = Offset(c.x + outer * cos(a), c.y + outer * sin(a)),
                            strokeWidth = if (i % 3 == 0) 2.5f else 1.5f,
                            cap = StrokeCap.Round,
                        )
                    }
                    // Hand.
                    val a = handDeg * (PI.toFloat() / 180f)
                    val handLen = if (mode == ClockMode.HOUR) radiusPx * 0.55f else radiusPx * 0.75f
                    drawLine(
                        color = BonyColors.Accent,
                        start = c,
                        end = Offset(c.x + handLen * cos(a), c.y + handLen * sin(a)),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(color = BonyColors.Accent, radius = 5f, center = c)
                }
                // Hour numbers — drawn inside the canvas so every glyph is
                // perfectly centered on its dial position (no offset drift).
                val hourStyle = BonyType.meta.copy(
                    color = BonyColors.Text,
                )
                val hourActiveStyle = BonyType.meta.copy(
                    color = BonyColors.Accent,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    for (i in 1..12) {
                        val a = (i * 30f - 90f) * (PI.toFloat() / 180f)
                        val px = c.x + numberRadiusPx * cos(a)
                        val py = c.y + numberRadiusPx * sin(a)
                        val isActive = (i == hour12 && mode == ClockMode.HOUR)
                        val layout = textMeasurer.measure(
                            AnnotatedString("$i"),
                            style = if (isActive) hourActiveStyle else hourStyle,
                        )
                        drawText(
                            layout,
                            topLeft = Offset(px - layout.size.width / 2f, py - layout.size.height / 2f),
                        )
                    }
                }
            }

            // AM / PM.
            Row(
                modifier = Modifier.padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClockChip("AM", am) { am = true }
                Spacer(Modifier.width(8.dp))
                ClockChip("PM", !am) { am = false }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "cancel",
                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(10.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "ok",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier
                        .border(1.dp, BonyColors.AccentDim)
                        .clickable {
                            val h = if (am) hour12 % 12 else (hour12 % 12) + 12
                            onConfirm(LocalTime.of(h, minute))
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ClockChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = BonyType.tag.copy(color = if (active) BonyColors.Accent else BonyColors.TextMute),
        modifier = Modifier
            .border(1.dp, if (active) BonyColors.Accent else BonyColors.Rule)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
