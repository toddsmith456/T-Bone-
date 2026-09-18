// SPDX-License-Identifier: MIT
package dev.youniversal.theme

/**
 * The background themes Youniversal ships.
 *
 * [Auto] follows the system dark-mode setting and resolves to [Light] or [Dark]; [Cream] is a
 * warm, aged-paper theme that is always treated as a light theme.
 */
public enum class YouniversalBackgroundStyle {
    /** Follow the system setting: [Dark] when the system is dark, otherwise [Light]. */
    Auto,

    /** Cool near-white paper with an indigo accent. */
    Light,

    /** Deep neutral charcoal with a soft indigo accent. */
    Dark,

    /** Aged book paper, slightly darker and warmer than a fresh page. */
    Cream,
    ;

    /** Collapses [Auto] into a concrete theme. Every other value returns itself. */
    public fun resolve(isSystemInDarkTheme: Boolean): YouniversalBackgroundStyle =
        when (this) {
            Auto -> if (isSystemInDarkTheme) Dark else Light
            Light -> Light
            Dark -> Dark
            Cream -> Cream
        }

    /**
     * True when the theme paints light content on dark surfaces.
     *
     * Only meaningful on a resolved style; call [resolve] first when the value may be [Auto].
     */
    public val isDark: Boolean
        get() = this == Dark

    /** A short, user-facing label for settings screens. */
    public val label: String
        get() =
            when (this) {
                Auto -> "Auto"
                Light -> "Light"
                Dark -> "Dark"
                Cream -> "Cream"
            }
}
