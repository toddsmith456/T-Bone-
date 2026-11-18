package social.tbone.settings.search

/**
 * One ranked search hit.
 *
 * [entry] carries the breadcrumb (which settings screen it lives on) and the
 * row anchor; [score] is only used for ordering; [matched] is the query token
 * that won, so the UI can highlight it.
 */
data class SettingsHit(
    val entry: SettingsEntry,
    val score: Float,
    val matched: String,
)

/**
 * Matches a query against [SettingsCatalog].
 *
 * Scoring is field-weighted: an exact word in the row title beats a prefix in
 * the title, which beats a keyword, which beats the subtitle. Tokens are
 * matched on word boundaries (never as raw substrings), each token may match
 * through its colloquial aliases, and a row must satisfy every token — so
 * adding words narrows results instead of widening them.
 */
object SettingsSearch {

    private const val TITLE_WEIGHT = 4f
    private const val KEYWORD_WEIGHT = 2f
    private const val SUBTITLE_WEIGHT = 1f

    /** Characters per token below which prefix matching is disabled. */
    private const val MIN_PREFIX_LEN = 3

    fun search(
        query: String,
        entries: List<SettingsEntry> = SettingsCatalog.entries,
        limit: Int = 40,
    ): List<SettingsHit> {
        val tokens = SettingsSearchKeywords.queryTokens(query)
        if (tokens.isEmpty()) return emptyList()

        val hits = entries.mapNotNull { entry ->
            val scored = score(entry, tokens) ?: return@mapNotNull null
            SettingsHit(entry = entry, score = scored.first, matched = scored.second)
        }
        // Stable ordering: score first, then catalog order (which mirrors the UI).
        val order = entries.withIndex().associate { (i, e) -> e.id to i }
        return hits
            .sortedWith(
                compareByDescending<SettingsHit> { it.score }
                    .thenBy { order[it.entry.id] ?: Int.MAX_VALUE },
            )
            .take(limit)
    }

    fun bestHit(
        query: String,
        entries: List<SettingsEntry> = SettingsCatalog.entries,
    ): SettingsHit? = search(query, entries, limit = 1).firstOrNull()

    fun hasMatch(query: String, entries: List<SettingsEntry> = SettingsCatalog.entries): Boolean =
        bestHit(query, entries) != null

    /**
     * Up to [count] alternate spellings to offer when nothing matched: the
     * query token with its first letter dropped, or the longest token's prefix
     * trimmed by one character. Deliberately cheap — settings search should
     * never spin a fuzzy index.
     */
    fun suggestions(
        query: String,
        entries: List<SettingsEntry> = SettingsCatalog.entries,
        count: Int = 3,
    ): List<String> {
        val tokens = SettingsSearchKeywords.queryTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val candidates = buildList {
            tokens.sortedByDescending { it.length }.forEach { token ->
                if (token.length > 3) add(token.dropLast(1))
                if (token.length > 4) add(token.take(token.length - 2))
            }
        }
        return candidates
            .distinct()
            .filter { hasMatch(it, entries) }
            .take(count)
    }

    /** Score one entry against every token, or null when a token misses. */
    private fun score(entry: SettingsEntry, tokens: List<String>): Pair<Float, String>? {
        val titleWords = SettingsSearchKeywords.wordsOf(entry.title)
        val keywordWords = entry.keywords.flatMap { SettingsSearchKeywords.wordsOf(it) }
        val subtitleWords = SettingsSearchKeywords.wordsOf(entry.subtitle)

        var total = 0f
        var strongest = ""
        var strongestScore = 0f

        for (token in tokens) {
            var best = 0f
            for (candidate in SettingsSearchKeywords.expand(token)) {
                best = maxOf(
                    best,
                    fieldScore(candidate, titleWords, TITLE_WEIGHT),
                    fieldScore(candidate, keywordWords, KEYWORD_WEIGHT),
                    fieldScore(candidate, subtitleWords, SUBTITLE_WEIGHT),
                )
                if (best >= strongestScore) {
                    strongestScore = best
                    strongest = candidate
                }
                // A title hit already satisfies the token; no need to look wider.
                if (best >= TITLE_WEIGHT) break
            }
            if (best == 0f) return null
            total += best
        }
        return total to strongest
    }

    private fun fieldScore(token: String, words: List<String>, weight: Float): Float {
        var best = 0f
        for (word in words) {
            val raw = SettingsSearchKeywords.wordScore(token, word)
            if (raw > best) best = raw
            if (best >= 3f) break
        }
        return best * weight
    }

    /** Exposed for tests: the floor below which prefix matching is disabled. */
    fun minPrefixLength(): Int = MIN_PREFIX_LEN
}
