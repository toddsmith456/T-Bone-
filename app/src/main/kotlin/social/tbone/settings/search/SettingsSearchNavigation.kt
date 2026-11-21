package social.tbone.settings.search

/** A concrete destination, never an unexpanded navigation route pattern. */
fun settingsDestination(routePattern: String, query: String = ""): String =
    routePattern.replace("{q}", java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20"))

/** Resolve the selected result, not the highest-ranked result of its old query. */
fun settingsSearchDestination(entryId: String): String? {
    val entry = SettingsCatalog.entries.firstOrNull { it.id == entryId } ?: return null
    val route = when (entry.group) {
        SettingsGroup.SETTINGS, SettingsGroup.REACTIONS -> "settings?q={q}"
        SettingsGroup.CONTENT_FILTERS -> "content_filters?q={q}"
        SettingsGroup.SCREEN_TIME -> "screen_time_settings?q={q}"
        SettingsGroup.RELAYS -> "relay_management?q={q}"
        SettingsGroup.BLOSSOM -> "blossom_settings?q={q}"
        SettingsGroup.ACCOUNTS -> "account_management?q={q}"
    }
    return settingsDestination(route, entry.title)
}
