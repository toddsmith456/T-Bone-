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
import kotlinx.coroutines.flow.flow
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
import social.tbone.notifications.summarizeNotifications
import social.tbone.nostr.Event
import social.tbone.nostr.ProfileContent
import social.tbone.profile.ProfileRepository
import social.tbone.settings.AppSettings
import social.tbone.settings.ContentFilter
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
    private val muteRepository: social.tbone.lists.MuteListRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {

    /**
     * Enabled notification-type filters. Empty = nothing shown (Wisp semantics).
     * Defaults to every filter enabled until the user changes it.
     */
    private val _enabledFilters = MutableStateFlow<Set<NotifFilter>>(NotifFilter.entries.toSet())
    val enabledFilters: StateFlow<Set<NotifFilter>> = _enabledFilters.asStateFlow()

    private data class NotificationFilterConfig(
        val enabled: Set<NotifFilter>,
        val blocked: Set<String>,
        val hideNsfw: Boolean,
        val hideWords: Set<String>,
    )

    private val filterConfig = combine(
        _enabledFilters,
        muteRepository.effectiveMuted,
        appSettings.hideNsfw,
        appSettings.hideWords,
    ) { enabled, blocked, hideNsfw, hideWords ->
        NotificationFilterConfig(enabled, blocked, hideNsfw, hideWords)
    }

    private fun notificationVisible(
        item: FlatNotifItem,
        config: NotificationFilterConfig,
        referencedEvents: Map<String, Event>,
    ): Boolean {
        if (item.type.toFilter()?.let { it in config.enabled } != true) return false
        if (item.actorPubkey in config.blocked) return false
        val note = item.note ?: return true
        return !ContentFilter.shouldHide(
            note,
            config.blocked,
            config.hideNsfw,
            config.hideWords,
            referencedEvents,
        )
    }

    /** Visible rows obey type filters, blocks, NSFW, and hidden-word rules. */
    val items: StateFlow<List<FlatNotifItem>> = combine(
        repository.flat, filterConfig, repository.referencedNotes,
    ) { rows, config, referencedEvents ->
        rows.filter { notificationVisible(it, config, referencedEvents) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Grouped state (drives filters/summary; kept for parity with Wisp). */
    val groups: StateFlow<List<NotifGroup>> = repository.groups

    private val recentVisibleItems = combine(
        repository.recentItems, filterConfig, repository.referencedNotes,
    ) { recent, config, referencedEvents ->
        recent.filter { notificationVisible(it, config, referencedEvents) }
    }

    /** Refresh the rolling 24h window even while the app is otherwise quiet. */
    private val epochSecondsTicker = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(60_000L)
        }
    }

    /**
     * Accurate 24h totals use the uncapped recent cache, not the 500-row UI
     * window. Notification timestamps are Nostr epoch seconds, not millis.
     */
    val summary: StateFlow<NotifSummary> = combine(
        recentVisibleItems,
        items,
        epochSecondsTicker,
    ) { recent, visibleRows, nowEpochSeconds ->
        summarizeNotifications(
            recent + visibleRows.filter { it.type == NotifType.CALENDAR },
            nowEpochSeconds,
        )
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

    /** Referenced notes that never arrived, so cards can stop saying "loading…". */
    val unresolvedReferencedIds: StateFlow<Set<String>> = repository.unresolvedReferencedIds

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
