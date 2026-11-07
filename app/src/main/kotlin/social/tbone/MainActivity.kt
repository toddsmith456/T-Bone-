package social.tbone

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import social.tbone.account.signer.AmberSignerBridge
import social.tbone.debug.FrameMonitor
import social.tbone.nostr.relay.RelayAuthManager
import social.tbone.notifications.DeepLinkHandler
import social.tbone.security.AppLockManager
import social.tbone.security.ToolboxLockManager
import social.tbone.security.ScreenTimeManager
import social.tbone.settings.AppSettings
import social.tbone.settings.ContentFilterProvider
import social.tbone.updates.LocalUpdateAvailable
import social.tbone.updates.UpdateManager
import social.tbone.notifications.dispatchNavigationIntent
import social.tbone.ui.BonyNavHost
import social.tbone.ui.SigningOverlay
import social.tbone.ui.theme.ThemeHost
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.ui.theme.parseHexColor
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private companion object { const val STATE_LINK_PROCESS = "tbone.link.process" }

    @Inject lateinit var amberBridge: AmberSignerBridge
    @Inject lateinit var relayAuthManager: RelayAuthManager
    @Inject lateinit var deepLinkHandler: DeepLinkHandler
    @Inject lateinit var lockManager: AppLockManager
    @Inject lateinit var toolboxLockManager: ToolboxLockManager
    @Inject lateinit var screenTimeManager: ScreenTimeManager
    @Inject lateinit var stealthManager: social.tbone.stealth.StealthManager
    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var updateManager: UpdateManager
    @Inject lateinit var noteWrites: social.tbone.notes.NoteWriteCoordinator
    @Inject lateinit var dataWiper: social.tbone.security.DataWiper
    @Inject lateinit var reactionsRepository: social.tbone.reactions.ReactionsRepository

    private val amberLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        amberBridge.onResult(result.resultCode, result.data)
    }

    /** Gate external links in one place, including ACTION_VIEW wrapped in a chooser. */
    override fun startActivity(intent: Intent) = startActivity(intent, null)

    override fun startActivity(intent: Intent, options: Bundle?) {
        @Suppress("DEPRECATION")
        val target = if (intent.action == Intent.ACTION_CHOOSER) {
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        } else intent
        val uri = target?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        val scheme = uri?.scheme?.lowercase()
        // File/content intents are local operations; the signer is a protocol
        // handoff rather than a link and must not interrupt signing.
        val external = uri != null && scheme != null && scheme !in setOf("file", "content", "nostrsigner")
        if (external && ::appSettings.isInitialized && !appSettings.initialized.value) {
            lifecycleScope.launch {
                appSettings.initialized.first { it }
                if (!isFinishing && !isDestroyed) startActivity(intent, options)
            }
            return
        }
        if (external && ::appSettings.isInitialized && appSettings.confirmExternalLinks.value) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                android.app.AlertDialog.Builder(this)
                    .setTitle("Open external link?")
                    .setMessage(uri.toString())
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open") { _, _ ->
                        runCatching { launchExternalIntent(intent, options) }
                            .onFailure { android.widget.Toast.makeText(this, "Could not open link", android.widget.Toast.LENGTH_SHORT).show() }
                    }
                    .show()
            }
        } else if (external) {
            // External handlers can disappear between intent resolution and
            // launch, including after the asynchronous settings-load wait.
            runCatching { launchExternalIntent(intent, options) }.onFailure {
                android.widget.Toast.makeText(this, "No app could open this link", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            launchExternalIntent(intent, options)
        }
    }

    private fun launchExternalIntent(intent: Intent, options: Bundle?) {
        super.startActivity(intent, options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Repair alias state for users who enabled the disguise before the
        // beta application-ID/manifest-class mismatch was fixed.
        lifecycleScope.launch {
            appSettings.initialized.first { it }
            runCatching { stealthManager.setNotepadFront(appSettings.notepadFrontEnabled.value) }
                .onFailure { timber.log.Timber.e(it, "Could not synchronize launcher disguise") }
            if (!appSettings.notepadFrontEnabled.value) maybeAskBatteryExemption()
        }

        // Until saved screenshot preferences are loaded, assume protection is
        // enabled. Otherwise a returning protected session could briefly expose
        // its first frame or task preview while the default false values load.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

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

        if (social.tbone.notifications.shouldHandleLaunchIntent(
                savedInstanceState?.getString(STATE_LINK_PROCESS), deepLinkHandler.processToken
            )) handleIntent(intent)

        // Screenshot blocker: FLAG_SECURE whenever the app-wide toggle OR the
        // toolbox toggle is on. Managed here (the window owner) so it can never
        // be cleared mid-navigation between toolbox screens and reliably covers
        // every screen inside the toolbox.
        lifecycleScope.launch {
            appSettings.initialized.first { it }
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
                val pendingWipe by noteWrites.pendingAppWipe.collectAsStateWithLifecycle()
                val invalidWipeState by noteWrites.invalidWipeState.collectAsStateWithLifecycle()
                if (pendingWipe) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0A0A0A)),
                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                        if (invalidWipeState) {
                            androidx.compose.material3.Text(
                                "Unable to unlock. Please contact support.",
                                color = androidx.compose.ui.graphics.Color.White,
                            )
                        } else {
                            androidx.compose.material3.Button(onClick = { dataWiper.wipeAndRestart() }) {
                                androidx.compose.material3.Text("Try again")
                            }
                        }
                    }
                } else {
                val brightness by social.tbone.ui.media.ScreenBrightness.level
                    .collectAsStateWithLifecycle()
                val screenTimeLocked by screenTimeManager.locked.collectAsStateWithLifecycle()
                var starting by remember { mutableStateOf(true) }
                var showStartupIcon by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    appSettings.initialized.first { it }
                    if (!appSettings.notepadFrontEnabled.value) {
                        showStartupIcon = true
                        kotlinx.coroutines.delay(500)
                    }
                    starting = false
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    // Multi emoji reactions: one app-wide config read by every
                    // note's like button.
                    val multiOn by appSettings.multiReactionsEnabled.collectAsStateWithLifecycle()
                    val savedEmojis by appSettings.reactionEmojis.collectAsStateWithLifecycle()
                    val reactionContents by reactionsRepository.reactionContents.collectAsStateWithLifecycle()
                    val updateState by updateManager.state.collectAsStateWithLifecycle()
                    val reactionConfig = androidx.compose.runtime.remember(multiOn, savedEmojis, reactionContents) {
                        social.tbone.ui.reactions.ReactionConfig(
                            emojis = if (multiOn) savedEmojis else emptyList(),
                            contents = reactionContents,
                            reactWith = { event, emoji -> reactionsRepository.react(event, emoji) },
                        )
                    }
                    social.tbone.ui.StartupGate(starting = starting, cover = {
                        Box(Modifier.fillMaxSize().clickable(indication = null,
                            interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}) {
                            if (showStartupIcon) social.tbone.ui.settings.StartupAppearance(this@MainActivity)
                            else Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0A0A0A)))
                        }
                    }) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            social.tbone.ui.reactions.LocalReactionConfig provides reactionConfig,
                            LocalUpdateAvailable provides updateState.updateAvailable,
                        ) {
                            ContentFilterProvider { BonyNavHost() }
                        }
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
        }

        FrameMonitor.start(this)
    }

    private fun maybeAskBatteryExemption() {
        val prefs = getSharedPreferences("battery_prompt", MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        val info = packageManager.getPackageInfo(packageName, 0)
        // Do not surprise users who are upgrading an existing installation.
        if (info.lastUpdateTime - info.firstInstallTime > 10_000L) {
            prefs.edit().putBoolean("asked", true).apply()
            return
        }
        val power = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("asked", true).apply()
            return
        }
        if (isFinishing || isDestroyed) return
        prefs.edit().putBoolean("asked", true).apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("Allow unrestricted battery usage?")
            .setMessage("Allowing unrestricted battery usage can improve background connectivity and notifications, but may use more battery. Android controls this setting and you can change it later.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Continue") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
                runCatching { startActivity(intent) }.onFailure {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }.show()
    }

    override fun onResume() {
        super.onResume()
        screenTimeManager.onAppForeground()
    }

    override fun onPause() {
        super.onPause()
        screenTimeManager.onAppBackground()
    }

    override fun onStart() {
        super.onStart()
        lockManager.onAppForegrounded()
        toolboxLockManager.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        lockManager.onAppBackgrounded()
        toolboxLockManager.onAppBackgrounded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_LINK_PROCESS, deepLinkHandler.processToken)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        dispatchNavigationIntent(intent, deepLinkHandler)
    }
}
