package social.tbone.settings.search

/**
 * Sections are the visual blocks a screen is built from: one per category on
 * the main Settings screen ("media", "privacy", …) and one per block inside a
 * sub-screen ("bleep words", "parental pin", …).
 *
 * Section-level visibility lets a screen hide whole categories while the row
 * anchors inside a visible section highlight the exact control that matched.
 */
object SettingsSections {

    data class Def(
        val id: String,
        val group: SettingsGroup,
        val label: String,
        /** Words that should open the section even with no matching row. */
        val keywords: String = "",
    )

    val all: List<Def> = listOf(
        // Main Settings screen.
        Def("set_ui_declutter", SettingsGroup.SETTINGS, "UI declutter", "compact collapsible categories layout"),
        Def("set_tor_strip", SettingsGroup.SETTINGS, "Tor active", "tor orbot proxy onion network"),
        Def("set_account", SettingsGroup.SETTINGS, "Account", "profile identity signer keys add manage npub"),
        Def("set_relays", SettingsGroup.SETTINGS, "Relays", "relay server connection websocket global personal"),
        Def("set_lists", SettingsGroup.SETTINGS, "Lists", "follow follows offline people mutes"),
        Def("set_content_filters", SettingsGroup.SETTINGS, "Content filters", "filters hide hidden words nsfw sensitive screen time moderation parental"),
        Def("set_home_feeds", SettingsGroup.SETTINGS, "Home feeds", "timeline clock global tabs hashtags polls reorder interval"),
        Def("set_note_actions", SettingsGroup.SETTINGS, "Note actions", "write compose switcher client tag mute report nip89"),
        Def("set_reactions", SettingsGroup.SETTINGS, "Reactions", "emoji likes picker"),
        Def("set_media", SettingsGroup.SETTINGS, "Media", "image video audio playback autoplay preload quality thumbnail blossom upload"),
        Def("set_avatars", SettingsGroup.SETTINGS, "Avatars", "avatar gif animated initials"),
        Def("set_storage_cache", SettingsGroup.SETTINGS, "Storage & cache", "cache memory disk clear database temporary downloads performance"),
        Def("set_theme_engine", SettingsGroup.SETTINGS, "Theme engine", "theme bony youniversal interface material you dynamic"),
        Def("set_appearance", SettingsGroup.SETTINGS, "Appearance", "color accent dark light cream font contrast background palette corner radius text size"),
        Def("set_privacy", SettingsGroup.SETTINGS, "Privacy", "pin duress screenshot lock tor keypad auto timeout scramble external links"),
        Def("set_stealth", SettingsGroup.SETTINGS, "Stealth", "notepad disguise decoy hidden icon"),
        Def("set_debug", SettingsGroup.SETTINGS, "Debug", "logs updates diagnostics crash share export version sign out"),

        // Content filters.
        Def("cf_parental_controls", SettingsGroup.CONTENT_FILTERS, "Parental controls", "screen time limit child parental"),
        Def("cf_nsfw_warnings", SettingsGroup.CONTENT_FILTERS, "NSFW / content warnings", "nsfw explicit sensitive content warning cover hide"),
        Def("cf_ai_scanner", SettingsGroup.CONTENT_FILTERS, "Offline AI media scanner", "ai scanner model offline nsfw sensitivity scan"),
        Def("cf_bleep_words", SettingsGroup.CONTENT_FILTERS, "Bleep words", "bleep swear censor asterisk words"),
        Def("cf_hide_words", SettingsGroup.CONTENT_FILTERS, "Hide words", "hide block words filter"),
        Def("cf_parental_pin", SettingsGroup.CONTENT_FILTERS, "Parental pin", "pin lock parental child"),
        // Screen time.
        Def("st_all", SettingsGroup.SCREEN_TIME, "Screen time", "limit allowance hours minutes"),
        // Relays.
        Def("relay_screen", SettingsGroup.RELAYS, "Relays", "relay server add import export global"),
        // Blossom.
        Def("blossom", SettingsGroup.BLOSSOM, "Blossom uploads", "server upload compress beaver"),
        // Lists.
        // Accounts.
        Def("accounts", SettingsGroup.ACCOUNTS, "Accounts", "account identity hidden pin add"),
        // Home feeds.
        // Profile.
        // Reactions.
    )

    private val byId: Map<String, Def> = all.associateBy { it.id }

    fun byId(id: String): Def? = byId[id]

    /**
     * Whether a section should stay visible for [query].
     *
     * A section is visible when any of its catalog rows matches, or when the
     * section's own label/keywords match — so "stealth" still opens a section
     * whose rows describe a decoy vault. Unknown sections are always visible:
     * a missing registration must never hide content.
     */
    fun visible(
        query: String,
        sectionId: String,
        entries: List<SettingsEntry> = SettingsCatalog.entries,
    ): Boolean {
        if (query.isBlank()) return true
        val rows = entries.filter { it.section == sectionId }
        if (rows.isNotEmpty() && SettingsSearch.hasMatch(query, rows)) return true
        val def = byId[sectionId] ?: return true
        val pseudo = SettingsEntry(
            id = "section:$sectionId",
            group = def.group,
            anchor = "",
            title = def.label,
            keywords = def.keywords.split(' ').filter { it.isNotBlank() },
        )
        return SettingsSearch.hasMatch(query, listOf(pseudo))
    }

    /** Row-level check used to dim rows that are not part of the result set. */
    fun rowMatches(
        query: String,
        entryId: String,
        entries: List<SettingsEntry> = SettingsCatalog.entries,
    ): Boolean {
        if (query.isBlank()) return true
        val entry = entries.firstOrNull { it.id == entryId } ?: return true
        return SettingsSearch.hasMatch(query, listOf(entry))
    }

    /** Sections in the order a screen should render them, for one group. */
    fun forGroup(group: SettingsGroup): List<Def> = all.filter { it.group == group }
}
