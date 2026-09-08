package social.tbone.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import social.tbone.nostr.Nip19
import social.tbone.nostr.identity.Phrase
import javax.inject.Inject

@HiltViewModel
class VerifyIdentityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pubkey: String = checkNotNull(savedStateHandle["pubkey"])
    val npub: String = Nip19.hexToNpub(pubkey)
    val phrase6: List<String> = Phrase.wordsFor(pubkey, 6)
}
