package social.tbone.ui.compose

/**
 * Turns the remembered "@Name" mention labels back into `nostr:npub1…` URIs so
 * the published note renders a clickable @Name and other clients can resolve
 * the mention.
 *
 * ### Why this is not just `content.replace("@Name", "nostr:npub1…")`
 * A handle is followed by whatever the user typed next, and in normal writing
 * that is punctuation: `@T-Bone's client`, `@T-Bone, @alice.`, `(@T-Bone)`,
 * `@T-Bone!`. A plain substring replace already matched inside those, which is
 * why the earlier code appeared to work for some punctuation — but it also
 * matched *inside longer handles* (`@T-Bone` inside `@T-Bonez`) and it could
 * never match a handle written with anything unusual. The rules below make the
 * boundary explicit instead of accidental:
 *
 *  - the match must START at `@` and be preceded by start-of-text, whitespace,
 *    or one of the opening marks `( [ { < " ' \`` — so `email@T-Bone` and a
 *    `https://x/@T-Bone` path never resolve,
 *  - the match must END at end-of-text, whitespace, or ANY punctuation /
 *    symbol / emoji — the handle itself may still contain `.`, `-`, `_`
 *    because those are legal in handles, so a trailing run of them is trimmed
 *    back and left in the text: `@alice.` resolves `alice` and keeps the `.`,
 *  - longest label first, so a label that prefixes another cannot win,
 *  - every occurrence is resolved, not just the first.
 *
 * The replacement is length-changing, so callers must not rely on offsets
 * after calling it.
 */
internal fun resolveMentions(content: String, mentions: Map<String, String>): String {
    if (mentions.isEmpty() || content.isEmpty()) return content

    var out = content
    mentions.entries
        .filter { it.key.isNotBlank() }
        .sortedByDescending { it.key.length }
        .forEach { (name, hex) ->
            val npub = runCatching { social.tbone.nostr.Nip19.hexToNpub(hex) }.getOrNull()
                ?: return@forEach
            out = resolveOneMention(out, name, "nostr:$npub")
        }
    return out
}

/**
 * Replaces every standalone `@[label]` in [text] with [replacement].
 *
 * Kept separate from [resolveMentions] so the boundary rules can be unit tested
 * without building an npub.
 */
internal fun resolveOneMention(text: String, label: String, replacement: String): String {
    if (label.isEmpty()) return text
    val needle = "@$label"
    val sb = StringBuilder(text.length + 16)
    var cursor = 0
    while (true) {
        val start = text.indexOf(needle, cursor)
        if (start < 0) break
        val end = start + needle.length
        val beforeOk = start == 0 || isMentionOpeningBoundary(text[start - 1])
        val afterOk = isMentionEndBoundary(text, end)
        if (beforeOk && afterOk) {
            sb.append(text, cursor, start).append(replacement)
            cursor = end
        } else {
            // Not a real boundary — keep the character and keep scanning so a
            // later legitimate occurrence in the same text still resolves.
            sb.append(text, cursor, start + 1)
            cursor = start + 1
        }
    }
    if (cursor == 0) return text
    sb.append(text, cursor, text.length)
    return sb.toString()
}

/** Start-of-text, whitespace, or an opening bracket/quote may precede a handle. */
private fun isMentionOpeningBoundary(c: Char): Boolean =
    c.isWhitespace() || c in "([{<\"'`\u201C\u2018\u00AB\u2039\u3008\u300A\u300C\uFF08"

/**
 * End-of-text, whitespace, or any punctuation/symbol/emoji may follow a handle.
 * Deliberately "anything that is not a letter or digit": enumerating
 * punctuation instead would miss whatever the user actually types.
 *
 * `.` `-` `_` are the awkward case because they are legal *inside* a handle, so
 * `@T-Bone.` is genuinely ambiguous. It is settled by looking at what follows
 * them: a run of them that ends the word (`@T-Bone. `, `@T-Bone-`) is sentence
 * punctuation, while one that continues into a letter or digit (`@T-Bone.xyz`)
 * means the user is still typing a longer handle — so that is not our mention.
 */
private fun isMentionEndBoundary(text: String, end: Int): Boolean {
    if (end >= text.length) return true
    val c = text[end]
    if (c == '.' || c == '-' || c == '_') {
        var j = end
        while (j < text.length && (text[j] == '.' || text[j] == '-' || text[j] == '_')) j++
        return j >= text.length || !text[j].isLetterOrDigit()
    }
    return !c.isLetterOrDigit()
}

/**
 * The trailing characters of a handle that may also be sentence punctuation.
 * Used by the composer to trim `@alice.` → handle `alice`; a name that really
 * ends in `.` or `-` is unusual enough to lose to readability here.
 */
internal fun trimHandleTail(label: String): String = label.trimEnd('.', '-', '_')
