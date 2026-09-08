package social.tbone.settings

/**
 * App-wide color theme. Stored in DataStore.
 *
 * DARK is the original Bony look (pure black backgrounds — true AMOLED).
 * LIGHT is a bright white paper palette; CREAM is the warm off-white of an
 * old book page, an in-between for people who don't like dark or bright
 * white. The chosen accent color applies in all three modes.
 */
enum class ThemeMode(val prefValue: String, val label: String) {
    LIGHT("light", "LIGHT"),
    DARK("dark", "DARK"),
    CREAM("cream", "CREAM"),
    ;

    val isDark: Boolean get() = this == DARK

    companion object {
        fun fromPref(value: String?): ThemeMode =
            entries.firstOrNull { it.prefValue == value } ?: DARK
    }
}
