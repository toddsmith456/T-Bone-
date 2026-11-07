package social.tbone.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.account.NsecBunkerConfig
import social.tbone.account.SignerType
import social.tbone.account.signer.AmberSignerBridge
import social.tbone.account.signer.LocalKeySigner
import social.tbone.account.signer.NsecBunkerSigner
import social.tbone.account.signer.SigningIndicator
import social.tbone.nostr.Nip19
import social.tbone.updates.AmberInstallManager
import social.tbone.updates.AmberInstallState
import social.tbone.nostr.relay.RelayPool
import javax.inject.Inject

data class OnboardingUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    val showNsecBunkerForm: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val amberBridge: AmberSignerBridge,
    private val signingIndicator: SigningIndicator,
    private val pool: RelayPool,
    private val amberInstallManager: AmberInstallManager,
    private val noteWrites: social.tbone.notes.NoteWriteCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()
    val amberInstallState: StateFlow<AmberInstallState> = amberInstallManager.state

    fun refreshAmberInstallState() = amberInstallManager.refreshInstalled()

    /** NIP-55: ask Amber for the user's public key. */
    fun addAccountWithAmber(callerPackage: String) {
        if (_uiState.value.isLoading) return
        val epoch = noteWrites.currentWipeEpoch()
        if (!amberInstallManager.isAmberInstalled()) {
            _uiState.update { it.copy(error = "Amber isn't installed yet. Tap GET AMBER to install it, then try again.") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("type", "get_public_key")
                putExtra("package", callerPackage)
            }
            try {
                val raw = amberBridge.request(intent).getOrThrow()
                val hexPubkey = Nip19.normalisePubkey(raw)
                    ?: error("Amber returned an unrecognised pubkey format")
                val account = Account(pubkey = hexPubkey, signerType = SignerType.AMBER)
                noteWrites.runCancellableAtEpoch(epoch) { accountRepository.addAccount(account) }
                _uiState.update { it.copy(isLoading = false, success = true) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "Could not add account") }
            }
        }
    }

    /** NIP-46: connect to an nsecBunker via a bunker:// URI. */
    fun addAccountWithNsecBunker(bunkerUrl: String) {
        if (_uiState.value.isLoading) return
        val epoch = noteWrites.currentWipeEpoch()
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val uri = Uri.parse(bunkerUrl.trim())
                check(uri.scheme == "bunker") { "URL must start with bunker://" }
                val bunkerPubkey = uri.host
                    ?: error("Could not parse bunker pubkey from URL")
                val relayUrl = uri.getQueryParameter("relay")
                    ?: error("No relay= parameter in bunker URL")
                val secret = uri.getQueryParameter("secret") ?: ""

                // Generate ephemeral session keypair for the NIP-46 channel
                val (sessionSigner, encryptedSessionKey) = noteWrites.runCancellableAtEpoch(epoch) { LocalKeySigner.generate() }
                val config = NsecBunkerConfig(
                    bunkerPubkey = bunkerPubkey,
                    relayUrl = relayUrl,
                    secret = secret,
                    sessionPubkey = sessionSigner.pubkey,
                )

                // Placeholder pubkey — replaced after handshake reveals the real one
                val tempSigner = NsecBunkerSigner("", config, pool, sessionSigner, signingIndicator)
                val userPubkey = tempSigner.handshake().getOrThrow()

                val account = Account(
                    pubkey = userPubkey,
                    signerType = SignerType.NSEC_BUNKER,
                    nsecBunkerConfig = config,
                )
                // Store account; encrypted session key persisted under the user's pubkey
                noteWrites.runCancellableAtEpoch(epoch) { accountRepository.addAccount(account, encryptedSessionKey) }
            }.onSuccess {
                _uiState.update { it.copy(isLoading = false, success = true) }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Connection failed") }
            }
        }
    }

    /** Last resort: generate a local secp256k1 keypair stored in Android Keystore. */
    fun addLocalKeyAccount() {
        if (_uiState.value.isLoading) return
        val epoch = noteWrites.currentWipeEpoch()
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                noteWrites.runCancellableAtEpoch(epoch) {
                    val (signer, encryptedPrivkey) = LocalKeySigner.generate()
                    val account = Account(
                        pubkey = signer.pubkey,
                        signerType = SignerType.LOCAL_KEY,
                    )
                    accountRepository.addAccount(account, encryptedPrivkey)
                }
                _uiState.update { it.copy(isLoading = false, success = true) }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun showNsecBunkerForm() = _uiState.update { it.copy(showNsecBunkerForm = true) }
    fun hideNsecBunkerForm() = _uiState.update { it.copy(showNsecBunkerForm = false) }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
