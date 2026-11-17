package social.tbone.ui.tokens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.account.AccountRepository
import social.tbone.nostr.Event
import social.tbone.nostr.ProfileContent
import social.tbone.profile.ProfileRepository
import social.tbone.tokens.IncomingSteakOffer
import social.tbone.tokens.LeaderboardScope
import social.tbone.tokens.ProfileSteakSummary
import social.tbone.tokens.SteakLeaderboardEntry
import social.tbone.tokens.SteakRepository
import javax.inject.Inject

@HiltViewModel
class SteakLeaderboardViewModel @Inject constructor(
    private val steakRepository: SteakRepository,
    accountRepository: AccountRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {

    private val _scope = MutableStateFlow(LeaderboardScope.FOLLOWS)
    val scope: StateFlow<LeaderboardScope> = _scope.asStateFlow()

    val entries: StateFlow<List<SteakLeaderboardEntry>> = combine(
        _scope,
        steakRepository.followsLeaderboard,
        steakRepository.globalLeaderboard,
    ) { currentScope, follows, global ->
        when (currentScope) {
            LeaderboardScope.FOLLOWS -> follows
            LeaderboardScope.GLOBAL -> global
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isLoading: StateFlow<Boolean> = steakRepository.leaderboardLoading

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val activePubkey: StateFlow<String?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val mySummary: StateFlow<ProfileSteakSummary?> = combine(
        activePubkey,
        steakRepository.profileSummaries,
    ) { pubkey, summaries ->
        pubkey?.let { summaries[it] ?: ProfileSteakSummary(pubkey = it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cooldownUntilEpochSec: StateFlow<Long> = steakRepository.cooldownUntilEpochSec

    val incomingOffers: StateFlow<List<IncomingSteakOffer>> = steakRepository.incomingOffers

    val unlistedClaimCount: StateFlow<Int> = steakRepository.unlistedClaimCount

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        steakRepository.refreshLeaderboard(LeaderboardScope.FOLLOWS)
    }

    fun selectScope(newScope: LeaderboardScope) {
        if (_scope.value == newScope) return
        _scope.value = newScope
        steakRepository.refreshLeaderboard(newScope)
    }

    fun refresh() {
        steakRepository.refreshLeaderboard(_scope.value)
        activePubkey.value?.let { steakRepository.loadProfileSteaks(it, forceRefresh = true) }
    }

    fun claimSteak(offerEvent: Event) {
        viewModelScope.launch {
            steakRepository.claimSteak(offerEvent)
                .onSuccess {
                    _messages.tryEmit("Steak accepted & added to your profile!")
                    steakRepository.refreshLeaderboard(_scope.value)
                }
                .onFailure { err ->
                    _messages.tryEmit(err.message ?: "Couldn't accept steak")
                }
        }
    }

    fun syncSteakShelf() {
        viewModelScope.launch {
            steakRepository.syncUnlistedClaims()
                .onSuccess { count ->
                    if (count > 0) _messages.tryEmit("Synced $count steak${if (count == 1) "" else "s"} to your profile")
                    steakRepository.refreshLeaderboard(_scope.value)
                }
                .onFailure { err ->
                    _messages.tryEmit(err.message ?: "Couldn't sync steak shelf")
                }
        }
    }
}
