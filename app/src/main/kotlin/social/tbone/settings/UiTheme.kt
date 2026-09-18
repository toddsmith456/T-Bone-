package social.tbone.settings

/**
 * Which UI engine the app uses.
 * BONY = original T-Bone theme (BonyColors + JetBrains Mono, 3 modes)
 * YOUNIVERSAL = Youniversal design system (Light/Dark/Cream/Auto + Material You + high contrast etc.)
 */
enum class UiTheme(val prefValue: String, val label: String, val description: String) {
    BONY("bony", "T-Bone", "Classic JetBrains Mono · pure black / cream"),
    YOUNIVERSAL("youniversal", "Youniversal", "Light · Dark · Cream · Auto + Material You"),
    ;

    companion object {
        fun fromPref(value: String?): UiTheme =
            entries.firstOrNull { it.prefValue == value } ?: BONY
    }
}
