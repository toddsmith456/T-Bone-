package social.tbone.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinAttemptLimiterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun clear() { context.getSharedPreferences("pin_retry_limits", Context.MODE_PRIVATE).edit().clear().commit() }
    @Before fun setUp() = clear()
    @After fun tearDown() = clear()
    @Test fun retryDelayPersistsAcrossInstancesAndCanBeReset() {
        val first = PinAttemptLimiter(context, "app")
        repeat(5) { first.failure(1_000L) }
        val reopened = PinAttemptLimiter(context, "app")
        assertEquals(30_000L, reopened.remainingMillis(1_000L))
        assertEquals(0L, reopened.remainingMillis(31_000L))
        reopened.reset()
        assertEquals(0L, first.remainingMillis(1_000L))
    }
}
