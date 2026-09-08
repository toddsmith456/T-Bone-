package social.tbone.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.tbone.account.AccountRepository
import social.tbone.db.EventRepository
import social.tbone.account.FollowEntry
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.reactions.ReactionsRepository
import social.tbone.reactions.PollsRepository
import social.tbone.reactions.RepliesRepository
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.profile.ProfileRepository
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val pool: RelayPool,
    private val profileRepository: ProfileRepository,
    private val accountRepository: AccountRepository,
    private val signerFactory: NostrSignerFactory,
    private val reactionsRepository: ReactionsRepository,
    private val repliesRepository: RepliesRepository,
    private val pollsRepository: PollsRepository,
    private val eventRepository: EventRepository,
    private val appSettings: social.tbone.settings.AppSettings,
) : ViewModel() {

    val pubkey: String = checkNotNull(savedStateHandle["pubkey"])

    val profile: StateFlow<ProfileContent?> = profileRepository.profiles
        .map { it[pubkey] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), profileRepository.getProfile(pubkey))

    private val _notes = MutableStateFlow<List<Event>>(emptyList())
    val notes: StateFlow<List<Event>> = _notes.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val isActiveUserProfile: StateFlow<Boolean> = accountRepository.activeAccount
        .map { it?.pubkey == pubkey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isFollowing: StateFlow<Boolean> = accountRepository.activeAccount
        .map { it?.isFollowing(pubkey) == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isBlocked: StateFlow<Boolean> = appSettings.blockedPubkeys
        .map { set -> pubkey in set }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleBlock() {
        viewModelScope.launch { appSettings.toggleBlockPubkey(pubkey) }
    }

    private val _isFollowLoading = MutableStateFlow(false)
    val isFollowLoading: StateFlow<Boolean> = _isFollowLoading.asStateFlow()

    val reactions: StateFlow<Map<String, Set<String>>> = reactionsRepository.reactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val replies: StateFlow<Map<String, Int>> = repliesRepository.replies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val repliedByMe: StateFlow<Set<String>> = repliesRepository.repliedByMe
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val pollVoteCounts: StateFlow<Map<String, Map<String, Int>>> = pollsRepository.counts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val pollMyVotes: StateFlow<Map<String, List<String>>> = pollsRepository.myVotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val pollVoteVersion: StateFlow<Int> = pollsRepository.voteVersion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val activePubkey: StateFlow<String?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var notesSubId: String? = null
    private var metadataSubId: String? = null

    init {
        notesSubId = pool.subscribe(listOf(
            Filter(authors = listOf(pubkey), kinds = listOf(EventKind.TEXT_NOTE, EventKind.POLL), limit = 50)
        ))
        metadataSubId = pool.subscribe(listOf(
            Filter(authors = listOf(pubkey), kinds = listOf(EventKind.METADATA), limit = 1)
        ))

        viewModelScope.launch(Dispatchers.Default) {
            pool.messages.collect { (_, message) ->
                when (message) {
                    is RelayMessage.EventMessage -> {
                        val event = message.event
                        if (!event.verify()) return@collect
                        when (event.kind) {
                            EventKind.METADATA -> profileRepository.processEvent(event)
                            EventKind.TEXT_NOTE, EventKind.POLL -> if (event.pubkey == pubkey) {
                                // Hide if the viewer blocked them / nsfw / hidden words.
                                if (social.tbone.settings.ContentFilter.shouldHide(
                                        event,
                                        appSettings.blockedPubkeys.value,
                                        appSettings.hideNsfw.value,
                                        appSettings.hideWords.value,
                                    )
                                ) {
                                    return@collect
                                }
                                _notes.update { existing ->
                                    (existing + event)
                                        .distinctBy { it.id }
                                        .sortedByDescending { it.createdAt }
                                }
                                // Persist so a tapped note opens a thread instantly.
                                viewModelScope.launch { eventRepository.save(event, "") }
                                reactionsRepository.subscribeTo(listOf(event.id))
                                repliesRepository.subscribeTo(listOf(event.id))
                                if (event.kind == EventKind.POLL) pollsRepository.subscribeTo(listOf(event.id))
                            }
                        }
                    }
                    is RelayMessage.EndOfStoredEvents -> _isLoading.update { false }
                    else -> Unit
                }
            }
        }

        viewModelScope.launch {
            delay(10_000)
            _isLoading.update { false }
        }
    }

    fun react(event: Event) = reactionsRepository.react(event)

    fun voteOnPoll(poll: Event, optionIds: List<String>) =
        pollsRepository.vote(poll, optionIds)

    /** Reposts (kind-6) a note from the profile feed. */
    fun boost(event: Event) {
        viewModelScope.launch {
            val signer = signerFactory.forActiveAccount() ?: return@launch
            val unsigned = UnsignedEvent(
                pubkey = signer.pubkey,
                kind = EventKind.REPOST,
                content = Json.encodeToString(Event.serializer(), event),
                tags = listOf(
                    buildJsonArray { add("e"); add(event.id); add(""); add("mention") },
                    buildJsonArray { add("p"); add(event.pubkey) },
                ),
            )
            signer.signEvent(unsigned)
                .onSuccess { pool.publish(it) }
                .onFailure { e -> Timber.w(e, "Boost failed") }
        }
    }

    fun follow() = toggleFollow(add = true)
    fun unfollow() = toggleFollow(add = false)

    private fun toggleFollow(add: Boolean) {
        viewModelScope.launch {
            _isFollowLoading.update { true }
            val account = accountRepository.activeAccount.first()
            val signer = signerFactory.forActiveAccount()
            if (account == null || signer == null) {
                _isFollowLoading.update { false }
                return@launch
            }

            // Prefer the rich entry list (relay hints + petnames) when available.
            // Fall back to the legacy pubkey-only list for accounts not yet updated.
            val existingEntries = account.followEntries.ifEmpty {
                account.follows.map { FollowEntry(pubkey = it) }
            }
            val newEntries = if (add)
                (existingEntries + FollowEntry(pubkey = pubkey)).distinctBy { it.pubkey }
            else
                existingEntries.filter { it.pubkey != pubkey }

            val unsigned = UnsignedEvent(
                pubkey = signer.pubkey,
                kind = EventKind.FOLLOW_LIST,
                content = "",
                tags = newEntries.map { entry ->
                    buildJsonArray {
                        add(JsonPrimitive("p"))
                        add(JsonPrimitive(entry.pubkey))
                        if (entry.relay.isNotEmpty()) add(JsonPrimitive(entry.relay))
                        if (entry.petname.isNotEmpty()) add(JsonPrimitive(entry.petname))
                    }
                },
            )

            signer.signEvent(unsigned)
                .onSuccess { event ->
                    pool.publish(event)
                    accountRepository.updateAccount(
                        account.copy(
                            follows = newEntries.map { it.pubkey },
                            followEntries = newEntries,
                        )
                    )
                }
                .onFailure { e -> Timber.w(e, "toggleFollow failed") }

            _isFollowLoading.update { false }
        }
    }

    override fun onCleared() {
        notesSubId?.let { pool.unsubscribe(it) }
        metadataSubId?.let { pool.unsubscribe(it) }
    }
}
