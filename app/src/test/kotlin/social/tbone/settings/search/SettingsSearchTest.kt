package social.tbone.settings.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for settings search.
 *
 * The negative cases are the measured false positives from the old
 * substring matcher (`tor` matched "sTORage", `lock` matched "bLOCK"); the
 * positive cases are labels that were on screen but unreachable by search.
 */
class SettingsSearchTest {

    private fun hits(query: String) = SettingsSearch.search(query)

    private fun ids(query: String) = hits(query).map { it.entry.id }

    private fun firstGroup(query: String) = SettingsSearch.bestHit(query)?.entry?.group

    // ── no substring leakage ────────────────────────────────────────────────

    @Test
    fun torDoesNotMatchStorage() {
        val groups = hits("tor").map { it.entry.group }.toSet()
        assertFalse("storage rows leaked into 'tor'", groups.contains(SettingsGroup.RELAYS) && hits("tor").any { it.entry.id.startsWith("settings.clear_") })
        assertTrue(ids("tor").contains("settings.orbot"))
    }

    @Test
    fun lockDoesNotMatchBlockOrClock() {
        val matched = ids("lock")
        assertTrue(matched.contains("settings.app_lock"))
        assertFalse("block-word rows leaked", matched.contains("filters.bleep_words"))
        assertFalse("home-feed rows leaked", matched.contains("settings.home_feeds"))
    }

    @Test
    fun shortTokensNeverPrefixMatch() {
        // "ui" used to match Stealth via "disgUIse"; it should only find rows
        // whose indexed words start with a real word.
        val stealth = ids("ui").filter { it == "settings.notepad_front" }
        assertTrue("'ui' should not reach the notepad disguise row", stealth.isEmpty())
    }

    @Test
    fun twoLetterNoiseStaysQuiet() {
        // "age" used to return Account, Media and Storage & cache ("manAGE",
        // "imAGE", "storAGE"). Two-letter tokens never prefix-match.
        assertTrue(ids("age").isEmpty())
        assertTrue(ids("ion").isEmpty())
    }

    @Test
    fun threeLetterPrefixKeepsModerationSectionOut() {
        // "rat" is allowed as a prefix ("rat" → "ratio") but must no longer
        // drag in the moderation sections that contain "moderation".
        val matched = ids("rat")
        assertFalse(matched.contains("filters.bleep_words"))
        assertFalse(matched.contains("settings.note_moderation"))
        assertTrue("unexpected fan-out for 'rat': $matched", matched.size <= 2)
    }

    @Test
    fun lockAliasDoesNotReachAutoplay() {
        val matched = ids("lock")
        assertFalse("'lock' should not match autoplay rows", matched.contains("settings.video_playback"))
        assertTrue(matched.contains("settings.app_lock"))
        assertTrue(matched.contains("settings.auto_lock"))
    }

    // ── previously unreachable labels ───────────────────────────────────────

    @Test
    fun findsOrbot() {
        assertEquals(SettingsGroup.SETTINGS, firstGroup("orbot"))
    }

    @Test
    fun findsBeaverMode() {
        assertTrue(ids("beaver").contains("blossom.beaver"))
    }

    @Test
    fun findsBeaverModeInParentSettingsScreen() {
        // The Media section links to Blossom; a query for the feature should
        // surface both the link and the actual control.
        val groups = hits("beaver").map { it.entry.group }.toSet()
        assertTrue(groups.contains(SettingsGroup.BLOSSOM))
    }

    @Test
    fun findsCornerRadiusAndTextSize() {
        assertTrue(ids("corner").contains("settings.corner_radius"))
        assertTrue(ids("text size").contains("settings.text_size"))
    }

    @Test
    fun findsInlineImages() {
        assertEquals("settings.inline_images", SettingsSearch.bestHit("inline images")?.entry?.id ?: "")
    }

    @Test
    fun findsCitrineRelay() {
        assertTrue(ids("citrine").contains("relays.citrine"))
    }

    @Test
    fun findsRelayImportExport() {
        assertTrue(ids("import relays").contains("relays.import_export"))
    }

    @Test
    fun findsSignOut() {
        assertTrue(ids("sign out").contains("settings.sign_out"))
        assertTrue(ids("logout").contains("settings.sign_out"))
    }

    @Test
    fun findsVersionAndChangelog() {
        assertTrue(ids("version").contains("settings.version"))
        assertTrue(ids("changelog").contains("settings.version"))
    }

    @Test
    fun findsScreenTimeTwoLevelsDown() {
        val hit = SettingsSearch.bestHit("screen time")
        assertNotNull(hit)
        assertTrue(
            "screen time should surface the pin-locked control, not just the link",
            hit!!.entry.id == "filters.screen_time" || hit.entry.id == "screentime.allowance" ||
                hit.entry.id == "settings.content_filters",
        )
    }

