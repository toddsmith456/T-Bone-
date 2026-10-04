package social.tbone.settings

/** How much video data an inline player may request. */
enum class VideoQuality(val prefValue: String, val label: String) {
    OFF("off", "Off"),
    LOW("low", "Low"),
    REGULAR("regular", "Regular"),
    ;

    companion object {
        fun fromPref(value: String?): VideoQuality =
            entries.firstOrNull { it.prefValue == value } ?: REGULAR
    }
}

/** When an inline video is prepared and when playback begins. */
enum class VideoPlaybackMode(val prefValue: String, val label: String) {
    PLAY_IMMEDIATELY("play_immediately", "Load and play immediately"),
    PLAY_ON_TAP("play_on_tap", "Load immediately, play on tap"),
    LOAD_ON_TAP("load_on_tap", "Load and play on tap"),
    ;

    companion object {
        fun fromPref(value: String?): VideoPlaybackMode =
            entries.firstOrNull { it.prefValue == value } ?: PLAY_ON_TAP
    }
}
