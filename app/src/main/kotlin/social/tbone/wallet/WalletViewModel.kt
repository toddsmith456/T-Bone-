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
    private val zapService: ZapService,
    private val paymentTargetsRepository: PaymentTargetsRepository,
) : ViewModel() {
    val lightningZapsEnabled: StateFlow<Boolean> = appSettings.lightningZapsEnabled
    val moneroXapsEnabled: StateFlow<Boolean> = appSettings.moneroXapsEnabled

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

    // ── Lightning (mirrors Monero exactly) ─────────────────────────────────
    private val _ownLightningAddress = MutableStateFlow("")
    val ownLightningAddress: StateFlow<String> = _ownLightningAddress.asStateFlow()
    private val _hasOwnLightningTarget = MutableStateFlow(false)
    val hasOwnLightningTarget: StateFlow<Boolean> = _hasOwnLightningTarget.asStateFlow()
    private val _ownLightningStatus = MutableStateFlow("Load the current payment target from your relays.")
    val ownLightningStatus: StateFlow<String> = _ownLightningStatus.asStateFlow()
    private val _loadingOwnLightning = MutableStateFlow(false)
    val loadingOwnLightning: StateFlow<Boolean> = _loadingOwnLightning.asStateFlow()
    private var loadingOwnLightningPubkey: String? = null
    private var displayedOwnLightningPubkey: String? = null

    fun setLightningZapsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setLightningZapsEnabled(enabled)
            _message.value = if (enabled) "Lightning zap buttons enabled" else "Lightning zap buttons hidden"
        }
    }

    fun setMoneroXapsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setMoneroXapsEnabled(enabled)
            _message.value = if (enabled) "Monero xap buttons enabled" else "Monero xap buttons hidden"
        }
    }

    /** Lightning invoice via NIP-57 — mirrors Monero's address lookup hand-off, but returns BOLT-11. */
    suspend fun createZapInvoice(event: Event, lightningAddress: String, amountSats: Long = 21L): Result<String> =
        zapService.createInvoiceForAddress(event, lightningAddress, amountSats)

    suspend fun lookupMoneroTarget(pubkey: String): MoneroTargetLookup =
        paymentTargetsRepository.lookupMoneroTarget(pubkey)

    suspend fun lookupLightningTarget(pubkey: String): LightningTargetLookup =
        paymentTargetsRepository.lookupLightningTarget(pubkey)

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

    // ── Lightning address publish (mirrors Monero 1:1) ───────────────────────
    fun setOwnLightningAddress(value: String) {
        _ownLightningAddress.value = value
        _message.value = null
    }

    fun clearOwnLightningTarget() {
        displayedOwnLightningPubkey = null
        _ownLightningAddress.value = ""
        _hasOwnLightningTarget.value = false
        _message.value = null
        _ownLightningStatus.value = "Sign in to load the current payment target."
    }

    fun loadOwnLightningTarget(pubkey: String) {
        if (displayedOwnLightningPubkey != pubkey) {
            displayedOwnLightningPubkey = pubkey
            _ownLightningAddress.value = ""
            _hasOwnLightningTarget.value = false
            _message.value = null
            _ownLightningStatus.value = "Checking connected relays…"
        }
        if (loadingOwnLightningPubkey == pubkey) return
        loadingOwnLightningPubkey = pubkey
        _loadingOwnLightning.value = true
        viewModelScope.launch {
            _ownLightningStatus.value = "Checking connected relays…"
            try {
                val result = paymentTargetsRepository.lookupLightningTarget(pubkey, forceRefresh = true)
                if (activePubkey.value != pubkey) return@launch
                val address = result.lightningAddress.orEmpty()
                _ownLightningAddress.value = address
                _hasOwnLightningTarget.value = result.hasLightningTag
                _ownLightningStatus.value = when {
                    result.usedLocalCache && address.isNotEmpty() ->
                        "Showing the last saved address; connected relays did not confirm it"
                    result.usedLocalCache -> "Last saved Lightning tag could not be confirmed by connected relays"
                    result.fullySynced && address.isNotEmpty() -> "Current public Lightning target loaded"
                    result.fullySynced && result.event != null -> "Latest payment-target event has no usable Lightning address"
                    result.fullySynced -> "No Lightning target is currently published"
                    address.isNotEmpty() -> "Showing the last saved address; relays could not all be checked"
                    else -> "Could not reach connected relays. Try again before publishing."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activePubkey.value == pubkey) {
                    _ownLightningStatus.value = error.message ?: "Could not load the current payment target"
                }
            } finally {
                if (loadingOwnLightningPubkey == pubkey) {
                    loadingOwnLightningPubkey = null
                    _loadingOwnLightning.value = false
                }
            }
        }
    }

    fun publishOwnLightningAddress() {
        val normalized = LightningAddressPolicy.normalizeOrNull(_ownLightningAddress.value)
        if (normalized == null) {
            _message.value = "Enter a valid Lightning address (user@domain or lnurl1...)"
            return
        }
        saveOwnLightningAddress(normalized)
    }

    fun removeOwnLightningAddress() = saveOwnLightningAddress(null)

    private fun saveOwnLightningAddress(address: String?) {
        val pubkey = activePubkey.value
        if (pubkey == null) {
            _message.value = "Sign in to publish a Lightning address"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            _ownLightningStatus.value = "Publishing kind-10133 payment target…"
            try {
                paymentTargetsRepository.publishOwnLightningAddress(pubkey, address)
                    .onSuccess { receipt ->
                        val stillActive = activePubkey.value == pubkey
                        if (stillActive) {
                            _ownLightningAddress.value = address.orEmpty()
                            _hasOwnLightningTarget.value = !address.isNullOrBlank()
                            _ownLightningStatus.value = if (address.isNullOrBlank()) {
                                "Lightning target removed; other payment targets were preserved"
                            } else {
                                "Published to ${receipt.acceptedRelays} relay(s)"
                            }
                        }
                        _message.value = when {
                            !stillActive -> "Lightning payment target published for the previous account"
                            receipt.attemptedRelays > receipt.acceptedRelays ->
                                "Address accepted by ${receipt.acceptedRelays} of ${receipt.attemptedRelays} relays"
                            else -> "Lightning payment target published"
                        }
                    }
                    .onFailure { error ->
                        _ownLightningStatus.value = error.message ?: "Could not publish the payment target"
                        _message.value = _ownLightningStatus.value
                    }
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearMessage() { _message.value = null }
}
