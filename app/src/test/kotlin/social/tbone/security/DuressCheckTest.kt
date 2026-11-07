package social.tbone.security

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DuressCheckTest {
    @Test fun matchedDuressStartsWipeEvenIfCallerDisappears() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val started = AtomicBoolean(false)
        val caller = launch {
            verifyAndStartDuressWipe(
                verify = { entered.complete(Unit); release.await(); true },
                startWipe = { started.set(true) },
            )
        }
        entered.await()
        caller.cancel()
        release.complete(Unit)
        withTimeout(5_000) { caller.join() }
        assertTrue(started.get())
    }

    @Test fun nonMatchingPinNeverStartsWipe() = runBlocking {
        var started = false
        assertFalse(verifyAndStartDuressWipe(verify = { false }, startWipe = { started = true }))
        assertFalse(started)
    }
}
