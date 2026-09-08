package social.tbone.settings

/**
 * How user avatars are rendered in content (feed, threads, notifications, profiles).
 *
 * Stored in DataStore as a plain string. Mirrors the image-loading modes:
 * INITIAL shows the deterministic letter square; LOW and REGULAR load the
 * user's actual profile picture from kind-0 metadata at different resolutions.
 */
enum class AvatarMode(val prefValue: String, val label: String) {
    /** Deterministic initial square — no network, matches the original Bony look. */
    INITIAL("initial", "INITIAL"),

    /** Real avatar, decoded small (faster, lighter). */
    LOW("low", "LOW"),

    /** Real avatar at full quality. */
    REGULAR("regular", "REGULAR"),
    ;

    companion object {
        fun fromPref(value: String?): AvatarMode =
            entries.firstOrNull { it.prefValue == value } ?: REGULAR
    }
}
