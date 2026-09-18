// SPDX-License-Identifier: MIT
package dev.youniversal.theme

/** Text/edge contrast level applied on top of the resolved [androidx.compose.material3.ColorScheme]. */
public enum class YouniversalContrast {
    /** The palette as designed: 4.5:1 minimum for every text pairing (see `tools/palette_lab.py`). */
    Default,

    /**
     * Pushes foreground colors toward pure black/white, strengthens the outline and flattens
     * container tinting. Use it for bright outdoor light or low-vision accessibility.
     */
    High,
    ;

    /** A short, user-facing label for settings screens. */
    public val label: String
        get() =
            when (this) {
                Default -> "Default"
                High -> "High"
            }
}
