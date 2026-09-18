// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * A dependency-free tonal palette generator.
 *
 * Material You derives its 13 tones from a seed color. Youniversal does the same thing in plain
 * Kotlin — sRGB to CIE L*a*b*, hold the hue, force `L*` to the requested tone, then shrink chroma
 * until the result fits inside sRGB — so a custom accent seed produces a full, harmonious
 * [ColorScheme] on any API level, with no color-science library to add.
 *
 * The algorithm is mirrored by `tools/palette_lab.py`, which is what generated the built-in
 * palettes and verified their contrast ratios.
 */
public object YouniversalTonalPalette {

    private const val WHITE_X = 0.95047f
    private const val WHITE_Y = 1.0f
    private const val WHITE_Z = 1.08883f

    /**
     * The color at [tone] (0–100) for [seed], keeping the seed's hue.
     *
     * @param chromaScale scales the seed's chroma before generating. Material 3 uses low chroma for
     *   neutral ramps; `0.3f` reads as a soft accent, `1f` keeps full saturation.
     * @param hueShiftDegrees rotates the seed hue, used to derive the tertiary accent.
     */
    public fun tone(
        seed: Color,
        tone: Int,
        chromaScale: Float = 1f,
        hueShiftDegrees: Float = 0f,
    ): Color {
        val clamped = tone.coerceIn(0, 100)
        // The endpoints are exact, matching Material 3's tonal palette: shrinking chroma toward
        // zero would otherwise leave a faint tint on tone 0.
        if (clamped == 0) return Color.Black
        if (clamped == 100) return Color.White
        val target = clamped.toFloat()
        val lab = rgbToLab(seed.red, seed.green, seed.blue)
        val chroma = hypot(lab[1], lab[2]) * chromaScale.coerceAtLeast(0f)
        val hue = atan2(lab[2], lab[1]) + hueShiftDegrees * PI.toFloat() / 180f

        var scaled = chroma
        repeat(128) {
            val xyz = labToXyz(target, scaled * cos(hue), scaled * sin(hue))
            if (isInGamut(xyz)) return xyzToColor(xyz)
            scaled *= 0.97f
        }
        return xyzToColor(labToXyz(target, 0f, 0f))
    }

    /**
     * A complete Material 3 [ColorScheme] built from a single [seed].
     *
     * Secondary is a desaturated echo of the seed, tertiary is the seed rotated 60 degrees, and the
     * neutral ramps carry a whisper of the seed hue so surfaces feel related to the accent instead
     * of generic grey. Error stays a constant red — an accent color should never change what
     * "destructive" looks like.
     */
    public fun colorScheme(seed: Color, isDark: Boolean): ColorScheme {
        fun primary(toneValue: Int): Color = tone(seed, toneValue)
        fun secondary(toneValue: Int): Color = tone(seed, toneValue, chromaScale = 0.34f)
        fun tertiary(toneValue: Int): Color =
            tone(seed, toneValue, chromaScale = 0.5f, hueShiftDegrees = 60f)
        fun neutral(toneValue: Int): Color = tone(seed, toneValue, chromaScale = 0.06f)
        fun neutralVariant(toneValue: Int): Color = tone(seed, toneValue, chromaScale = 0.16f)
        fun error(toneValue: Int): Color = tone(ErrorSeed, toneValue)

        return if (isDark) {
            darkColorScheme(
                primary = primary(80), onPrimary = primary(20), primaryContainer = primary(30),
                onPrimaryContainer = primary(90), inversePrimary = primary(40),
                secondary = secondary(80), onSecondary = secondary(20), secondaryContainer = secondary(30),
                onSecondaryContainer = secondary(90),
                tertiary = tertiary(80), onTertiary = tertiary(20), tertiaryContainer = tertiary(30),
                onTertiaryContainer = tertiary(90),
                background = neutral(6), onBackground = neutral(90),
                surface = neutral(6), onSurface = neutral(90),
                surfaceVariant = neutralVariant(30), onSurfaceVariant = neutralVariant(80),
                surfaceTint = primary(80), inverseSurface = neutral(90), inverseOnSurface = neutral(20),
                error = error(80), onError = error(20), errorContainer = error(30), onErrorContainer = error(90),
                outline = neutralVariant(60), outlineVariant = neutralVariant(30), scrim = Color.Black,
                surfaceBright = neutral(24), surfaceDim = neutral(6),
                surfaceContainerLowest = neutral(4), surfaceContainerLow = neutral(10),
                surfaceContainer = neutral(12), surfaceContainerHigh = neutral(17),
                surfaceContainerHighest = neutral(22),
                primaryFixed = primary(90), primaryFixedDim = primary(80), onPrimaryFixed = primary(10),
                onPrimaryFixedVariant = primary(30),
                secondaryFixed = secondary(90), secondaryFixedDim = secondary(80), onSecondaryFixed = secondary(10),
                onSecondaryFixedVariant = secondary(30),
                tertiaryFixed = tertiary(90), tertiaryFixedDim = tertiary(80), onTertiaryFixed = tertiary(10),
                onTertiaryFixedVariant = tertiary(30),
            )
        } else {
            lightColorScheme(
                primary = primary(40), onPrimary = primary(100), primaryContainer = primary(90),
                onPrimaryContainer = primary(10), inversePrimary = primary(80),
                secondary = secondary(40), onSecondary = secondary(100), secondaryContainer = secondary(90),
                onSecondaryContainer = secondary(10),
                tertiary = tertiary(40), onTertiary = tertiary(100), tertiaryContainer = tertiary(90),
                onTertiaryContainer = tertiary(10),
                background = neutral(98), onBackground = neutral(10),
                surface = neutral(98), onSurface = neutral(10),
                surfaceVariant = neutralVariant(90), onSurfaceVariant = neutralVariant(30),
                surfaceTint = primary(40), inverseSurface = neutral(20), inverseOnSurface = neutral(95),
                error = error(40), onError = error(100), errorContainer = error(90), onErrorContainer = error(10),
                outline = neutralVariant(50), outlineVariant = neutralVariant(80), scrim = Color.Black,
                surfaceBright = neutral(98), surfaceDim = neutral(87),
                surfaceContainerLowest = neutral(100), surfaceContainerLow = neutral(96),
                surfaceContainer = neutral(94), surfaceContainerHigh = neutral(92),
                surfaceContainerHighest = neutral(90),
                primaryFixed = primary(90), primaryFixedDim = primary(80), onPrimaryFixed = primary(10),
                onPrimaryFixedVariant = primary(30),
                secondaryFixed = secondary(90), secondaryFixedDim = secondary(80), onSecondaryFixed = secondary(10),
                onSecondaryFixedVariant = secondary(30),
                tertiaryFixed = tertiary(90), tertiaryFixedDim = tertiary(80), onTertiaryFixed = tertiary(10),
                onTertiaryFixedVariant = tertiary(30),
            )
        }
    }

