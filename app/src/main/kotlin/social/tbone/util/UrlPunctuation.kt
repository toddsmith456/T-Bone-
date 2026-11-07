package social.tbone.util

/** Shared token boundaries for visible prose links and inline media. */
internal val HTTP_URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

/** Strip punctuation commonly surrounding prose URLs, retaining balanced brackets.
 * Characters that are genuinely part of a URL can be percent-encoded by authors. */
internal fun trimUrlPunctuation(url: String): String {
    var end = url.length
    var roundBalance = url.count { it == '(' } - url.count { it == ')' }
    var squareBalance = url.count { it == '[' } - url.count { it == ']' }
    var braceBalance = url.count { it == '{' } - url.count { it == '}' }
    while (end > 0) {
        val c = url[end - 1]
        val removable = when (c) {
            '.', ',', '!', ';', ':', '?', '"', '\'' -> true
            ')' -> if (roundBalance < 0) { roundBalance++; true } else false
            ']' -> if (squareBalance < 0) { squareBalance++; true } else false
            '}' -> if (braceBalance < 0) { braceBalance++; true } else false
            else -> false
        }
        if (!removable) break
        end--
    }
    return url.substring(0, end)
}

internal data class HttpUrlToken(val start: Int, val endExclusive: Int, val url: String)

internal fun httpUrlTokens(text: String): List<HttpUrlToken> = HTTP_URL_REGEX.findAll(text).mapNotNull { match ->
    val url = trimUrlPunctuation(match.value)
    if (url.isEmpty()) null else HttpUrlToken(match.range.first, match.range.first + url.length, url)
}.toList()
