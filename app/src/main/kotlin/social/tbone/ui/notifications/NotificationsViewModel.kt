package social.tbone.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.notifications.FlatNotifItem
import social.tbone.notifications.NotifFilter
import social.tbone.notifications.NotifGroup
import social.tbone.notifications.NotifSummary
import social.tbone.notifications.NotifType
import social.tbone.notifications.NotificationsRepository
import social.tbone.nostr.Event
import social.tbone.nostr.ProfileContent
import social.tbone.profile.ProfileRepository
import social.tbone.settings.AppSettings
import javax.inject.Inject

/**
 * UI state for the notifications tab. All notification data lives in the
 * app-wide [NotificationsRepository]; this ViewModel applies the persisted
 * type filters (Wisp-style) and exposes what the screen needs.
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationsRepository,
    private val appSettings: AppSettings,
    profileRepository: ProfileRepository,
) : ViewModel() {

    /**
     * Enabled notification-type filters. Empty = nothing shown (Wisp semantics).
     * Defaults to every filter enabled until the user changes it.
     */
    private val _enabledFilters = MutableStateFlow<Set<NotifFilter>>(NotifFilter.entries.toSet())
    val enabledFilters: StateFlow<Set<NotifFilter>> = _enabledFilters.asStateFlow()

    /** The visible list — flat rows filtered by [enabledFilters] and blocks. */
    val items: StateFlow<List<FlatNotifItem>> = combine(
        repository.flat, _enabledFilters, appSettings.blockedPubkeys,
    ) { items, enabled, blocked ->
        if (enabled.isEmpty()) items
        else items.filter { item ->
            (item.type.toFilter()?.let { it in enabled } ?: true) &&
                item.actorPubkey !in blocked
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Grouped state (drives filters/summary; kept for parity with Wisp). */
    val groups: StateFlow<List<NotifGroup>> = repository.groups

    /**
     * 24h summary counts derived from the BLOCKED-FILTERED list, so a blocked
     * user's activity neither shows as rows nor inflates the summary bar.
     */
    val summary: StateFlow<NotifSummary> = items.map { list ->
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        list.filter { it.timestamp >= cutoff }
            .fold(NotifSummary()) { acc, item ->
                when (item.type) {
                    NotifType.REACTION -> acc.copy(reactionCount = acc.reactionCount + 1)
                    NotifType.REPOST -> acc.copy(repostCount = acc.repostCount + 1)
                    NotifType.REPLY -> acc.copy(replyCount = acc.replyCount + 1)
                    NotifType.QUOTE -> acc.copy(quoteCount = acc.quoteCount + 1)
                    NotifType.MENTION -> acc.copy(mentionCount = acc.mentionCount + 1)
                    NotifType.VOTE -> acc.copy(voteCount = acc.voteCount + 1)
                    NotifType.CALENDAR -> acc.copy(calendarCount = acc.calendarCount + 1)
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotifSummary())

    val hasUnread: StateFlow<Boolean> = repository.hasUnread

    val isLoading: StateFlow<Boolean> = repository.isLoading

    val hasMore: StateFlow<Boolean> = repository.hasMore

    val loadingMore: StateFlow<Boolean> = repository.loadingMore

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val referencedNotes: StateFlow<Map<String, Event>> = repository.referencedNotes

    init {
        // Persisted filter set → enabled filters (null = never set = all on).
        viewModelScope.launch {
            appSettings.notificationEnabledTypes
                .map { names ->
                    names.mapNotNull { NotifFilter.fromName(it) }.toSet()
                }
                .collect { _enabledFilters.value = it }
        }
    }

    // ── Filtering (Wisp semantics) ────────────────────────────────────────────

    fun toggleType(filter: NotifFilter) {
        val current = _enabledFilters.value
        val next = if (filter in current) current - filter else current + filter
        persist(next)
    }

    fun enableAll() = persist(NotifFilter.entries.toSet())

    fun disableAll() = persist(emptySet())

    /** Tap a summary stat to isolate; tap again to restore all. */
    fun isolateType(filter: NotifFilter) {
        if (_enabledFilters.value == setOf(filter)) enableAll() else persist(setOf(filter))
    }

    private fun persist(enabled: Set<NotifFilter>) {
        _enabledFilters.value = enabled
        viewModelScope.launch {
            appSettings.setNotificationEnabledTypes(enabled.map { it.name }.toSet())
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun refresh() {
        repository.refresh()
        viewModelScope.launch {
            _isRefreshing.update { true }
            delay(3_000)
            _isRefreshing.update { false }
        }
    }

    /** Loads an older page of notifications from the relays. */
    fun loadMore() = repository.loadMore()

    fun markRead() = repository.markRead()

    private fun NotifType.toFilter(): NotifFilter? = when (this) {
        NotifType.REPLY -> NotifFilter.REPLIES
        NotifType.REACTION -> NotifFilter.REACTIONS
        NotifType.REPOST -> NotifFilter.REPOSTS
        NotifType.QUOTE -> NotifFilter.MENTIONS
        NotifType.MENTION -> NotifFilter.MENTIONS
        NotifType.VOTE -> NotifFilter.VOTES
        NotifType.CALENDAR -> NotifFilter.CALENDAR
    }
}
