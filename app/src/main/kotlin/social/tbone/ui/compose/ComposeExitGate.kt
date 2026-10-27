package social.tbone.ui.compose

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guarantees one navigation action per composer instance. SEND success and the
 * × button share this gate, so even a duplicated one-shot effect or a late
 * signer callback cannot pop the graph twice.
 */
internal class ComposeExitGate {
    private val handled = AtomicBoolean(false)

    fun runOnce(action: () -> Unit) {
        if (handled.compareAndSet(false, true)) action()
    }
}
