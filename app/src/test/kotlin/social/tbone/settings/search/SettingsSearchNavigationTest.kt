package social.tbone.settings.search

import java.io.File
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchNavigationTest {
    private fun source(relative: String): String {
        val file = listOf(File("src/main/kotlin/social/tbone/$relative"), File("app/src/main/kotlin/social/tbone/$relative")).firstOrNull { it.isFile }
        assertNotNull("Missing source: $relative", file)
        return file!!.readText()
    }

    @Test fun ordinaryNavigationNeverInjectsTheTemplateAsAQuery() {
        listOf("settings", "content_filters", "screen_time_settings", "relay_management", "blossom_settings", "account_management").forEach {
            assertEquals("$it?q=", settingsDestination("$it?q={q}"))
        }
    }

    @Test fun arbitraryQueriesAreEncodedExactlyOnce() {
        listOf("", "content filters", "a&b=c#d?e/f+g%h", "日本語 café", "{q}").forEach { query ->
            val route = settingsDestination("settings?q={q}", query)
            assertEquals(query, URLDecoder.decode(route.substringAfter("?q="), "UTF-8"))
            assertFalse(route.contains("{q}"))
            assertFalse(route.contains(" "))
        }
    }

    @Test fun eachClickedResultUsesItsOwnDestinationNotTheTopHit() {
        val prefixes = mapOf(
            SettingsGroup.SETTINGS to "settings", SettingsGroup.REACTIONS to "settings",
            SettingsGroup.CONTENT_FILTERS to "content_filters", SettingsGroup.SCREEN_TIME to "screen_time_settings",
            SettingsGroup.RELAYS to "relay_management", SettingsGroup.BLOSSOM to "blossom_settings",
            SettingsGroup.ACCOUNTS to "account_management",
        )
        SettingsCatalog.entries.forEach { entry ->
            val route = settingsSearchDestination(entry.id)!!
            assertEquals(entry.id, prefixes[entry.group], route.substringBefore('?'))
            assertEquals(entry.title, URLDecoder.decode(route.substringAfter("?q="), "UTF-8"))
        }
        assertNull(settingsSearchDestination("unknown"))
    }

    @Test fun generatedDestinationsExistInTheNavigationGraph() {
        val nav = source("ui/BonyNavHost.kt")
        SettingsCatalog.entries.forEach {
            val pattern = settingsSearchDestination(it.id)!!.substringBefore('?') + "?q={q}"
            assertTrue("Missing route $pattern", nav.contains("\"$pattern\""))
        }
    }

    @Test fun ordinaryCallbacksMustConcretizeQueryPatterns() {
        val nav = source("ui/BonyNavHost.kt")
        val bad = Regex("navigate\\(ROUTE_(SETTINGS|CONTENT_FILTERS|SCREEN_TIME|RELAY_MANAGEMENT|BLOSSOM|ACCOUNT_MANAGEMENT)\\)")
        assertFalse("Route template passed directly into navigate", bad.containsMatchIn(nav))
        assertFalse("Never rerank after clicking", nav.contains(".bestHit(query)"))
        assertTrue(source("ui/settings/SettingsScreen.kt").contains("onOpenSearchHit(hit.entry.id)"))
    }

    @Test fun secondarySettingsScreensCannotHideControlsBehindSearch() {
        listOf("ContentFiltersScreen", "ScreenTimeSettingsScreen", "RelayManagementScreen", "BlossomSettingsScreen", "AccountManagementScreen").forEach { name ->
            val text = source("ui/settings/$name.kt")
            assertFalse("$name has redundant search field", text.contains("SettingsSearchField("))
            assertFalse("$name resets query during composition", text.contains("value.isEmpty() && initialSearch.isNotEmpty()"))
            assertFalse("$name filters controls", text.contains("LocalSettingsScreenSearch provides screenSearch"))
        }
        val filters = source("ui/settings/ContentFiltersScreen.kt")
        assertTrue(filters.contains("if (parentalEnabled && !filtersUnlocked)"))
        assertTrue(filters.contains("LocalSettingsScreenSearch provides null"))
        assertTrue(filters.indexOf("ParentalPinLock(") < filters.indexOf("LazyColumn("))
    }

    @Test fun reactionHitsStayInTheRealSettingsSection() {
        SettingsCatalog.entries.filter { it.id.startsWith("reactions.") }.forEach {
            assertEquals(SettingsGroup.SETTINGS, it.group)
            assertEquals("set_reactions", it.section)
            assertTrue(SettingsSections.visible(it.title, "set_reactions"))
            assertTrue(settingsSearchDestination(it.id)!!.startsWith("settings?"))
        }
    }

    @Test fun playerSecondaryActionsStayIconOnlyAndInOneRow() {
        val player = source("ui/audio/AudioPlayerScreen.kt").substringAfter("private fun FullPlayer(").substringBefore("private fun NowPlayingIdentity")
        assertFalse(player.contains("TextButton("))
        assertFalse(player.contains("FlowRow("))
        listOf("Rewind 15 seconds", "Forward 30 seconds", "Playback speed", "Toggle elapsed or remaining time", "Open playback queue").forEach {
            assertTrue("Missing accessible icon label: $it", player.contains(it))
        }
        assertTrue(player.contains("Modifier.size(48.dp)"))
    }
}
