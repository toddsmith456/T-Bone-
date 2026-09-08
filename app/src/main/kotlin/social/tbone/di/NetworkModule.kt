package social.tbone.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import social.tbone.nostr.relay.RelayConnection
import social.tbone.nostr.relay.RelayPool
import social.tbone.settings.AppSettings
import social.tbone.settings.OrbotHelper
import social.tbone.settings.OrbotStatus
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideRelayPool(
        scope: CoroutineScope,
        appSettings: AppSettings,
        @ApplicationContext context: Context,
    ): RelayPool {
        val pool = RelayPool(
            scope = scope,
            connectionFactory = { url ->
                RelayConnection(url, RelayConnection.buildClient(appSettings.torEnabled.value))
            },
        )
        // On each Tor toggle, probe the reachable Orbot proxy (HTTP CONNECT preferred
        // over SOCKS to avoid the local DNS leak) and reconnect all relays.
        scope.launch {
            appSettings.torEnabled.collect { useTor ->
                val status = if (useTor) OrbotHelper.getStatus(context) else null
                val connected = status as? OrbotStatus.InstalledAndConnected
                pool.updateTransport(
                    RelayConnection.buildClient(
                        useTor = useTor,
                        proxyType = connected?.proxyType ?: java.net.Proxy.Type.HTTP,
                        torPort = connected?.port ?: 8118,
                    )
                )
            }
        }
        return pool
    }
}
