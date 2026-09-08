package social.tbone.notifications

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeepLinkHandler @Inject constructor() {

    private val _openThread = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openThread: SharedFlow<String> = _openThread.asSharedFlow()

    private val _openProfile = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openProfile: SharedFlow<String> = _openProfile.asSharedFlow()

    fun navigateToThread(eventId: String) {
        _openThread.tryEmit(eventId)
    }

    fun navigateToProfile(pubkey: String) {
        _openProfile.tryEmit(pubkey)
    }
}
