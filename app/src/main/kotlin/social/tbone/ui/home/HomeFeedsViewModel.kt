package social.tbone.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.tbone.home.FeedSlot
import social.tbone.home.FeedSource
import social.tbone.home.HomeLayout
import social.tbone.home.HomeLayoutRepository
import javax.inject.Inject

/**
 * Backs the home-feed editor.
 *
 * Deliberately thin: all the rules — slot 1 is pinned to the follow feed, a
 * layout always has at least one visible slot, changing a source clears its
 * cached contents — live in [HomeLayoutRepository] and the slot engine, not here.
 * This class only translates taps into repository calls.
 */
@HiltViewModel
class HomeFeedsViewModel @Inject constructor(
    private val layouts: HomeLayoutRepository,
) : ViewModel() {

    val layout: StateFlow<HomeLayout> = layouts.layout

    /** One-shot confirmation line; the screen shows it and clears it. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Non-blocking notice for an action that was silently ignored.
     *
     * Removing a pinned feed, or hiding the last visible one, is a no-op inside
     * the repository — it has to be, because the invariant is enforced there and
     * not in the button's enabled state. Telling the user why is this class's job.
     */
    private val _notice = MutableStateFlow<Nudge?>(null)
    val notice: StateFlow<Nudge?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    private fun nudge(action: Nudge.Action, feedName: String, target: Nudge.Target) {
        _notice.value = Nudge(action = action, feedName = feedName, target = target)
    }

    /** The reason a feed name is being shown back to the user. */
    enum class Target { FOLLOWS, LAST_VISIBLE, MAX_SLOTS }

    /**
     * What could not happen, in the three shapes it can happen in.
     *
     * Kept as a type rather than a formatted string so the sheet can put the
     * explanation next to the specific control that failed.
     */
    data class Nudge(val action: Action, val feedName: String, val target: Target) {
        enum class Action { REMOVE, MOVE, HIDE, ADD }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Moves a slot one position up. Slot 1 cannot move: it is the follow feed. */
    fun moveUp(slot: FeedSlot) {
        val current = layout.value
        val index = current.indexOf(slot.id)
        if (index < 0) return
        if (index == 0 || current.slots[index].source is FeedSource.Follows) {
            nudge(Nudge.Action.MOVE, slot.source.label, Target.FOLLOWS)
            return
        }
        if (index == 1) return // already directly under the pinned follows feed
        viewModelScope.launch {
            val ids = current.slots.map { it.id }.toMutableList()
            ids.removeAt(index)
            ids.add(index - 1, slot.id)
            layouts.reorder(ids)
        }
    }

    fun moveDown(slot: FeedSlot) {
        val current = layout.value
        val index = current.indexOf(slot.id)
        if (index < 0) return
        if (current.slots[index].source is FeedSource.Follows) {
            nudge(Nudge.Action.MOVE, slot.source.label, Target.FOLLOWS)
            return
        }
        if (index >= current.slots.size - 1) return
        viewModelScope.launch {
            val ids = current.slots.map { it.id }.toMutableList()
            ids.removeAt(index)
            ids.add(index + 1, slot.id)
            layouts.reorder(ids)
        }
    }

    fun setVisible(slot: FeedSlot, visible: Boolean) {
        if (slot.source is FeedSource.Follows && !visible) {
            // The follow feed is the landing tab (decision 3A); hiding it would
            // leave the app with no home screen to open on.
            nudge(Nudge.Action.HIDE, slot.source.label, Target.FOLLOWS)
            return
        }
        val visibleCount = layout.value.visibleSlots.size
        if (!visible && visibleCount <= 1) {
            nudge(Nudge.Action.HIDE, slot.source.label, Target.LAST_VISIBLE)
            return
        }
        viewModelScope.launch { layouts.setVisible(slot.id, visible) }
    }

    fun remove(slot: FeedSlot) {
        if (slot.source is FeedSource.Follows) {
            nudge(Nudge.Action.REMOVE, slot.source.label, Target.FOLLOWS)
            return
        }
        viewModelScope.launch { layouts.remove(slot.id) }
    }

    /** Replaces what a slot reads, keeping its position and identity. */
    fun replaceSource(slot: FeedSlot, source: FeedSource) {
        viewModelScope.launch { layouts.upsert(slot.copy(source = source)) }
    }

    fun addSource(source: FeedSource) {
        viewModelScope.launch {
            val added = layouts.add(source)
            if (added == null) {
                nudge(Nudge.Action.ADD, source.label, Target.MAX_SLOTS)
            }
        }
    }

    fun reset() {
        viewModelScope.launch {
            layouts.resetToDefaults()
            _message.value = "Feeds reset to the defaults."
        }
    }

    fun setClockHours(slot: FeedSlot, hours: Int) {
        replaceSource(slot, FeedSource.Clock(hours = hours.coerceIn(1, 24)))
    }

    fun addHashtag(tag: String) {
        val clean = tag.trim().removePrefix("#").lowercase()
        if (clean.isBlank()) {
            _message.value = "Enter a hashtag."
            return
        }
        addSource(FeedSource.Hashtag(tags = listOf(clean)))
    }

    fun addRelay(url: String) {
        val clean = url.trim().let { if (it.startsWith("ws://") || it.startsWith("wss://")) it else "wss://$it" }
        if (!social.tbone.nostr.relay.RelayPool.isValidRelayUrl(clean)) {
            _message.value = "That does not look like a relay address."
            return
        }
        addSource(FeedSource.Relay(urls = listOf(clean)))
    }

    /**
     * Creates a people feed from pasted keys.
     *
     * Accepts npub or hex, one per line or separated by commas/spaces — the same
     * tolerance the offline list import has.
     */
    fun addPeople(name: String, keys: String) {
        val parsed = keys.split('\n', ',', ' ', '\t', ';')
            .mapNotNull { raw ->
                val trimmed = raw.trim()
                when {
                    trimmed.isBlank() -> null
                    trimmed.length == 64 && trimmed.all { it in "0123456789abcdefABCDEF" } ->
                        trimmed.lowercase()
                    else -> social.tbone.nostr.Nip19.npubToHex(trimmed)?.lowercase()
                }
            }
            .distinct()
        if (parsed.isEmpty()) {
            _message.value = "No valid npub or hex keys found."
            return
        }
        addSource(
            FeedSource.People(
                name = name.trim().ifBlank { "people" },
                pubkeys = parsed,
            ),
        )
    }
}