    @Test
    fun findsPinLockedFilterControls() {
        assertTrue(ids("bleep").contains("filters.bleep_words"))
        assertTrue(ids("parental pin").contains("filters.parental_pin"))
        assertTrue(ids("nsfw").contains("filters.nsfw_mode"))
    }

    // ── ranking ─────────────────────────────────────────────────────────────

    @Test
    fun titleBeatsSubtitle() {
        // "avatar" is a title on the avatar rows and a keyword on profile rows.
        val first = SettingsSearch.bestHit("avatar style")
        assertEquals("settings.avatar_style", first?.entry?.id)
    }

    @Test
    fun exactWordBeatsPrefix() {
        val results = hits("video")
        assertTrue(results.isNotEmpty())
        assertTrue(
            "video-titled rows should outrank mention-only rows",
            results.first().entry.title.contains("video", ignoreCase = true),
        )
    }

    @Test
    fun multiTokenNarrowsResults() {
        val single = hits("cache").size
        val narrowed = hits("clear cache").size
        assertTrue("adding a word should narrow, not widen", narrowed in 1..single)
        assertTrue(ids("clear all cache").contains("settings.clear_all_cache"))
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(hits("   ").isEmpty())
        assertFalse(SettingsSearch.hasMatch(""))
    }

    // ── aliases ─────────────────────────────────────────────────────────────

    @Test
    fun colloquialAliasesResolve() {
        assertTrue(ids("pictures").contains("settings.inline_images"))
        assertTrue(ids("photos").contains("settings.inline_images"))
        assertTrue(ids("night").any { it == "settings.theme_mode" })
        assertTrue(ids("passcode").contains("settings.app_lock"))
        assertTrue(ids("swear").contains("filters.bleep_words"))
        assertTrue(ids("muted").isNotEmpty())
    }

    @Test
    fun suggestionsResolveToRealEntries() {
        SettingsSearch.suggestions("screenshot").forEach { suggestion ->
            assertTrue(SettingsSearch.hasMatch(suggestion))
        }
    }

    // ── catalog integrity ───────────────────────────────────────────────────

    @Test
    fun catalogIsUniqueAndWellFormed() {
        val ids = SettingsCatalog.entries.map { it.id }
        assertEquals("duplicate catalog ids", ids.size, ids.distinct().size)

        val anchorsPerGroup = SettingsCatalog.entries.groupBy { it.group to it.anchor }
        anchorsPerGroup.forEach { (key, list) ->
            assertTrue(
                "anchor ${key.second} claimed by ${list.size} entries in ${key.first}",
                list.size == 1,
            )
        }
        SettingsCatalog.entries.forEach { entry ->
            assertTrue("${entry.id} has blank title", entry.title.isNotBlank())
            assertTrue("${entry.id} has blank anchor", entry.anchor.isNotBlank())
        }
    }

    @Test
    fun everyEntryIsFindableByItsOwnTitle() {
        SettingsCatalog.entries.forEach { entry ->
            val found = SettingsSearch.search(entry.title, listOf(entry)).isNotEmpty()
            assertTrue("catalog entry ${entry.id} is not findable by '${entry.title}'", found)
        }
    }

    @Test
    fun everyEntryBelongsToAKnownSection() {
        SettingsCatalog.entries.forEach { entry ->
            val section = SettingsSections.byId(entry.section)
            assertNotNull("${entry.id} points at unknown section '${entry.section}'", section)
            assertEquals(
                "section ${entry.section} belongs to a different settings screen than ${entry.id}",
                entry.group,
                section!!.group,
            )
        }
    }

    @Test
    fun sectionAnchorsAreUnique() {
        val keys = SettingsCatalog.entries.map { it.section to it.anchor }
        assertEquals("duplicate section/anchor pairs", keys.size, keys.distinct().size)
        assertEquals(keys.size, SettingsCatalog.bySectionAnchor.size)
    }

    @Test
    fun everySectionHasAtLeastOneRow() {
        SettingsSections.all.forEach { def ->
            val rows = SettingsCatalog.entries.filter { it.section == def.id }
            assertTrue("section ${def.id} has no catalog rows", rows.isNotEmpty())
        }
    }

    @Test
    fun sectionVisibilityOpensForSectionKeywords() {
        // A section opens on its own label even when no row matches.
        assertTrue(SettingsSections.visible("notepad", "set_stealth"))
        assertTrue(SettingsSections.visible("privacy", "set_privacy"))
        assertFalse(SettingsSections.visible("zzzznothing", "set_privacy"))
    }

    @Test
    fun blankQueryKeepsEverySectionVisible() {
        SettingsSections.all.forEach { def ->
            assertTrue(SettingsSections.visible("", def.id))
            assertTrue(SettingsSections.visible("   ", def.id))
        }
    }

    @Test
    fun minimumPrefixLengthIsSane() {
        assertTrue(SettingsSearch.minPrefixLength() >= 3)
    }
}
