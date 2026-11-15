package social.tbone.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import social.tbone.settings.AppSettings
import java.io.File

class ScreenTimeManagerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var appSettings: AppSettings
    private lateinit var manager: ScreenTimeManager

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("screen-time", ".preferences_pb").also { it.delete() }
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        appSettings = AppSettings(dataStore, scope)
        manager = ScreenTimeManager(appSettings, scope)
    }

    @After
    fun tearDown() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        file.delete()
        Unit
    }

    @Test
    fun `disabled screen time does not write to datastore on foreground or background`() = runBlocking {
        appSettings.initialized.first { it }
        assertFalse(appSettings.screenTimeEnabled.value)

        manager.onAppForeground()
        delay(50)
        manager.onAppBackground()
        delay(50)

        assertEquals(0L, appSettings.getScreenTimeUsedSeconds())
        assertFalse(manager.locked.value)
    }

    @Test
    fun `addScreenTimeSeconds accumulates atomically and ignores non-positive values`() = runBlocking {
        appSettings.initialized.first { it }

        appSettings.addScreenTimeSeconds(0L)
        appSettings.addScreenTimeSeconds(-5L)
        assertEquals(0L, appSettings.getScreenTimeUsedSeconds())

        appSettings.addScreenTimeSeconds(12L)
        appSettings.addScreenTimeSeconds(18L)
        assertEquals(30L, appSettings.getScreenTimeUsedSeconds())
    }
}
