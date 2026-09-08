package social.tbone.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import social.tbone.account.signer.SigningIndicator
import javax.inject.Inject

/** Exposes the app-wide signing state to [SigningOverlay]. */
@HiltViewModel
class SigningOverlayViewModel @Inject constructor(
    signingIndicator: SigningIndicator,
) : ViewModel() {

    val signingInProgress: StateFlow<Boolean> = signingIndicator.signingInProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
