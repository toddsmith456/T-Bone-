package social.tbone.ui.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.account.AccountRepository
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.media.BlossomUploader
import social.tbone.media.MediaProcessor
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.NostrJson
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayPool
import social.tbone.profile.ProfileRepository
import javax.inject.Inject

data class ProfileEditUiState(
    val uploading: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

/**
 * Edits the active account's profile picture and banner:
 * images are re-encoded (EXIF/metadata removed), compressed to the 800 KB cap,
 * uploaded to Blossom, and a kind-0 metadata event is published.
 */
@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val signerFactory: NostrSignerFactory,
    private val blossomUploader: BlossomUploader,
    private val profileRepository: ProfileRepository,
    private val pool: RelayPool,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileEditUiState())
    val uiState: StateFlow<ProfileEditUiState> = _uiState.asStateFlow()

    val activePubkey: StateFlow<String?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profile: StateFlow<ProfileContent?> =
        kotlinx.coroutines.flow.combine(activePubkey, profileRepository.profiles) { pk, all ->
            pk?.let { all[it] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Uploads a new avatar/banner image and publishes the updated profile. */
    fun setImage(uri: Uri, banner: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(uploading = true, error = null, done = false) }
            try {
                val signer = signerFactory.forActiveAccount()
                    ?: error("no active account")
                val prepared = MediaProcessor.prepareImage(
                    context = context,
                    uri = uri,
                    maxBytes = MediaProcessor.AVATAR_MAX_BYTES,
                    compress = true,
                ) ?: error("could not read image")
                val url = blossomUploader.upload(
                    file = prepared,
                    mime = "image/jpeg",
                    preferredServer = null, // default or random from the pool
                    signer = signer,
                )

                val existing = profileRepository.getProfile(signer.pubkey) ?: ProfileContent()
                val updated = if (banner) existing.copy(banner = url) else existing.copy(picture = url)
                val content = NostrJson.encodeToString(ProfileContent.serializer(), updated)

                val unsigned = UnsignedEvent(
                    pubkey = signer.pubkey,
                    kind = EventKind.METADATA,
                    content = content,
                )
                signer.signEvent(unsigned)
                    .onSuccess { event ->
                        pool.publish(event)
                        profileRepository.processEvent(event)
                        _uiState.update { it.copy(uploading = false, done = true, error = null) }
                    }
                    .onFailure { e ->
                        _uiState.update { it.copy(uploading = false, error = e.message ?: "sign failed") }
                    }
            } catch (e: Exception) {
                _uiState.update { it.copy(uploading = false, error = e.message ?: "upload failed") }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null, done = false) }
}
