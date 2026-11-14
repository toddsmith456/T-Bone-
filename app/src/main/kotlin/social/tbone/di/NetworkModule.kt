package social.tbone.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import social.tbone.nostr.relay.RelayConnection
import social.tbone.nostr.relay.RelayPool
import social.tbone.settings.AppSettings
import social.tbone.settings.OrbotHelper
import social.tbone.settings.OrbotStatus
import timber.log.Timber
import okhttp3.OkHttpClient
import javax.inject.Singleton
import javax.inject.Qualifier
import social.tbone.account.AccountRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GlobalFeedPool

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = ImageClientProvider.client

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, throwable ->
                    Timber.e(throwable, "Uncaught exception in applicationScope")
                },
        )

    @Provides
    @Singleton
    fun provideRelayPool(
        scope: CoroutineScope,
        appSettings: AppSettings,
        @ApplicationContext context: Context,
        accountRepository: AccountRepository,
    ): RelayPool {
        val pool = createPool(scope, appSettings, context)
        scope.launch {
            appSettings.initialized.first { it }
            accountRepository.activeAccount.map { it?.effectiveRelays().orEmpty() }
                .distinctUntilChanged().collect { pool.setPersonalRelays(it) }
        }
        return pool
    }

    @Provides
    @Singleton
    @GlobalFeedPool
    fun provideGlobalFeedPool(scope: CoroutineScope, appSettings: AppSettings, @ApplicationContext context: Context): RelayPool =
        createPool(scope, appSettings, context)

    private fun createPool(scope: CoroutineScope, appSettings: AppSettings, context: Context): RelayPool {
        val pool = RelayPool(
            scope = scope,
            connectionFactory = { url ->
                val tor = startupRequiresTor(appSettings.initialized.value, appSettings.torEnabled.value)
                RelayConnection(url, RelayConnection.buildClient(
                    useTor = tor,
                    bypassProxy = RelayConnection.isLocalRelayUrl(url),
                )).guardedBy(TorCallPolicy.guard, tor)
            },
        )
        // Android 8 (API 26) Fix: startup Tor-guard race.
        //
        // Settings are read asynchronously, so on a slow API 26 device the first
        // collector can observe torEnabled=false while `initialized` is still
        // false. Collecting the two flows independently meant the pool could be
        // rebuilt for "no Tor" during that window. Combining them means a
        // transport change is only ever applied once settings are known, and the
        // first emission after load is correct by construction.
        //
        // Note: this deliberately does NOT block inside a @Provides function
        // (`initialized.first()`), which would deadlock Hilt's single-threaded
        // synchronous injection by blocking the thread that loads the settings.
        scope.launch {
            combine(appSettings.initialized, appSettings.torEnabled) { ready, tor -> ready to tor }
                .distinctUntilChanged()
                .collect { (ready, useTor) ->
                    // Before settings load the factory already fails closed to Tor
                    // (startupRequiresTor), so never rebuild from a stale value.
                    if (!ready) return@collect
                    val status = if (useTor) OrbotHelper.getStatus(context) else null
                    val connected = status as? OrbotStatus.InstalledAndConnected
                    // Build one client per transport role (remote vs local loopback)
                    // rather than allocating a new client per relay URL.
                    val remoteClient = RelayConnection.buildClient(
                        useTor = useTor,
                        proxyType = connected?.proxyType ?: java.net.Proxy.Type.HTTP,
                        torPort = connected?.port ?: 8118,
                        bypassProxy = false,
                    )
                    val localClient = RelayConnection.buildClient(
                        useTor = false,
                        bypassProxy = true,
                    )
                    pool.updateConnectionFactory { url ->
                        val client = if (RelayConnection.isLocalRelayUrl(url)) localClient else remoteClient
                        RelayConnection(url, client).guardedBy(TorCallPolicy.guard, useTor)
                    }
                }
        }
        return pool
    }
}

/** Unknown persisted transport mode is treated as Tor until settings load. */
internal fun startupRequiresTor(settingsReady: Boolean, torEnabled: Boolean): Boolean =
    !settingsReady || torEnabled
