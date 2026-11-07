package social.tbone.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbotInstallTest {
    @Test fun opensOfficialDownloadPageAndReportsMissingHandler() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        var launched: Intent? = null
        val available = object : ContextWrapper(base) { override fun startActivity(intent: Intent) { launched = intent } }
        assertTrue(openOrbotInstallPage(available))
        assertEquals(Intent.ACTION_VIEW, launched?.action)
        assertEquals("https://orbot.app/en/download/", launched?.data.toString())
        val missing = object : ContextWrapper(base) { override fun startActivity(intent: Intent) { throw ActivityNotFoundException() } }
        assertFalse(openOrbotInstallPage(missing))
    }
}
