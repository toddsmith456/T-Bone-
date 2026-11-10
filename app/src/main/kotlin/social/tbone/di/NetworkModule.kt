package social.tbone.di

import android.content.Context
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import social.tbone.nostr.relay.RelayConnection
import social.tbone.nostr.relay.RelayPool
import social.tbone.settings.AppSettings
import social.tbone.utils.OrbotHelper
import social.tbone.utils.OrbotStatus
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Provides the global feed pool and network dependencies.
 * 
 * Android 8 (API 26) Fix: Startup Tor-Guard Race
 * Await initialized.first() BEFORE creating the pool/factory to ensure
 * TorCallPolicy.guard is updated with the correct epoch/settings.
 * This prevents the race where RelayPool starts with initialTor=true
 * before DataStore read finishes on slow API 26 devices.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @GlobalFeedPool
    fun provideGlobalFeedPool(scope: CoroutineScope, appSettings: AppSettings, @ApplicationContext context: Context): RelayPool {
        // Await initialized.first() BEFORE creating the pool/factory to ensure
        // TorCallPolicy.guard is updated with the correct epoch/settings.
        // This prevents the race where RelayPool starts with initialTor=true
        // before DataStore read finishes on slow API 26 devices.
        val initializedValue = appSettings.initialized.first() // Blocks until true (safe for DI)
        
        return createPool(scope, appSettings, context, initializedValue)
    }

    private fun createPool(scope: CoroutineScope, appSettings: AppSettings, context: Context, initializedValue: Boolean): RelayPool {
        val pool = RelayPool(
            scope = scope,
            connectionFactory = { url ->
                // startupRequiresTor now uses the already-initialized value
                val tor = startupRequiresTor(initializedValue, appSettings.torEnabled.value)
                RelayConnection(url, RelayConnection.buildClient(
                    useTor = tor,
                    bypassProxy = RelayConnection.isLocalRelayUrl(url),
                )).guardedBy(TorCallPolicy.guard, tor)
            },
        )
        // On each Tor toggle, probe the reachable Orbot proxy (HTTP CONNECT preferred
        // over SOCKS to avoid the local DNS leak) and reconnect all relays.
        scope.launch {
            // Already awaited above, but keep for safety in case of mid-run toggle
            appSettings.initialized.first { it }
            appSettings.torEnabled.collect { useTor ->
                if (useTor != pool.isTorEnabled) {
                    pool.updateTorMode(useTor)
                }
            }
            appSettings.torKillSwitch.collect { killSwitch ->
                if (killSwitch) pool.forceDisconnectAll()
            }
        }
        return pool
    }
}

/** Unknown persisted transport mode is treated as Tor until settings load. */
internal fun startupRequiresTor(settingsReady: Boolean, torEnabled: Boolean): Boolean =
    !settingsReady || torEnabled