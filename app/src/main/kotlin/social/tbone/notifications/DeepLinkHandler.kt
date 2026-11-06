package social.tbone.notifications

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeepLinkHandler @Inject constructor() {

    private val _openThread = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val openThread = _openThread.receiveAsFlow()

    private val _openProfile = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val openProfile = _openProfile.receiveAsFlow()

    fun navigateToThread(eventId: String) {
        _openThread.trySend(eventId)
    }

    fun navigateToProfile(pubkey: String) {
        _openProfile.trySend(pubkey)
    }
}
