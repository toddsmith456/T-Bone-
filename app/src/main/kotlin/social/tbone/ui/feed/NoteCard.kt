package social.tbone.ui.feed

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Nip19
import social.tbone.nostr.Nip88
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.identity.Phrase
import social.tbone.nostr.isReply
import social.tbone.nostr.quotedEventId
import social.tbone.nostr.replyEventId
import social.tbone.nostr.replyToPubkeys
import social.tbone.ui.components.UserAvatar
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NoteCard(
    event: Event,
    profile: ProfileContent?,
    profiles: Map<String, ProfileContent> = emptyMap(),
    highlighted: Boolean = false,
    quotedEvent: Event? = null,
    onThreadClick: ((eventId: String) -> Unit)? = null,
    onProfileClick: ((pubkey: String) -> Unit)? = null,
    onHashtagClick: ((tag: String) -> Unit)? = null,
    onReply: ((Event) -> Unit)? = null,
    onBoost: ((Event) -> Unit)? = null,
    onQuote: ((Event) -> Unit)? = null,
    onLike: ((Event) -> Unit)? = null,
    onShare: ((Event) -> Unit)? = null,
    reactors: Set<String>? = null,
    activePubkey: String? = null,
    /** Reply counts per note id (from RepliesRepository). */
    replies: Map<String, Int> = emptyMap(),
    /** Notes the active account replied to (from RepliesRepository). */
    repliedByMe: Set<String> = emptySet(),
    /** NIP-88 poll vote counts: poll id -> (option id -> count). */
    pollVoteCounts: Map<String, Map<String, Int>> = emptyMap(),
    /** NIP-88 poll votes by the active user: poll id -> option ids. */
    pollMyVotes: Map<String, List<String>> = emptyMap(),
    /** Bumped on vote changes — used to recompute poll counts. */
    pollVoteVersion: Int = 0,
    /** Called when the user casts a poll vote. */
    onPollVote: ((Event, List<String>) -> Unit)? = null,
    /** When true the body text is not truncated — used for notes opened in a thread. */
    fullContent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cardBg = when {
        highlighted -> BonyColors.AccentBg
        else -> BonyColors.Bg
    }

    // ── Kind-6 repost ────────────────────────────────────────────────────────
    if (event.kind == EventKind.REPOST) {
        val target = quotedEvent
        Column(
            modifier = modifier
                .background(cardBg)
                .then(
                    if (onThreadClick != null && target != null)
                        Modifier.clickable { onThreadClick(target.id) }
                    else Modifier,
                ),
        ) {
            // Boost header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 4.dp)
                    .then(
                        if (onProfileClick != null)
                            Modifier.clickable { onProfileClick(event.pubkey) }
                        else Modifier,
                    ),
            ) {
                Text(
                    text = "↻",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "reposted by ${profile?.bestName ?: event.pubkey.phraseHandle()}" +
                        " · ${event.createdAt.formatRelative()}",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                    maxLines = 1,
                )
            }

            if (target != null) {
                QuotedNoteCard(
                    event = target,
                    profile = profiles[target.pubkey],
                    profiles = profiles,
                    onThreadClick = onThreadClick,
                    onProfileClick = onProfileClick,
                    onHashtagClick = onHashtagClick,
                    fullContent = fullContent,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
                NoteEngagementRow(
                    event = target,
                    onReply = onReply,
                    onBoost = onBoost,
                    onQuote = onQuote,
                    onLike = onLike,
                    onShare = onShare,
                    reactors = reactors,
                    activePubkey = activePubkey,
                    replies = replies,
                    repliedByMe = repliedByMe,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
            } else {
                Text(
                    text = "loading reposted note…",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        // Hairline bottom border
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        return
    }

    // ── Kind-1068 poll ───────────────────────────────────────────────────────
    if (event.kind == EventKind.POLL) {
        val parsedPoll = remember(event.id) { parseNoteContent(event.content, profiles) }
        Column(
            modifier = modifier
                .background(cardBg)
                .then(
                    if (onThreadClick != null)
                        Modifier.clickable { onThreadClick(event.id) }
                    else Modifier,
                )
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        ) {
            // Author row
            Row(verticalAlignment = Alignment.Top) {
                UserAvatar(
                    pubkeyHex = event.pubkey,
                    profile = profile,
                    size = 36.dp,
                    modifier = if (onProfileClick != null)
                        Modifier.clickable { onProfileClick(event.pubkey) }
                    else Modifier,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val name = profile?.bestName
                        if (name != null) {
                            Text(name, style = BonyType.bodyDim.copy(color = BonyColors.Text), maxLines = 1)
                            Text(" · ", style = BonyType.metaDim.copy(color = BonyColors.TextMute))
                        }
                        Text(
                            text = remember(event.pubkey) { Phrase.wordsFor(event.pubkey, 3).joinToString("·") },
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = remember(event.createdAt) { event.createdAt.formatRelative() },
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Question text
            if (parsedPoll.text.isNotEmpty()) {
                NoteBodyText(
                    content = parsedPoll.text,
                    style = BonyType.body.copy(color = BonyColors.Text),
                    maxLines = 12,
                    expandable = !fullContent,
                    onThreadClick = onThreadClick,
                    onProfileClick = onProfileClick,
                    onHashtagClick = onHashtagClick,
                    onOpenNote = { onThreadClick?.invoke(event.id) },
                    profiles = profiles,
                )
                Spacer(Modifier.height(8.dp))
            }

            // The poll itself.
            PollCard(
                poll = event,
                voteCounts = pollVoteCounts[event.id] ?: emptyMap(),
                totalVotes = pollVoteCounts[event.id]?.values?.sum() ?: 0,
                userVotes = pollMyVotes[event.id] ?: emptyList(),
                onVote = { ids -> onPollVote?.invoke(event, ids) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            NoteEngagementRow(
                event = event,
                onReply = onReply,
                onBoost = onBoost,
                onQuote = onQuote,
                onLike = onLike,
                onShare = onShare,
                reactors = reactors,
                activePubkey = activePubkey,
                replies = replies,
                repliedByMe = repliedByMe,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        return
    }

    // ── Kind-1 text note ─────────────────────────────────────────────────────
    val hasQuote = event.parsedTags.quotedEventId != null
        || extractInlineQuoteId(event.content) != null
    val parsed = remember(event.id) { parseNoteContent(event.content, profiles) }
    // When a quote is present, drop its raw nostr:nevent1…/note1… URI from the
    // body — the quoted note is rendered as a card below, so the URI must
    // never appear as a fake "nevent" link inside the text.
    val quotedRefId = event.parsedTags.quotedEventId ?: extractInlineQuoteId(event.content)
    val bodyText = remember(event.id, quotedRefId) {
        if (quotedRefId != null) stripQuoteRefs(parsed.text, quotedRefId) else parsed.text
    }

    Column(
        modifier = modifier
            .background(cardBg)
            .then(
                if (highlighted) Modifier.border(width = 2.dp, color = BonyColors.Accent,
                    shape = RectangleShape)
                else Modifier
            )
            .then(
                if (onThreadClick != null)
                    Modifier.clickable { onThreadClick(event.id) }
                else Modifier,
            )
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
    ) {
        // Reply context line
        if (event.parsedTags.isReply) {
            val replyPubkeys = event.parsedTags.replyToPubkeys
            val replyLabel = replyPubkeys.take(1).joinToString { pk ->
                profiles[pk]?.bestName?.take(20) ?: pk.phraseHandle()
            } + if (replyPubkeys.size > 1) " +${replyPubkeys.size - 1}" else ""
            val parentId = event.parsedTags.replyEventId
            Text(
                text = "↳ replying to $replyLabel",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                maxLines = 1,
                modifier = if (onThreadClick != null && parentId != null)
                    Modifier.clickable { onThreadClick(parentId) }
                else Modifier,
            )
            Spacer(Modifier.height(6.dp))
        }

        // Author row
        Row(verticalAlignment = Alignment.Top) {
            UserAvatar(
                pubkeyHex = event.pubkey,
                profile = profile,
                size = 36.dp,
                modifier = if (onProfileClick != null)
                    Modifier.clickable { onProfileClick(event.pubkey) }
                else Modifier,
            )

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Header: name · phrase · time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val name = profile?.bestName
                        if (name != null) {
                            Text(
                                text = name,
                                style = BonyType.bodyDim.copy(color = BonyColors.Text),
                                maxLines = 1,
                            )
                            Text(
                                text = " · ",
                                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                            )
                        }
                        val phrase = remember(event.pubkey) {
                            Phrase.wordsFor(event.pubkey, 3).joinToString("·")
                        }
                        Text(
                            text = phrase,
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = remember(event.createdAt) { event.createdAt.formatRelative() },
                        style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Body text — rich (links, npubs, bleep) with a show-more.
                if (bodyText.isNotEmpty()) {
                    NoteBodyText(
                        content = bodyText,
                        style = BonyType.body.copy(color = BonyColors.Text),
                        maxLines = 12,
                        expandable = !fullContent,
                        onThreadClick = onThreadClick,
                        onProfileClick = onProfileClick,
                        onHashtagClick = onHashtagClick,
                        onOpenNote = { onThreadClick?.invoke(event.id) },
                        profiles = profiles,
                    )
                }

                // Media placeholder(s)
                if (parsed.mediaItems.isNotEmpty()) {
                    Spacer(Modifier.height(if (bodyText.isNotEmpty()) 8.dp else 0.dp))
                    NoteMediaContent(mediaItems = parsed.mediaItems, modifier = Modifier.fillMaxWidth())
                }

                // Quoted note
                if (hasQuote) {
                    Spacer(Modifier.height(8.dp))
                    if (quotedEvent != null) {
                        QuotedNoteCard(
                            event = quotedEvent,
                            profile = profiles[quotedEvent.pubkey],
                            profiles = profiles,
                            onThreadClick = onThreadClick,
                            onProfileClick = onProfileClick,
                            onHashtagClick = onHashtagClick,
                            fullContent = fullContent,
                        )
                    } else {
                        Text(
                            text = "loading quoted note…",
                            style = BonyType.meta.copy(color = BonyColors.TextMute),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                NoteEngagementRow(
                    event = event,
                    onReply = onReply,
                    onBoost = onBoost,
                    onQuote = onQuote,
                    onLike = onLike,
                    onShare = onShare,
                    reactors = reactors,
                    activePubkey = activePubkey,
                    replies = replies,
                    repliedByMe = repliedByMe,
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

// ── Quoted / nested card ─────────────────────────────────────────────────────

@Composable
fun QuotedNoteCard(
    event: Event,
    profile: ProfileContent?,
    profiles: Map<String, ProfileContent> = emptyMap(),
    onThreadClick: ((eventId: String) -> Unit)? = null,
    onProfileClick: ((pubkey: String) -> Unit)? = null,
    onHashtagClick: ((tag: String) -> Unit)? = null,
    /** When true the quoted body is not truncated (thread view). */
    fullContent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(event.id) { parseNoteContent(event.content, profiles) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.SurfaceAlt)
            .then(
                if (onThreadClick != null)
                    Modifier.clickable { onThreadClick(event.id) }
                else Modifier,
            )
            .padding(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (onProfileClick != null)
                Modifier.clickable { onProfileClick(event.pubkey) }
            else Modifier,
        ) {
            UserAvatar(
                pubkeyHex = event.pubkey,
                profile = profile,
                size = 22.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = profile?.bestName ?: event.pubkey.phraseHandle(),
                style = BonyType.metaDim.copy(color = BonyColors.TextDim),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = remember(event.createdAt) { event.createdAt.formatRelative() },
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            )
        }

        if (parsed.text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            NoteBodyText(
                content = parsed.text,
                style = BonyType.bodyDim.copy(color = BonyColors.TextDim),
                maxLines = 6,
                expandable = !fullContent,
                onThreadClick = onThreadClick,
                onProfileClick = onProfileClick,
                onHashtagClick = onHashtagClick,
                onOpenNote = { onThreadClick?.invoke(event.id) },
                profiles = profiles,
            )
        }
    }
}

// ── Engagement row ───────────────────────────────────────────────────────────

@Composable
private fun NoteEngagementRow(
    event: Event,
    onReply: ((Event) -> Unit)?,
    onBoost: ((Event) -> Unit)?,
    onQuote: ((Event) -> Unit)?,
    onLike: ((Event) -> Unit)?,
    onShare: ((Event) -> Unit)?,
    reactors: Set<String>?,
    activePubkey: String?,
    replies: Map<String, Int> = emptyMap(),
    repliedByMe: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    if (onReply == null && onBoost == null && onQuote == null && onLike == null && onShare == null) return

    val hasReacted = activePubkey != null && reactors?.contains(activePubkey) == true
    val likeCount = reactors?.size ?: 0
    val replyCount = replies[event.id] ?: 0
    val didReply = activePubkey != null && event.id in repliedByMe
    val LIKE_RED = Color(0xFFE0245E)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onReply != null) {
            EngagementButton(
                icon = if (didReply) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline,
                contentDescription = "Reply",
                count = replyCount,
                tint = if (didReply) BonyColors.Accent else BonyColors.TextMute,
                onClick = { onReply(event) },
            )
        }
        if (onBoost != null) {
            EngagementButton(
                icon = Icons.Outlined.Repeat,
                contentDescription = "Repost",
                count = 0,
                tint = BonyColors.TextMute,
                onClick = { onBoost(event) },
            )
        }
        if (onQuote != null) {
            EngagementButton(
                icon = Icons.Outlined.FormatQuote,
                contentDescription = "Quote",
                count = 0,
                tint = BonyColors.TextMute,
                onClick = { onQuote(event) },
            )
        }
        if (onLike != null) {
            EngagementButton(
                icon = if (hasReacted) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = "Like",
                count = likeCount,
                tint = if (hasReacted) LIKE_RED else BonyColors.TextMute,
                onClick = { if (!hasReacted) onLike(event) },
            )
        }
        if (onShare != null) {
            EngagementButton(
                icon = Icons.Outlined.Share,
                contentDescription = "Share",
                count = 0,
                tint = BonyColors.TextMute,
                onClick = { onShare(event) },
            )
        }
    }
}

/**
 * A single, larger engagement button: recognizable icon + optional count.
 * Icons are ~20dp with a generous 40dp+ touch target — about twice the old
 * glyph row — so the action bar can't interfere with tapping them.
 */
@Composable
private fun EngagementButton(
    icon: ImageVector,
    contentDescription: String,
    count: Int,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        if (count > 0 || contentDescription == "Reply" || contentDescription == "Like") {
            Spacer(Modifier.width(4.dp))
            Text(
                text = count.toString(),
                style = BonyType.metaDim.copy(color = tint),
            )
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Short 3-word phrase handle fallback for when there's no display name. */
private fun String.phraseHandle(): String {
    val words = Phrase.wordsFor(this, 3)
    return if (words.all { it == "·····" }) {
        val npub = Nip19.hexToNpub(this)
        "${npub.take(9)}…"
    } else {
        words.joinToString("·")
    }
}

private fun Long.formatRelative(): String {
    val now = System.currentTimeMillis() / 1000
    val delta = now - this
    return when {
        delta < 60   -> "now"
        delta < 3600 -> "${delta / 60}m"
        delta < 86400 -> "${delta / 3600}h"
        delta < 604800 -> "${delta / 86400}d"
        else -> Instant.ofEpochSecond(this)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d"))
    }
}
