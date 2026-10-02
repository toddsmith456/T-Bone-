package social.tbone.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import social.tbone.nostr.Event
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
private data class SegmentedBody(
    val text: androidx.compose.ui.text.AnnotatedString,
    val actions: List<SegAction>,
    val segments: List<BodySeg>,
)

/**
 * A body is rendered as a sequence of blocks rather than one flat text run:
 * runs of ordinary text keep flowing together (no gaps around links), while a
 * note reference gets a block of its own so it can be drawn as an actual note
 * card instead of a bare `nostr:nevent1…` link.
 */
private sealed interface BodyBlock {
    data class Text(val segs: List<BodySeg>) : BodyBlock
    data class NoteLink(val eventId: String, val raw: String) : BodyBlock
}

/**
 * Splits the segments into blocks. A note reference is hoisted out of the
 * text flow; everything else stays in a text run.
 */
private fun toBlocks(segs: List<BodySeg>): List<BodyBlock> {
    val blocks = mutableListOf<BodyBlock>()
    val run = mutableListOf<BodySeg>()
    fun flush() {
        if (run.isNotEmpty()) {
            blocks.add(BodyBlock.Text(run.toList()))
            run.clear()
        }
    }
    segs.forEach { seg ->
        if (seg is BodySeg.NoteRef) {
            flush()
            blocks.add(BodyBlock.NoteLink(seg.eventId, seg.label))
        } else {
            run.add(seg)
        }
    }
    flush()
    return blocks
}

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
    /**
     * Notes already resolved by the caller (quote targets, reposts, NIP-27
     * references). When a `nostr:note1…/nevent1…` in the body points at one of
     * these it is rendered as an embedded note card, not as a link.
     */
    quotedEvents: Map<String, Event> = emptyMap(),
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
        val segments = segment(bleeped, profiles)
        buildSegmentedAnnotated(
            segs = segments,
            profiles = profiles,
            onThreadClick = onThreadClick,
            onProfileClick = onProfileClick,
            onHashtagClick = onHashtagClick,
            onOpenUrl = { openUrl(context, it) },
        ).copy(segments = segments)
    }

    val blocks = remember(segmented) { toBlocks(segmented.segments) }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is BodyBlock.Text -> {
                    val run = remember(block, profiles) {
                        buildSegmentedAnnotated(
                            segs = block.segs,
                            profiles = profiles,
                            onThreadClick = onThreadClick,
                            onProfileClick = onProfileClick,
                            onHashtagClick = onHashtagClick,
                            onOpenUrl = { openUrl(context, it) },
                        )
                    }
                    if (run.text.text.isEmpty()) {
                        // Nothing linkable in this run — plain text only.
                        val plain = block.segs.filterIsInstance<BodySeg.Plain>()
                            .joinToString("") { it.text }
                        if (plain.isNotEmpty()) {
                            Text(
                                text = plain,
                                style = style,
                                maxLines = effectiveMax,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        ClickableText(
                            text = run.text,
                            style = style,
                            maxLines = effectiveMax,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { result: TextLayoutResult ->
                                if (result.hasVisualOverflow || result.lineCount > effectiveMax) {
                                    truncated = true
                                }
                            },
                            onClick = { offset ->
                                val action = run.actions.firstOrNull { offset in it.start until it.end }
                                if (action?.action != null) action.action()
                                else onOpenNote?.invoke()
                            },
                        )
                    }
                }

                is BodyBlock.NoteLink -> {
                    val referenced = quotedEvents[block.eventId]
                    if (referenced != null) {
                        // Resolved: show the referenced note itself.
                        Spacer(Modifier.height(6.dp))
                        QuotedNoteCard(
                            event = referenced,
                            profile = profiles[referenced.pubkey],
                            profiles = profiles,
                            onThreadClick = onThreadClick,
                            onProfileClick = onProfileClick,
                            onHashtagClick = onHashtagClick,
                            fullContent = false,
                            quotedEvents = quotedEvents,
                            nestingDepth = 1,
                        )
                        Spacer(Modifier.height(6.dp))
                    } else {
                        // Not resolved yet — a compact chip that opens the note,
                        // never a raw `nostr:nevent1…` URL.
                        Text(
                            text = "↗ open note",
                            style = BonyType.tag.copy(color = BonyColors.Accent),
                            modifier = Modifier
                                .clickable { onThreadClick?.invoke(block.eventId) }
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            }
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
    segs: List<BodySeg>,
    profiles: Map<String, ProfileContent>,
    onThreadClick: ((String) -> Unit)?,
    onProfileClick: ((String) -> Unit)?,
    onHashtagClick: ((String) -> Unit)?,
    onOpenUrl: (String) -> Unit,
): SegmentedBody {
    val actions = mutableListOf<SegAction>()
    val builder = buildAnnotatedString {
        segs.forEach { seg ->
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
    return SegmentedBody(builder, actions, segs)
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
                        BodySeg.NoteRef(eventId, "↗ open note")
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
