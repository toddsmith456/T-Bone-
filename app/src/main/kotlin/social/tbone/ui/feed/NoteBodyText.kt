package social.tbone.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.settings.ContentFilter
import social.tbone.settings.LocalContentFilter
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

private val HTTP_REGEX = Regex("""https?://[^\s<>"')\]]+""")
private val NPUB_REGEX = Regex("""nostr:(npub1|nprofile1)[a-z0-9]+""")
private val NOTE_REGEX = Regex("""nostr:(note1|nevent1|naddr1)[a-z0-9]+""")
// A #tag: must not be preceded by a word character so URLs (…/#frag) and
// "c#" don't match, but "#tag" at the start of text or after whitespace does.
private val HASHTAG_REGEX = Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+""")

private sealed interface BodySeg {
    data class Plain(val text: String) : BodySeg
    data class Url(val url: String) : BodySeg
    data class Profile(val hex: String, val label: String) : BodySeg
    data class NoteRef(val eventId: String, val label: String) : BodySeg
    data class Hashtag(val tag: String) : BodySeg
}

private data class SegRange(val start: Int, val end: Int, val kind: Int, val value: String)

/** A clickable region inside the annotated note text. */
private data class SegAction(val start: Int, val end: Int, val action: (() -> Unit)?)

/** The styled note text plus the click regions that go with it. */
private data class SegmentedBody(val text: androidx.compose.ui.text.AnnotatedString, val actions: List<SegAction>)

/**
 * Renders a note's text as ONE flowing text block (no segmented rows, so no
 * gaps ever appear around links/npubs):
 *  - web links are tinted + underlined (tap → browser),
 *  - npubs/nprofiles show as @name (tap → profile),
 *  - note URIs (tap → thread),
 *  - hashtags are accent-colored (tap → that tag's feed),
 *  - bleep words are masked with same-length asterisks,
 *  - a "show more →" button appears exactly when the note is truncated
 *    (driven by the same text-layout measurement that cuts the note off).
 * Tapping plain body text opens the note (forwarded via [onOpenNote]).
 */
@Composable
fun NoteBodyText(
    content: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    expandable: Boolean = false,
    onThreadClick: ((String) -> Unit)? = null,
    onProfileClick: ((String) -> Unit)? = null,
    onHashtagClick: ((String) -> Unit)? = null,
    onOpenNote: (() -> Unit)? = null,
    profiles: Map<String, ProfileContent> = emptyMap(),
) {
    val context = LocalContext.current
    val filter = LocalContentFilter.current
    var expanded by remember(content) { mutableStateOf(false) }
    // True when the layout actually cut the note off — the exact same
    // condition that decides not to show the full note.
    var truncated by remember(content) { mutableStateOf(false) }
    val effectiveMax = if (expandable && !expanded) maxLines else Int.MAX_VALUE

    val bleeped = remember(content, filter.bleepWords) { ContentFilter.bleep(content, filter.bleepWords) }
    val segmented = remember(bleeped, profiles) {
        buildSegmentedAnnotated(
            text = bleeped,
            profiles = profiles,
            onThreadClick = onThreadClick,
            onProfileClick = onProfileClick,
            onHashtagClick = onHashtagClick,
            onOpenUrl = { openUrl(context, it) },
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (segmented.text.text.isEmpty()) {
            Text(text = bleeped, style = style, maxLines = effectiveMax, overflow = TextOverflow.Ellipsis)
        } else {
            ClickableText(
                text = segmented.text,
                style = style,
                maxLines = effectiveMax,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result: TextLayoutResult ->
                    truncated = result.hasVisualOverflow || result.lineCount > effectiveMax
                },
                onClick = { offset ->
                    val action = segmented.actions.firstOrNull { offset in it.start until it.end }
                    if (action?.action != null) action.action()
                    else onOpenNote?.invoke()
                },
            )
        }
        if (expandable && !expanded && truncated) {
            Text(
                text = "show more →",
                style = BonyType.tag.copy(color = BonyColors.Accent),
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(top = 4.dp),
            )
        }
    }
}

/**
 * Splits the note into styled clickable segments and builds a single
 * [androidx.compose.ui.text.AnnotatedString]. The parallel [SegAction] list
 * maps character offsets back to the action to run on tap.
 */
private fun buildSegmentedAnnotated(
    text: String,
    profiles: Map<String, ProfileContent>,
    onThreadClick: ((String) -> Unit)?,
    onProfileClick: ((String) -> Unit)?,
    onHashtagClick: ((String) -> Unit)?,
    onOpenUrl: (String) -> Unit,
): SegmentedBody {
    val actions = mutableListOf<SegAction>()
    val builder = buildAnnotatedString {
        segment(text, profiles).forEach { seg ->
            when (seg) {
                is BodySeg.Plain -> append(seg.text)

                is BodySeg.Url -> {
                    val start = length
                    // Show and open the tracking-stripped URL.
                    append(seg.url)
                    addStyle(SpanStyle(color = BonyColors.Link, textDecoration = TextDecoration.Underline), start, length)
                    actions.add(SegAction(start, length) { onOpenUrl(seg.url) })
                }

                is BodySeg.Profile -> {
                    val start = length
                    append(seg.label)
                    addStyle(SpanStyle(color = BonyColors.Link), start, length)
                    actions.add(SegAction(start, length) { onProfileClick?.invoke(seg.hex) })
                }

                is BodySeg.NoteRef -> {
                    val start = length
                    append(seg.label)
                    addStyle(SpanStyle(color = BonyColors.Link, textDecoration = TextDecoration.Underline), start, length)
                    actions.add(SegAction(start, length) { onThreadClick?.invoke(seg.eventId) })
                }

                is BodySeg.Hashtag -> {
                    val start = length
                    append("#${seg.tag}")
                    addStyle(SpanStyle(color = BonyColors.Accent), start, length)
                    actions.add(SegAction(start, length) { onHashtagClick?.invoke(seg.tag) })
                }
            }
        }
    }
    return SegmentedBody(builder, actions)
}

private fun segment(text: String, profiles: Map<String, ProfileContent>): List<BodySeg> {
    val segs = mutableListOf<SegRange>()
    HTTP_REGEX.findAll(text).forEach { m -> segs.add(SegRange(m.range.first, m.range.last + 1, 1, m.value)) }
    NPUB_REGEX.findAll(text).forEach { m -> segs.add(SegRange(m.range.first, m.range.last + 1, 2, m.value)) }
    NOTE_REGEX.findAll(text).forEach { m -> segs.add(SegRange(m.range.first, m.range.last + 1, 3, m.value)) }
    HASHTAG_REGEX.findAll(text).forEach { m ->
        // Normalize the tag to lowercase, minus any leading '#'.
        segs.add(SegRange(m.range.first, m.range.last + 1, 4, m.value.drop(1).lowercase()))
    }
    segs.sortBy { it.start }

    val out = mutableListOf<BodySeg>()
    var cursor = 0
    fun pushPlain(from: Int, to: Int) {
        if (to > from) out.add(BodySeg.Plain(text.substring(from, to)))
    }
    segs.forEach { seg ->
        if (seg.start < cursor) return@forEach
        pushPlain(cursor, seg.start)
        when (seg.kind) {
            1 -> out.add(BodySeg.Url(social.tbone.util.LinkCleaner.stripTrackingParams(seg.value)))
            2 -> {
                val entity = seg.value.removePrefix("nostr:")
                val hex = if (entity.startsWith("npub1")) Nip19.npubToHex(entity) else Nip19.nprofileToHex(entity)
                out.add(
                    if (hex != null) {
                        BodySeg.Profile(hex, "@${profiles[hex]?.bestName ?: abbreviateNpub(hex)}")
                    } else {
                        BodySeg.Plain(seg.value)
                    },
                )
            }
            3 -> {
                val eventId = Nip19.nostrUriToEventId(seg.value)
                out.add(
                    if (eventId != null) {
                        BodySeg.NoteRef(eventId, seg.value.take(14) + "…")
                    } else {
                        BodySeg.Plain(seg.value)
                    },
                )
            }
            4 -> out.add(BodySeg.Hashtag(seg.value))
        }
        cursor = seg.end
    }
    pushPlain(cursor, text.length)
    return out
}

private fun abbreviateNpub(hex: String): String =
    Nip19.hexToNpub(hex).let { "${it.take(9)}…${it.takeLast(4)}" }

/** Opens a browser for a url. */
fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}