    /** The default Youniversal accent, used when no seed or wallpaper palette is available. */
    public val DefaultSeed: Color = Color(0xFF3E63DD)

    private val ErrorSeed: Color = Color(0xFFDC362E)

    // ------------------------------------------------------------------ color math

    private fun rgbToLab(r: Float, g: Float, b: Float): FloatArray {
        val linearR = srgbToLinear(r)
        val linearG = srgbToLinear(g)
        val linearB = srgbToLinear(b)
        val x = 0.4124564f * linearR + 0.3575761f * linearG + 0.1804375f * linearB
        val y = 0.2126729f * linearR + 0.7151522f * linearG + 0.0721750f * linearB
        val z = 0.0193339f * linearR + 0.1191920f * linearG + 0.9503041f * linearB
        val fx = pivot(x / WHITE_X)
        val fy = pivot(y / WHITE_Y)
        val fz = pivot(z / WHITE_Z)
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    private fun labToXyz(l: Float, a: Float, b: Float): FloatArray {
        val fy = (l + 16f) / 116f
        return floatArrayOf(
            pivotInverse(fy + a / 500f) * WHITE_X,
            pivotInverse(fy) * WHITE_Y,
            pivotInverse(fy - b / 200f) * WHITE_Z,
        )
    }

    private fun pivot(value: Float): Float =
        if (value > 216f / 24389f) value.pow(1f / 3f) else (841f / 108f) * value + 4f / 29f

    private fun pivotInverse(value: Float): Float =
        if (value.pow(3f) > 216f / 24389f) value.pow(3f) else (108f / 841f) * (value - 4f / 29f)

    private fun srgbToLinear(value: Float): Float =
        if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(value: Float): Float =
        (if (value <= 0.0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - 0.055f)
            .coerceIn(0f, 1f)

    private fun isInGamut(xyz: FloatArray): Boolean {
        val r = 3.2404542f * xyz[0] - 1.5371385f * xyz[1] - 0.4985314f * xyz[2]
        val g = -0.9692660f * xyz[0] + 1.8760108f * xyz[1] + 0.0415560f * xyz[2]
        val b = 0.0556434f * xyz[0] - 0.2040259f * xyz[1] + 1.0572252f * xyz[2]
        return r >= -0.001f && r <= 1.001f &&
            g >= -0.001f && g <= 1.001f &&
            b >= -0.001f && b <= 1.001f
    }

    private fun xyzToColor(xyz: FloatArray): Color =
        Color(
            red = linearToSrgb(3.2404542f * xyz[0] - 1.5371385f * xyz[1] - 0.4985314f * xyz[2]),
            green = linearToSrgb(-0.9692660f * xyz[0] + 1.8760108f * xyz[1] + 0.0415560f * xyz[2]),
            blue = linearToSrgb(0.0556434f * xyz[0] - 0.2040259f * xyz[1] + 1.0572252f * xyz[2]),
        )
}
