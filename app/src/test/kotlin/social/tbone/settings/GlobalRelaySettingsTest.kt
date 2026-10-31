package social.tbone.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class GlobalRelaySettingsTest {
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var settings: AppSettings
    private lateinit var store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    @Before fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("relay-test", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = scope) { file }
        settings = AppSettings(store, scope)
        runBlocking { settings.initialized.first { it } }
    }
    @After fun cleanup() { scope.cancel(); file.delete() }

    @Test fun `empty global list and zero enabled are preserved, reset is explicit`() = runBlocking {
        val relay = "wss://example.org"
        settings.setGlobalRelays(emptySet())
        settings.globalRelays.first { it.isEmpty() }
        settings.addGlobalRelay(relay)
        settings.globalActiveRelays.first { it == setOf(relay) }
        settings.toggleGlobalRelayActive(relay)
        settings.globalActiveRelays.first { it.isEmpty() }
        assertEquals(setOf(relay), settings.globalRelays.value)
        settings.removeGlobalRelay(relay)
        settings.globalRelays.first { it.isEmpty() }
        assertEquals(emptySet<String>(), settings.globalActiveRelays.value)
        val reloaded = AppSettings(store, scope)
        reloaded.initialized.first { it }
        assertEquals(emptySet<String>(), reloaded.globalRelays.value)
        assertEquals(emptySet<String>(), reloaded.globalActiveRelays.value)
        settings.resetGlobalRelays()
        settings.globalRelays.first { it == AppSettings.GLOBAL_RELAY_DEFAULTS }
        settings.globalActiveRelays.first { it == AppSettings.GLOBAL_RELAY_DEFAULTS }
        Unit
    }
}
