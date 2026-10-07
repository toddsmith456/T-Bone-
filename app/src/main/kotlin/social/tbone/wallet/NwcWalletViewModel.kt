package social.tbone.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import social.tbone.nostr.Event
import social.tbone.nostr.ProfileContent
import social.tbone.settings.AppSettings
import social.tbone.settings.ZapAmounts
import javax.inject.Inject

@HiltViewModel
class NwcWalletViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val nwcRepository: NwcRepository,
    private val zapService: ZapService,
) : ViewModel() {
    val zapsEnabled: StateFlow<Boolean> = appSettings.nwcZapsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val zapAmountSats: StateFlow<Long> = appSettings.nwcZapAmountSats
        .stateIn(viewModelScope, SharingStarted.Eagerly, ZapAmounts.DEFAULT.first())
    val zapAmounts: StateFlow<List<Long>> = appSettings.nwcZapAmounts
        .stateIn(viewModelScope, SharingStarted.Eagerly, ZapAmounts.DEFAULT)
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
    /** One-shot completion events for haptic feedback; never persisted or replayed. */
    private val _zapSuccessEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val zapSuccessEvents = _zapSuccessEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            _sentZapIds.value = appSettings.nwcPaidZapIds()
            _uncertainZapIds.value = appSettings.nwcUncertainZapIds()
            try {
                nwcRepository.connectSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
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

    fun saveZapAmount(raw: String) {
        val amount = raw.trim().toLongOrNull()
        if (amount == null || amount !in ZapAmounts.MIN_SATS..ZapAmounts.MAX_SATS) {
            _message.value = "zap amount must be between ${ZapAmounts.MIN_SATS} and ${ZapAmounts.MAX_SATS} sats"
            return
        }
        viewModelScope.launch {
            runCatching {
                appSettings.setNwcZapAmountSats(amount)
                appSettings.addNwcZapAmount(amount)
            }.onSuccess {
                _message.value = "default zap amount saved · $amount sats"
            }.onFailure { _message.value = it.message ?: "could not save zap amount" }
        }
    }

    fun savePreset(index: Int, raw: String) {
        val amount = raw.trim().toLongOrNull()
        val current = zapAmounts.value.toMutableList()
        if (index !in current.indices || amount == null || amount !in ZapAmounts.MIN_SATS..ZapAmounts.MAX_SATS) {
            _message.value = "preset must be between ${ZapAmounts.MIN_SATS} and ${ZapAmounts.MAX_SATS} sats"
            return
        }
        current[index] = amount
        viewModelScope.launch {
            appSettings.setNwcZapAmounts(current)
            _message.value = "zap preset saved"
        }
    }

    fun addPreset(raw: String) {
        val amount = raw.trim().toLongOrNull()
        if (amount == null || amount !in ZapAmounts.MIN_SATS..ZapAmounts.MAX_SATS) {
            _message.value = "preset must be between ${ZapAmounts.MIN_SATS} and ${ZapAmounts.MAX_SATS} sats"
            return
        }
        viewModelScope.launch {
            appSettings.addNwcZapAmount(amount)
            _message.value = "zap preset added"
        }
    }

    fun removePreset(amount: Long) {
        if (zapAmounts.value.size <= 1) {
            _message.value = "keep at least one zap preset"
            return
        }
        viewModelScope.launch {
            appSettings.removeNwcZapAmount(amount)
            _message.value = "zap preset removed"
        }
    }

    fun movePreset(from: Int, to: Int) {
        viewModelScope.launch { appSettings.moveNwcZapAmount(from, to) }
    }

    fun reconnect() {
        viewModelScope.launch {
            _busy.value = true
            try {
                nwcRepository.reconnectSaved().onFailure { _message.value = it.message ?: "could not reconnect wallet" }
            } finally {
                _busy.value = false
            }
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
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
        // A completed zap must not lock the note to one amount: users may send
        // another zap with a different preset or custom amount. Only an in-flight
        // or financially uncertain payment is blocked.
        if (event.id in _pendingZapIds.value || event.id in _uncertainZapIds.value) return
        _pendingZapIds.value = _pendingZapIds.value + event.id
        viewModelScope.launch {
            val result = zapService.zap(event, profile, amountSats)
            _pendingZapIds.value = _pendingZapIds.value - event.id
            when (NwcPaymentPolicy.classify(result)) {
                NwcPaymentState.SUCCEEDED -> {
                    _sentZapIds.value = _sentZapIds.value + event.id
                    appSettings.markNwcZapPaid(event.id)
                    _zapSuccessEvents.tryEmit(event.id)
                    _message.value = "zap sent · $amountSats sats"
                }
                NwcPaymentState.UNKNOWN -> {
                    // The wallet may have settled while the response was lost.
                    // Keep this note blocked until the user reconnects/checks the
                    // wallet; generating a fresh invoice here could double-pay.
                    _uncertainZapIds.value = _uncertainZapIds.value + event.id
                    appSettings.markNwcZapUncertain(event.id)
                    _message.value = "zap status is unknown — check the wallet before retrying"
                }
                NwcPaymentState.FAILED -> {
                    _message.value = result.exceptionOrNull()?.message ?: "zap failed"
                }
            }
        }
    }
}
