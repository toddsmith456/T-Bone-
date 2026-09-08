package social.tbone.settings

/**
 * How inline images are handled in note cards.
 *
 * Stored in DataStore as a plain string so the preference survives restarts
 * and stays consistent across screens (feed, thread, notifications, profile).
 */
enum class ImageLoadMode(val prefValue: String, val label: String) {
    /** Do not fetch images inline — keep the tap-to-open placeholder. */
    OFF("off", "OFF"),

    /** Fetch and display images inline at full quality. */
    ON("on", "ON"),

    /** Fetch and display images inline, decoded at a lower resolution for speed. */
    LOW_QUALITY("low", "LOW"),
    ;

    companion object {
        fun fromPref(value: String?): ImageLoadMode =
            entries.firstOrNull { it.prefValue == value } ?: ON
    }
}
