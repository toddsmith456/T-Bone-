package social.tbone.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.nostr.Event
import social.tbone.nostr.ProfileContent
import social.tbone.settings.AppSettings
import javax.inject.Inject

@HiltViewModel
class NwcWalletViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val nwcRepository: NwcRepository,
    private val zapService: ZapService,
) : ViewModel() {
    val zapsEnabled: StateFlow<Boolean> = appSettings.nwcZapsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val connectionState: StateFlow<NwcConnectionState> = nwcRepository.connectionState
    val status: StateFlow<String> = nwcRepository.status
    val balanceMsats = nwcRepository.balanceMsats
    val walletInfo = nwcRepository.walletInfo
    val zapNotifications = nwcRepository.zapNotifications

    private val _connectionText = MutableStateFlow("")
    val connectionText: StateFlow<String> = _connectionText.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _pendingZapIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingZapIds: StateFlow<Set<String>> = _pendingZapIds.asStateFlow()
    private val _sentZapIds = MutableStateFlow<Set<String>>(emptySet())
    val sentZapIds: StateFlow<Set<String>> = _sentZapIds.asStateFlow()
    private val _uncertainZapIds = MutableStateFlow<Set<String>>(emptySet())
    val uncertainZapIds: StateFlow<Set<String>> = _uncertainZapIds.asStateFlow()

    init {
        viewModelScope.launch {
            _sentZapIds.value = appSettings.nwcPaidZapIds()
            _uncertainZapIds.value = appSettings.nwcUncertainZapIds()
            try {
                nwcRepository.connectSaved()
            } catch (_: Exception) {
                // The repository exposes the actionable error state; startup
                // must not crash the feed if a saved wallet is unreachable.
            }
        }
    }

    fun setConnectionText(value: String) {
        _connectionText.value = value
        _message.value = null
    }

    fun setZapsEnabled(enabled: Boolean) {
        if (enabled && nwcRepository.connectionState.value != NwcConnectionState.READY) {
            _message.value = "validate a wallet connection before enabling zaps"
            return
        }
        viewModelScope.launch {
            appSettings.setNwcZapsEnabled(enabled)
            _message.value = if (enabled) "zaps enabled" else "zaps disabled"
        }
    }

    fun connect() {
        val raw = _connectionText.value.trim()
        if (raw.isBlank()) {
            _message.value = "paste your wallet's NWC address first"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                val result = try {
                    nwcRepository.saveAndConnect(raw)
                } catch (error: Exception) {
                    Result.failure(error)
                }
                result.onFailure { _message.value = it.message ?: "could not connect wallet" }
                // Do not leave the secret visible in the text field after a
                // successful save; the repository stores only encrypted material.
                if (nwcRepository.connectionState.value == NwcConnectionState.READY) {
                    _connectionText.value = ""
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { nwcRepository.disconnect() }
    }

    fun removeConnection() {
        viewModelScope.launch {
            nwcRepository.clearSavedConnection()
            _connectionText.value = ""
            _message.value = "wallet connection removed"
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val info = try {
                    nwcRepository.refreshWalletInfo()
                } catch (error: Exception) {
                    Result.failure(error)
                }
                val balance = try {
                    nwcRepository.refreshBalance()
                } catch (error: Exception) {
                    Result.failure(error)
                }
                _message.value = (info.exceptionOrNull() ?: balance.exceptionOrNull())?.message
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearMessage() { _message.value = null }
    fun report(message: String) { _message.value = message }

    /** Sends one zap and never retries a timed-out payment. */
    fun sendZap(event: Event, profile: ProfileContent, amountSats: Long) {
        if (event.id in _pendingZapIds.value || event.id in _sentZapIds.value || event.id in _uncertainZapIds.value) return
        _pendingZapIds.value = _pendingZapIds.value + event.id
        viewModelScope.launch {
            val result = zapService.zap(event, profile, amountSats)
            _pendingZapIds.value = _pendingZapIds.value - event.id
            if (result.isSuccess) {
                _sentZapIds.value = _sentZapIds.value + event.id
                appSettings.markNwcZapPaid(event.id)
                _message.value = "zap sent · $amountSats sats"
            } else {
                val error = result.exceptionOrNull()
                if (error is NwcPaymentTimeoutException) {
                    // The wallet may have settled while the response was lost.
                    // Keep this note blocked until the user reconnects/checks the
                    // wallet; generating a fresh invoice here could double-pay.
                    _uncertainZapIds.value = _uncertainZapIds.value + event.id
                    appSettings.markNwcZapUncertain(event.id)
                    _message.value = "zap status is unknown — check the wallet before retrying"
                } else {
                    _message.value = error?.message ?: "zap failed"
                }
            }
        }
    }
}
