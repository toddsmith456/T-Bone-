package social.tbone.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.pubkeys
import social.tbone.nostr.quotedEventId

/**
 * Option 4: Per-card narrow state observer around [NoteCard].
 *
 * Instead of collecting global `profiles`, `quotedEvents`, `reactions`,
 * `replies`, `repliedByMe`, and poll vote maps at the top of `FeedScreen`,
 * `ProfileScreen`, or `ThreadScreen` (which forced every visible card to
 * recompose whenever any single profile, reaction, reply, quote, or poll vote
 * changed anywhere in the app), this wrapper subscribes strictly to the keys
 * referenced by THIS card via `map { ... }.distinctUntilChanged()`.
 *
 * Synchronous `initialValue` snapshots ensure zero frame-0 flicker while
 * scrolling new cards into view.
 */
@Composable
fun ObservedNoteCard(
    event: Event,
    profilesFlow: StateFlow<Map<String, ProfileContent>>,
    quotedEventsFlow: StateFlow<Map<String, Event>>,
    reactionsFlow: StateFlow<Map<String, Set<String>>>,
    repliesFlow: StateFlow<Map<String, Int>>,
    repliedByMeFlow: StateFlow<Set<String>>,
    pollVoteCountsFlow: StateFlow<Map<String, Map<String, Int>>>,
    pollTotalVotesFlow: StateFlow<Map<String, Int>>,
    pollMyVotesFlow: StateFlow<Map<String, List<String>>>,
    activePubkey: String?,
    fallbackAuthorProfile: ProfileContent? = null,
    highlighted: Boolean = false,
    fullContent: Boolean = false,
    showReplyContext: Boolean = true,
    isPinned: Boolean = false,
    onThreadClick: ((eventId: String) -> Unit)? = null,
    onProfileClick: ((pubkey: String) -> Unit)? = null,
    onHashtagClick: ((tag: String) -> Unit)? = null,
    onReply: ((Event) -> Unit)? = null,
    onBoost: ((Event) -> Unit)? = null,
    onQuote: ((Event) -> Unit)? = null,
    onLike: ((Event) -> Unit)? = null,
    onPollVote: ((Event, List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isRepost = event.kind == EventKind.REPOST || event.kind == EventKind.GENERIC_REPOST
    val refId = remember(event.id) { primaryReferencedEventId(event) }
    val directQuoteIds = remember(event.id) { collectCardQuoteIds(event) }

    // 1. Narrowly observe only the quoted/reposted events referenced by this card
    // (plus one level of nested quote, e.g., a repost of a quote note).
    val cardQuotedEvents by remember(event.id, directQuoteIds, quotedEventsFlow) {
        if (directQuoteIds.isEmpty()) {
            flowOf(emptyMap())
        } else {
            quotedEventsFlow
                .map { all -> sliceQuotedEvents(all, directQuoteIds) }
                .distinctUntilChanged()
        }
    }.collectAsStateWithLifecycle(
        initialValue = if (directQuoteIds.isEmpty()) {
            emptyMap()
        } else {
            sliceQuotedEvents(quotedEventsFlow.value, directQuoteIds)
        },
    )
    val quotedEvent = refId?.let { cardQuotedEvents[it] }

    // 2. Narrowly observe only profiles needed by this card (author, p-tags,
    // inline npub/nprofile/naddr mentions, and any resolved quoted/reposted events).
    val relevantPubkeys = remember(event.id, cardQuotedEvents) {
        collectCardRelevantPubkeys(event, cardQuotedEvents.values)
    }
    val cardProfiles by remember(relevantPubkeys, profilesFlow) {
        if (relevantPubkeys.isEmpty()) {
            flowOf(emptyMap())
        } else {
            profilesFlow
                .map { all -> sliceProfiles(all, relevantPubkeys) }
                .distinctUntilChanged()
        }
    }.collectAsStateWithLifecycle(
        initialValue = sliceProfiles(profilesFlow.value, relevantPubkeys),
    )

    // 3. Narrowly observe reactions & replies for this card's engagement target.
    val targetId = if (isRepost) quotedEvent?.id else event.id
    val cardReactors by remember(targetId, reactionsFlow) {
        if (targetId == null) flowOf(null)
        else reactionsFlow.map { it[targetId] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = targetId?.let { reactionsFlow.value[it] })

    val cardReplyCount by remember(targetId, repliesFlow) {
        if (targetId == null) flowOf(0)
        else repliesFlow.map { it[targetId] ?: 0 }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = targetId?.let { repliesFlow.value[it] } ?: 0)

    val cardDidReply by remember(targetId, repliedByMeFlow) {
        if (targetId == null) flowOf(false)
        else repliedByMeFlow.map { targetId in it }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = targetId != null && targetId in repliedByMeFlow.value)

    val cardRepliesMap = remember(targetId, cardReplyCount) {
        if (targetId != null && cardReplyCount > 0) mapOf(targetId to cardReplyCount) else emptyMap()
    }
    val cardRepliedByMeSet = remember(targetId, cardDidReply) {
        if (targetId != null && cardDidReply) setOf(targetId) else emptySet()
    }

    // 4. Observe poll vote state ONLY for cards that actually display a poll.
    val pollIds = remember(event.id, cardQuotedEvents) {
        buildSet {
            if (event.kind == EventKind.POLL) add(event.id)
            for (q in cardQuotedEvents.values) {
                if (q.kind == EventKind.POLL) add(q.id)
            }
        }
    }
    val cardPollCounts by remember(pollIds, pollVoteCountsFlow) {
        if (pollIds.isEmpty()) flowOf(emptyMap())
        else pollVoteCountsFlow.map { all -> sliceMap(all, pollIds) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = if (pollIds.isEmpty()) emptyMap() else sliceMap(pollVoteCountsFlow.value, pollIds),
    )
    val cardPollTotals by remember(pollIds, pollTotalVotesFlow) {
        if (pollIds.isEmpty()) flowOf(emptyMap())
        else pollTotalVotesFlow.map { all -> sliceMap(all, pollIds) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = if (pollIds.isEmpty()) emptyMap() else sliceMap(pollTotalVotesFlow.value, pollIds),
    )
    val cardPollMyVotes by remember(pollIds, pollMyVotesFlow) {
        if (pollIds.isEmpty()) flowOf(emptyMap())
        else pollMyVotesFlow.map { all -> sliceMap(all, pollIds) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = if (pollIds.isEmpty()) emptyMap() else sliceMap(pollMyVotesFlow.value, pollIds),
    )

    NoteCard(
        event = event,
        profile = cardProfiles[event.pubkey] ?: fallbackAuthorProfile,
        profiles = cardProfiles,
        highlighted = highlighted,
        quotedEvent = quotedEvent,
        onThreadClick = onThreadClick,
        onProfileClick = onProfileClick,
        onHashtagClick = onHashtagClick,
        onReply = onReply,
        onBoost = onBoost,
        onQuote = onQuote,
        onLike = onLike,
        reactors = cardReactors,
        activePubkey = activePubkey,
        quotedEvents = cardQuotedEvents,
        quotedEventId = refId,
        replies = cardRepliesMap,
        repliedByMe = cardRepliedByMeSet,
        pollVoteCounts = cardPollCounts,
        pollTotalVotes = cardPollTotals,
        pollMyVotes = cardPollMyVotes,
        pollVoteVersion = 0,
        onPollVote = onPollVote,
        fullContent = fullContent,
        showReplyContext = showReplyContext,
        isPinned = isPinned,
        modifier = modifier,
    )
}

internal fun primaryReferencedEventId(event: Event): String? =
    when (event.kind) {
        EventKind.REPOST, EventKind.GENERIC_REPOST ->
            event.parsedTags.firstOrNull { it.name == "e" }?.value()
        else -> event.parsedTags.quotedEventId ?: extractInlineQuoteId(event.content)
    }

/** All direct quote/repost event IDs referenced by [event]. */
internal fun collectCardQuoteIds(event: Event): Set<String> = buildSet {
    primaryReferencedEventId(event)?.let(::add)
    for (tag in event.parsedTags) {
        if (tag.name == "q") tag.value()?.let(::add)
    }
    addAll(extractInlineQuoteIds(event.content))
}

/**
 * Slices [allQuoted] down to [directIds] plus one level of nested quotes
 * (matching [QuotedNoteCard]'s max nesting depth).
 */
internal fun sliceQuotedEvents(
    allQuoted: Map<String, Event>,
    directIds: Set<String>,
): Map<String, Event> {
    if (directIds.isEmpty() || allQuoted.isEmpty()) return emptyMap()
    var result: LinkedHashMap<String, Event>? = null
    for (id in directIds) {
        val ev = allQuoted[id] ?: continue
        val map = result ?: LinkedHashMap<String, Event>(directIds.size).also { result = it }
        map[id] = ev
        val nested = primaryReferencedEventId(ev)
        if (nested != null && nested !in map) {
            allQuoted[nested]?.let { map[nested] = it }
        }
        for (inlineNested in extractInlineQuoteIds(ev.content)) {
            if (inlineNested !in map) {
                allQuoted[inlineNested]?.let { map[inlineNested] = it }
            }
        }
    }
    return result ?: emptyMap()
}

/** All author and mentioned pubkeys that [event] and its [quotedEvents] can render. */
internal fun collectCardRelevantPubkeys(
    event: Event,
    quotedEvents: Collection<Event>,
): Set<String> = buildSet {
    add(event.pubkey)
    addAll(event.parsedTags.pubkeys)
    addAll(extractInlineMentionPubkeys(event.content))
    for (quoted in quotedEvents) {
        add(quoted.pubkey)
        addAll(quoted.parsedTags.pubkeys)
        addAll(extractInlineMentionPubkeys(quoted.content))
    }
}

internal fun sliceProfiles(
    allProfiles: Map<String, ProfileContent>,
    keys: Set<String>,
): Map<String, ProfileContent> = sliceMap(allProfiles, keys)

private fun <V> sliceMap(all: Map<String, V>, keys: Set<String>): Map<String, V> {
    if (keys.isEmpty() || all.isEmpty()) return emptyMap()
    var result: LinkedHashMap<String, V>? = null
    for (key in keys) {
        val value = all[key] ?: continue
        val map = result ?: LinkedHashMap<String, V>(keys.size).also { result = it }
        map[key] = value
    }
    return result ?: emptyMap()
}
