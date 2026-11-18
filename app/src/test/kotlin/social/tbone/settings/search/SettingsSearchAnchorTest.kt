package social.tbone.settings.search

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [SettingsCatalog] and the UI in step.
 *
 * Every catalog entry must be claimed by an anchor in the composables, and
 * every anchor written in the UI must exist in the catalog. Without this, a
 * search hit can point at a row that no longer exists (or a new row can ship
 * that search cannot find) — exactly the drift that made the old hand-written
 * keyword blobs unreliable.
 *
 * Anchors are declared as `SettingsAnchor("group", "anchor") { … }` in the
 * settings screens; the test scans the source tree for those literals.
 */
class SettingsSearchAnchorTest {

    private val sourceRoots = listOf(
        "src/main/kotlin/social/tbone/ui/settings",
        "src/main/kotlin/social/tbone/ui/home",
        "src/main/kotlin/social/tbone/ui/profile",
        "app/src/main/kotlin/social/tbone/ui/settings",
        "app/src/main/kotlin/social/tbone/ui/home",
        "app/src/main/kotlin/social/tbone/ui/profile",
    )

    private val anchorPattern = Regex("""SettingsAnchor\(\s*"([^"]+)"\s*\)""")

    private fun sourceFiles(): List<File> {
        sourceRoots.forEach { root ->
            val dir = File(root)
            if (dir.isDirectory) {
                return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
        }
        return emptyList()
    }

    /** Anchors claimed in the UI, as anchor names (sections make them unique). */
    private fun claimedAnchors(): Set<String> {
        val files = sourceFiles()
        assertTrue(
            "source scan found no settings composables — check the working directory of the test run",
            files.isNotEmpty(),
        )
        return files.flatMap { file ->
            anchorPattern.findAll(file.readText()).map { it.groupValues[1] }.toList()
        }.toSet()
    }

    private fun catalogAnchors(): Set<String> =
        SettingsCatalog.entries.map { it.anchor }.toSet()

    /**
     * Anchors whose catalog rows exist but are not yet marked in the UI.
     *
     * Anchors are additive: an entry can be searchable (it appears in results,
     * with a breadcrumb) before its row carries a marker. This list is the
     * backlog, and the test fails if an entry is neither marked nor listed.
     */
    private val knownUnanchored = setOf(
        // Main Settings rows that still have no marker of their own.
        "orbot_install", "font", "confirm_links", "database", "youniversal_reset",
        "accent_seed", "reactions",
        // Content Filters: the AI scanner block and the word-matching mode row.
        "ai_scanner", "ai_sensitivity", "ai_scan_avatars", "ai_scan_banners",
        "ai_exempt_follows", "ai_require_pin", "ai_models", "ai_clear_cache", "word_match_mode",
        // Relay / Blossom / Accounts / Reactions sub-screens filter at section level.
        "relay_list", "relay_add", "relay_citrine", "relay_import_export", "relay_global",
        "relay_enable_all",
        "blossom_servers", "blossom_add_server",
        "accounts_active", "accounts_hidden", "accounts_hide", "accounts_add",
        "reactions_multi", "reactions_saved",
    )

    /**
     * Regression guard for the Settings-row overlap bug.
     *
     * Anchors used to be emitted as bare statements (`SettingsAnchor("x")` with no
     * block), which still wrapped the row in a zero-height Box and stole a slot
     * from the enclosing Row/Column. Every anchor must now wrap real content.
     */
    @Test
    fun noAnchorIsEmittedAsABareStatement() {
        val bare = Regex("""^\s*Settings(?:Section)?Anchor\(\s*"[^"]+"\s*\)\s*$""", RegexOption.MULTILINE)
        val offenders = sourceFiles().flatMap { file ->
            bare.findAll(file.readText()).map { "${file.name}: ${it.value.trim()}" }.toList()
        }
        assertTrue(
            "anchors must wrap content, never stand alone (they break Row/Column layout): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun everyCatalogEntryIsAnchoredOrKnown() {
        val missing = catalogAnchors() - claimedAnchors() - knownUnanchored
        assertTrue(
            "catalog entries with no anchor and not on the known backlog: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun noUiAnchorIsMissingFromTheCatalog() {
        val orphaned = claimedAnchors() - catalogAnchors()
        assertTrue(
            "UI anchors missing from SettingsCatalog (search cannot find them): $orphaned",
            orphaned.isEmpty(),
        )
    }

    @Test
    fun subScreensAlsoCarrySectionAnchors() {
        val files = sourceFiles()
        val sections = files.flatMap { file ->
            Regex("""SettingsSectionAnchor\(\s*"([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue("content filters items are not section-anchored", sections.contains("cf_bleep_words"))
        assertTrue("screen time is not section-anchored", sections.contains("st_all"))
    }
}
