package social.tbone.settings

/** Visual treatment for the account switch control in the Write composer. */
enum class WriteAccountSwitcherStyle(val label: String) {
    AVATAR("Avatar"),
    PROFILE_NAME("Profile name"),
    THREE_WORD_HASH("3-word hash");

    companion object {
        fun fromPref(value: String?): WriteAccountSwitcherStyle =
            entries.firstOrNull { it.name == value } ?: AVATAR
    }
}
