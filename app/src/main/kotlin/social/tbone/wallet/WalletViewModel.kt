package social.tbone.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.account.AccountRepository
import social.tbone.nostr.Event
import social.tbone.settings.AppSettings
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val accountRepository: AccountRepository,
    private val paymentTargetsRepository: PaymentTargetsRepository,
    private val zapService: ZapService,
    private val profileRepository: social.tbone.profile.ProfileRepository,
) : ViewModel() {
    val moneroXapsEnabled: StateFlow<Boolean> = appSettings.moneroXapsEnabled
    val lightningZapsEnabled: StateFlow<Boolean> = appSettings.lightningZapsEnabled

    val activePubkey: StateFlow<String?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // ── Monero (existing) ──────────────────────────────────────────────────
    private val _ownMoneroAddress = MutableStateFlow("")
    val ownMoneroAddress: StateFlow<String> = _ownMoneroAddress.asStateFlow()
    private val _hasOwnMoneroTarget = MutableStateFlow(false)
    val hasOwnMoneroTarget: StateFlow<Boolean> = _hasOwnMoneroTarget.asStateFlow()
    private val _ownAddressStatus = MutableStateFlow("Load the current payment target from your relays.")
    val ownAddressStatus: StateFlow<String> = _ownAddressStatus.asStateFlow()
    private val _loadingOwnAddress = MutableStateFlow(false)
    val loadingOwnAddress: StateFlow<Boolean> = _loadingOwnAddress.asStateFlow()
    private var loadingOwnPubkey: String? = null
    private var displayedOwnPubkey: String? = null

    fun setMoneroXapsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setMoneroXapsEnabled(enabled)
            _message.value = if (enabled) "Monero xap buttons enabled" else "Monero xap buttons hidden"
        }
    }

    private val _ownLightningAddress = MutableStateFlow("")
    val ownLightningAddress = _ownLightningAddress.asStateFlow()
    private val _lightningStatus = MutableStateFlow("Load your profile from connected relays.")
    val lightningStatus = _lightningStatus.asStateFlow()
    private val _loadingLightning = MutableStateFlow(false)
    val loadingLightning = _loadingLightning.asStateFlow()

    fun setLightningZapsEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setLightningZapsEnabled(enabled) }
    }
    fun setOwnLightningAddress(value: String) { _ownLightningAddress.value = value }
    fun loadLightningProfile(pubkey: String?) {
        _ownLightningAddress.value = ""
        if (pubkey == null) { _lightningStatus.value = "Sign in to load your profile."; return }
        viewModelScope.launch {
            _loadingLightning.value = true
            _lightningStatus.value = "Checking connected relays…"
            try {
                val (event, synced) = paymentTargetsRepository.lookupLightningProfile(pubkey)
                if (activePubkey.value != pubkey) return@launch
                _ownLightningAddress.value = event?.let { social.tbone.nostr.ProfileContent.parse(it.content)?.lightningAddress }.orEmpty()
                _lightningStatus.value = if (synced) "Profile loaded from connected relays." else "Could not confirm latest profile; publishing is blocked until relays respond."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (activePubkey.value == pubkey) _lightningStatus.value = error.message ?: "Could not load profile" }
            finally { _loadingLightning.value = false }
        }
    }
    fun saveLightningProfile(remove: Boolean = false) {
        val pubkey = activePubkey.value ?: return
        val value = _ownLightningAddress.value.trim()
        if (!remove && !isLightningAddressShape(value)) {
            _lightningStatus.value = "Enter a valid user@domain or LNURL."
            return
        }
        viewModelScope.launch {
            _loadingLightning.value = true
            _lightningStatus.value = "Publishing profile…"
            try {
                val result = paymentTargetsRepository.publishLightningProfile(pubkey, if (remove) null else value)
                if (activePubkey.value == pubkey) {
                    result.onSuccess { receipt ->
                        if (remove) _ownLightningAddress.value = ""
                        _lightningStatus.value = "Profile accepted by ${receipt.acceptedRelays} relay(s)."
                    }.onFailure { error -> _lightningStatus.value = error.message ?: "Could not publish profile" }
                }
            } finally { _loadingLightning.value = false }
        }
    }

    suspend fun createLightningInvoice(event: Event, profile: social.tbone.nostr.ProfileContent?, sats: Long): Result<String> {
        val recipient = profile?.takeIf { it.lightningAddress != null } ?: profileRepository.getProfile(event.pubkey)
            ?: return Result.failure(IllegalStateException("This profile has no Lightning address"))
        return zapService.createInvoice(event, recipient, sats)
    }

    suspend fun lookupMoneroTarget(pubkey: String): MoneroTargetLookup =
        paymentTargetsRepository.lookupMoneroTarget(pubkey)

    // ── Monero address publish (unchanged) ─────────────────────────────────
    fun setOwnMoneroAddress(value: String) {
        _ownMoneroAddress.value = value
        _message.value = null
    }

    fun clearOwnMoneroTarget() {
        displayedOwnPubkey = null
        _ownMoneroAddress.value = ""
        _hasOwnMoneroTarget.value = false
        _message.value = null
        _ownAddressStatus.value = "Sign in to load the current payment target."
    }

    fun loadOwnMoneroTarget(pubkey: String) {
        if (displayedOwnPubkey != pubkey) {
            displayedOwnPubkey = pubkey
            _ownMoneroAddress.value = ""
            _hasOwnMoneroTarget.value = false
            _message.value = null
            _ownAddressStatus.value = "Checking connected relays…"
        }
        if (loadingOwnPubkey == pubkey) return
        loadingOwnPubkey = pubkey
        _loadingOwnAddress.value = true
        viewModelScope.launch {
            _ownAddressStatus.value = "Checking connected relays…"
            try {
                val result = paymentTargetsRepository.lookupMoneroTarget(pubkey, forceRefresh = true)
                if (activePubkey.value != pubkey) return@launch
                val address = result.moneroAddress.orEmpty()
                _ownMoneroAddress.value = address
                _hasOwnMoneroTarget.value = result.hasMoneroTag
                _ownAddressStatus.value = when {
                    result.usedLocalCache && address.isNotEmpty() ->
                        "Showing the last saved address; connected relays did not confirm it"
                    result.usedLocalCache -> "Last saved Monero tag could not be confirmed by connected relays"
                    result.fullySynced && address.isNotEmpty() -> "Current public Monero target loaded"
                    result.fullySynced && result.event != null -> "Latest payment-target event has no usable Monero address"
                    result.fullySynced -> "No Monero target is currently published"
                    address.isNotEmpty() -> "Showing the last saved address; relays could not all be checked"
                    else -> "Could not reach connected relays. Try again before publishing."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activePubkey.value == pubkey) {
                    _ownAddressStatus.value = error.message ?: "Could not load the current payment target"
                }
            } finally {
                if (loadingOwnPubkey == pubkey) {
                    loadingOwnPubkey = null
                    _loadingOwnAddress.value = false
                }
            }
        }
    }

    fun publishOwnMoneroAddress() {
        val normalized = MoneroAddressPolicy.normalizeOrNull(_ownMoneroAddress.value)
        if (normalized == null) {
            _message.value = "Enter a valid Monero address before publishing"
            return
        }
        saveOwnMoneroAddress(normalized)
    }

    fun removeOwnMoneroAddress() = saveOwnMoneroAddress(null)

    private fun saveOwnMoneroAddress(address: String?) {
        val pubkey = activePubkey.value
        if (pubkey == null) {
            _message.value = "Sign in to publish a Monero address"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            _ownAddressStatus.value = "Publishing kind-10133 payment target…"
            try {
                paymentTargetsRepository.publishOwnMoneroAddress(pubkey, address)
                    .onSuccess { receipt ->
                        val stillActive = activePubkey.value == pubkey
                        if (stillActive) {
                            _ownMoneroAddress.value = address.orEmpty()
                            _hasOwnMoneroTarget.value = !address.isNullOrBlank()
                            _ownAddressStatus.value = if (address.isNullOrBlank()) {
                                "Monero target removed; other payment targets were preserved"
                            } else {
                                "Published to ${receipt.acceptedRelays} relay(s)"
                            }
                        }
                        _message.value = when {
                            !stillActive -> "Monero payment target published for the previous account"
                            receipt.attemptedRelays > receipt.acceptedRelays ->
                                "Address accepted by ${receipt.acceptedRelays} of ${receipt.attemptedRelays} relays"
                            else -> "Monero payment target published"
                        }
                    }
                    .onFailure { error ->
                        _ownAddressStatus.value = error.message ?: "Could not publish the payment target"
                        _message.value = _ownAddressStatus.value
                    }
            } finally {
                _busy.value = false
            }
        }
    }


}

internal fun isLightningAddressShape(value: String): Boolean {
    val address = value.trim()
    if (address.startsWith("lnurl1", ignoreCase = true)) return social.tbone.nostr.Nip19.lnurlToUrl(address)?.startsWith("https://", true) == true
    if (address.any(Char::isWhitespace) || address.count { it == '@' } != 1) return false
    val parts = address.split('@')
    return parts[0].isNotEmpty() && parts[1].contains('.') && !parts[1].startsWith('.') && !parts[1].endsWith('.') && parts[1].none { it in "/:?#" }
}
