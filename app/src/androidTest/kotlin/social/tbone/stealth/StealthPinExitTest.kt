package social.tbone.stealth

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.security.AppLockManager
import social.tbone.settings.AppSettings
import social.tbone.ui.StealthGateViewModel
import java.io.File

@RunWith(AndroidJUnit4::class)
class StealthPinExitTest {
    @Test fun decoyExitLocksAnAlreadyUnlockedPinBeforeExposingApp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "stealth-test-${java.util.UUID.randomUUID()}.preferences_pb")
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val settings = AppSettings(store, scope)
            withTimeout(10_000) { settings.initialized.first { it } }
            settings.setPinEnabled(true)
            val manager = AppLockManager(settings, scope, context)
            manager.onAppForegrounded()
            // Initialize the timer, then simulate an already-unlocked session.
            manager.lockForStealthExit()
            manager.unlock()
            assertFalse(manager.isLocked.value)
            settings.setDecoyUnlocked(false)
            val gate = StealthGateViewModel(settings, manager)
            gate.unlock()
            withTimeout(10_000) { settings.decoyUnlocked.first { it } }
            assertTrue("Exiting the decoy must require the configured PIN", manager.isLocked.value)
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
