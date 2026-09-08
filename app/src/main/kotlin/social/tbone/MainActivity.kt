package social.tbone

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import social.tbone.account.signer.AmberSignerBridge
import social.tbone.debug.FrameMonitor
import social.tbone.nostr.relay.RelayAuthManager
import social.tbone.notifications.DeepLinkHandler
import social.tbone.security.AppLockManager
import social.tbone.security.ScreenTimeManager
import social.tbone.settings.AppSettings
import social.tbone.settings.ContentFilterProvider
import social.tbone.notifications.EXTRA_EVENT_ID
import social.tbone.nostr.Nip19
import social.tbone.ui.BonyNavHost
import social.tbone.ui.SigningOverlay
import social.tbone.ui.theme.ThemeHost
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.ui.theme.parseHexColor
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var amberBridge: AmberSignerBridge
    @Inject lateinit var relayAuthManager: RelayAuthManager
    @Inject lateinit var deepLinkHandler: DeepLinkHandler
    @Inject lateinit var lockManager: AppLockManager
    @Inject lateinit var screenTimeManager: ScreenTimeManager
    @Inject lateinit var appSettings: AppSettings

    private val amberLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        amberBridge.onResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Opaque window background so not even the very first frame (or a
        // returning process) can flash a previous screen behind the lock.
        window.decorView.setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))

        // Forward pending Amber sign requests to the system intent launcher
        lifecycleScope.launch {
            amberBridge.pendingRequest.collect { request ->
                request?.let { amberLauncher.launch(it.intent) }
            }
        }

        relayAuthManager.start(lifecycleScope)

        handleIntent(intent)

        // Screenshot blocker: FLAG_SECURE whenever the app-wide toggle OR the
        // toolbox toggle is on. Managed here (the window owner) so it can never
        // be cleared mid-navigation between toolbox screens and reliably covers
        // every screen inside the toolbox.
        lifecycleScope.launch {
            combine(
                appSettings.screenshotBlockEnabled,
                appSettings.toolboxScreenshotBlockEnabled,
            ) { app, toolbox -> app || toolbox }.collect { block ->
                if (block) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE,
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        setContent {
            ThemeHost {
                val brightness by social.tbone.ui.media.ScreenBrightness.level
                    .collectAsStateWithLifecycle()
                val screenTimeLocked by screenTimeManager.locked.collectAsStateWithLifecycle()
                Box(modifier = Modifier.fillMaxSize()) {
                    ContentFilterProvider {
                        BonyNavHost()
                    }
                    // Parental screen-time lock: drawn OVER everything (but under
                    // the brightness dim) once the daily allowance is used up.
                    if (screenTimeLocked) {
                        social.tbone.ui.lock.ScreenTimeLockScreen(
                            onUnlocked = {},
                            verifyPin = { pin -> screenTimeManager.unlockWithParentalPin(pin) },
                        )
                    }
                    // Non-blocking "signing…" indicator while an external or
                    // remote signer is handling a request.
                    SigningOverlay()
                    // App-wide brightness overlay (dim = black, brighten = white).
                    // Non-interactive — touches pass straight through.
                    if (brightness != social.tbone.ui.media.ScreenBrightness.NORMAL) {
                        val overlayColor = if (brightness < social.tbone.ui.media.ScreenBrightness.NORMAL) {
                            androidx.compose.ui.graphics.Color.Black.copy(
                                alpha = ((social.tbone.ui.media.ScreenBrightness.NORMAL - brightness) * 1.6f)
                                    .coerceIn(0f, 0.75f)
                            )
                        } else {
                            androidx.compose.ui.graphics.Color.White.copy(
                                alpha = ((brightness - social.tbone.ui.media.ScreenBrightness.NORMAL) * 0.8f)
                                    .coerceIn(0f, 0.35f)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(overlayColor),
                        )
                    }
                }
            }
        }

        FrameMonitor.start(this)
    }

    override fun onResume() {
        super.onResume()
        screenTimeManager.onAppForeground()
    }

    override fun onPause() {
        super.onPause()
        screenTimeManager.onAppBackground()
    }

    override fun onStop() {
        super.onStop()
        lockManager.lock()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // From in-app notification tap (EXTRA_EVENT_ID set by NotificationHelper)
        intent?.getStringExtra(EXTRA_EVENT_ID)?.let { eventId ->
            deepLinkHandler.navigateToThread(eventId)
            return
        }

        // From nostr: URI (Pokey, browsers, other Nostr apps)
        val data = intent?.data ?: return
        if (data.scheme != "nostr") return
        val entity = data.schemeSpecificPart ?: return
        when {
            entity.startsWith("note1") || entity.startsWith("nevent1") ->
                Nip19.nostrUriToEventId(entity)?.let { deepLinkHandler.navigateToThread(it) }
            entity.startsWith("npub1") ->
                Nip19.npubToHex(entity)?.let { deepLinkHandler.navigateToProfile(it) }
            entity.startsWith("nprofile1") ->
                Nip19.nprofileToHex(entity)?.let { deepLinkHandler.navigateToProfile(it) }
        }
    }
}
