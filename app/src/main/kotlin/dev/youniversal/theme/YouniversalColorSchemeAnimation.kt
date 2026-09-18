// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp

/**
 * Interpolates every color role of this scheme toward [other].
 *
 * Used to morph between themes. Because all 48 Material 3 roles are blended, nothing snaps: a
 * switch from Cream to Dark sweeps the surfaces, the accents and the outlines together.
 */
internal fun ColorScheme.youniversalBlend(other: ColorScheme, fraction: Float): ColorScheme =
    copy(
        primary = lerp(primary, other.primary, fraction),
        onPrimary = lerp(onPrimary, other.onPrimary, fraction),
        primaryContainer = lerp(primaryContainer, other.primaryContainer, fraction),
        onPrimaryContainer = lerp(onPrimaryContainer, other.onPrimaryContainer, fraction),
        inversePrimary = lerp(inversePrimary, other.inversePrimary, fraction),
        secondary = lerp(secondary, other.secondary, fraction),
        onSecondary = lerp(onSecondary, other.onSecondary, fraction),
        secondaryContainer = lerp(secondaryContainer, other.secondaryContainer, fraction),
        onSecondaryContainer = lerp(onSecondaryContainer, other.onSecondaryContainer, fraction),
        tertiary = lerp(tertiary, other.tertiary, fraction),
        onTertiary = lerp(onTertiary, other.onTertiary, fraction),
        tertiaryContainer = lerp(tertiaryContainer, other.tertiaryContainer, fraction),
        onTertiaryContainer = lerp(onTertiaryContainer, other.onTertiaryContainer, fraction),
        background = lerp(background, other.background, fraction),
        onBackground = lerp(onBackground, other.onBackground, fraction),
        surface = lerp(surface, other.surface, fraction),
        onSurface = lerp(onSurface, other.onSurface, fraction),
        surfaceVariant = lerp(surfaceVariant, other.surfaceVariant, fraction),
        onSurfaceVariant = lerp(onSurfaceVariant, other.onSurfaceVariant, fraction),
        surfaceTint = lerp(surfaceTint, other.surfaceTint, fraction),
        inverseSurface = lerp(inverseSurface, other.inverseSurface, fraction),
        inverseOnSurface = lerp(inverseOnSurface, other.inverseOnSurface, fraction),
        error = lerp(error, other.error, fraction),
        onError = lerp(onError, other.onError, fraction),
        errorContainer = lerp(errorContainer, other.errorContainer, fraction),
        onErrorContainer = lerp(onErrorContainer, other.onErrorContainer, fraction),
        outline = lerp(outline, other.outline, fraction),
        outlineVariant = lerp(outlineVariant, other.outlineVariant, fraction),
        scrim = lerp(scrim, other.scrim, fraction),
        surfaceBright = lerp(surfaceBright, other.surfaceBright, fraction),
        surfaceDim = lerp(surfaceDim, other.surfaceDim, fraction),
        surfaceContainer = lerp(surfaceContainer, other.surfaceContainer, fraction),
        surfaceContainerHigh = lerp(surfaceContainerHigh, other.surfaceContainerHigh, fraction),
        surfaceContainerHighest = lerp(surfaceContainerHighest, other.surfaceContainerHighest, fraction),
        surfaceContainerLow = lerp(surfaceContainerLow, other.surfaceContainerLow, fraction),
        surfaceContainerLowest = lerp(surfaceContainerLowest, other.surfaceContainerLowest, fraction),
        primaryFixed = lerp(primaryFixed, other.primaryFixed, fraction),
        primaryFixedDim = lerp(primaryFixedDim, other.primaryFixedDim, fraction),
        onPrimaryFixed = lerp(onPrimaryFixed, other.onPrimaryFixed, fraction),
        onPrimaryFixedVariant = lerp(onPrimaryFixedVariant, other.onPrimaryFixedVariant, fraction),
        secondaryFixed = lerp(secondaryFixed, other.secondaryFixed, fraction),
        secondaryFixedDim = lerp(secondaryFixedDim, other.secondaryFixedDim, fraction),
        onSecondaryFixed = lerp(onSecondaryFixed, other.onSecondaryFixed, fraction),
        onSecondaryFixedVariant = lerp(onSecondaryFixedVariant, other.onSecondaryFixedVariant, fraction),
        tertiaryFixed = lerp(tertiaryFixed, other.tertiaryFixed, fraction),
        tertiaryFixedDim = lerp(tertiaryFixedDim, other.tertiaryFixedDim, fraction),
        onTertiaryFixed = lerp(onTertiaryFixed, other.onTertiaryFixed, fraction),
        onTertiaryFixedVariant = lerp(onTertiaryFixedVariant, other.onTertiaryFixedVariant, fraction),
    )

/**
 * Holds a [ColorScheme] that animates toward [target] whenever it changes.
 *
 * The first composition returns [target] untouched, so there is no fade-in when an app launches.
 */
@Composable
internal fun rememberAnimatedColorScheme(
    target: ColorScheme,
    spec: FiniteAnimationSpec<Float> = YouniversalMotion.ThemeColorSpec,
): ColorScheme {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(target) {
        if (target != to) {
            from = to
            to = target
            progress.snapTo(0f)
            progress.animateTo(targetValue = 1f, animationSpec = spec)
        }
    }

    val fraction = progress.value
    return if (fraction >= 1f) to else from.youniversalBlend(to, fraction)
}
