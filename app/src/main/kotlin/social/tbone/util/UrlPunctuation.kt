package social.tbone.util

/** Strip punctuation commonly surrounding prose URLs, retaining balanced brackets. */
internal fun trimUrlPunctuation(url: String): String {
    var end = url.length
    while (end > 0) {
        val c = url[end - 1]
        val removable = when (c) {
            '.', ',', '!', ';', ':', '?', '\"', '\'' -> true
            ')' -> url.substring(0, end).count { it == ')' } > url.substring(0, end).count { it == '(' }
            ']' -> url.substring(0, end).count { it == ']' } > url.substring(0, end).count { it == '[' }
            '}' -> url.substring(0, end).count { it == '}' } > url.substring(0, end).count { it == '{' }
            else -> false
        }
        if (!removable) break
        end--
    }
    return url.substring(0, end)
}
