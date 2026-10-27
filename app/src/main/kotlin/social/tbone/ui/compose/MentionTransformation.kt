package social.tbone.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.ui.theme.BonyColors

private val NPUB_TOKEN = Regex("""npub1[a-z0-9]{58,}""")

/**
 * Builds the editor's short `@name` rendering of an underlying `@npub1…`
 * token. The visible text can be shorter than the editable text, so the
 * transformation MUST provide a real [OffsetMapping]. Using
 * `OffsetMapping.Identity` here let Compose request transformed offsets beyond
 * the visible string and crash the Write tab while editing a mention.
 */
internal fun buildMentionTransformedText(
    text: String,
    profiles: Map<String, ProfileContent>,
    accentColor: Color = BonyColors.Accent,
): TransformedText {
    val replacements = NPUB_TOKEN.findAll(text).mapNotNull { match ->
        // Only valid bech32 npubs are shortened; an npub-like typo stays editable.
        val hex = Nip19.npubToHex(match.value) ?: return@mapNotNull null
        val label = profiles[hex]?.bestName?.takeIf { it.isNotBlank() }
            ?: match.value.take(10)
        MentionReplacement(match.range.first, match.range.last + 1, label)
    }.toList()
    if (replacements.isEmpty()) {
        return TransformedText(AnnotatedString(text), OffsetMapping.Identity)
    }

    val spans = ArrayList<MentionSpan>(replacements.size)
    val annotated = buildAnnotatedString {
        var cursor = 0
        replacements.forEach { replacement ->
            append(text.substring(cursor, replacement.originalStart))
            val transformedStart = length
            append(replacement.label)
            val transformedEnd = length
            if (transformedEnd > transformedStart) {
                addStyle(SpanStyle(color = accentColor), transformedStart, transformedEnd)
            }
            spans += MentionSpan(
                originalStart = replacement.originalStart,
                originalEnd = replacement.originalEnd,
                transformedStart = transformedStart,
                transformedEnd = transformedEnd,
            )
            cursor = replacement.originalEnd
        }
        append(text.substring(cursor))
    }
    return TransformedText(
        text = annotated,
        offsetMapping = MentionOffsetMapping(
            spans = spans,
            originalLength = text.length,
            transformedLength = annotated.length,
        ),
    )
}

private data class MentionReplacement(
    val originalStart: Int,
    val originalEnd: Int,
    val label: String,
)

internal data class MentionSpan(
    val originalStart: Int,
    val originalEnd: Int,
    val transformedStart: Int,
    val transformedEnd: Int,
)

/**
 * Monotonic, bounds-safe mapping for non-overlapping replacements. Offsets
 * inside a replaced npub are clamped to the replacement so Compose can place
 * the cursor/selection without indexing outside the transformed string.
 */
internal class MentionOffsetMapping(
    private val spans: List<MentionSpan>,
    private val originalLength: Int,
    private val transformedLength: Int,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val originalOffset = offset.coerceIn(0, originalLength)
        var delta = 0
        spans.forEach { span ->
            when {
                originalOffset < span.originalStart ->
                    return (originalOffset + delta).coerceIn(0, transformedLength)

                originalOffset < span.originalEnd -> {
                    val replacementLength = span.transformedEnd - span.transformedStart
                    val inside = (originalOffset - span.originalStart).coerceAtMost(replacementLength)
                    return (span.transformedStart + inside).coerceIn(0, transformedLength)
                }

                else -> delta = span.transformedEnd - span.originalEnd
            }
        }
        return (originalOffset + delta).coerceIn(0, transformedLength)
    }

    override fun transformedToOriginal(offset: Int): Int {
        val transformedOffset = offset.coerceIn(0, transformedLength)
        var delta = 0
        spans.forEach { span ->
            when {
                transformedOffset < span.transformedStart ->
                    return (transformedOffset - delta).coerceIn(0, originalLength)

                transformedOffset < span.transformedEnd -> {
                    val originalSpanLength = span.originalEnd - span.originalStart
                    val inside = (transformedOffset - span.transformedStart).coerceAtMost(originalSpanLength)
                    return (span.originalStart + inside).coerceIn(0, originalLength)
                }

                else -> delta = span.transformedEnd - span.originalEnd
            }
        }
        return (transformedOffset - delta).coerceIn(0, originalLength)
    }
}
