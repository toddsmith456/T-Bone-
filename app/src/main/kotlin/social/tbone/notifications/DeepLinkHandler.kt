package social.tbone.notifications

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PendingNavigation {
    data class Thread(val eventId: String) : PendingNavigation
    data class Profile(val pubkey: String) : PendingNavigation
}

@Singleton
class DeepLinkHandler @Inject constructor() {
    // One queue preserves the order of mixed notification and URI destinations.
    // Pending destinations wait until the navigation graph is ready and unlocked.
    private val pending = Channel<PendingNavigation>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val destinations = pending.receiveAsFlow()

    fun navigateToThread(eventId: String) {
        pending.trySend(PendingNavigation.Thread(eventId))
    }

    fun navigateToProfile(pubkey: String) {
        pending.trySend(PendingNavigation.Profile(pubkey))
    }
}
