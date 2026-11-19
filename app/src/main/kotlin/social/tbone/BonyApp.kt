package social.tbone

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.tbone.BuildConfig
import social.tbone.account.AccountRepository
import social.tbone.cache.ImageLoaderRegistry
import social.tbone.cache.MediaCacheManager
import social.tbone.di.ImageClientProvider
import social.tbone.calendar.CalendarAlarmScheduler
import social.tbone.calendar.CalendarRepository
import social.tbone.logging.CrashRecorder
import social.tbone.logging.FileLoggingTree
import social.tbone.logging.LOG_FILE_NAME
import social.tbone.notifications.createNotificationChannels
import social.tbone.nostr.identity.Phrase
import social.tbone.nostr.relay.RelayConnection
import social.tbone.reactions.EngagementCacheRepository
import social.tbone.settings.AppSettings
import social.tbone.settings.OrbotHelper
import social.tbone.settings.OrbotStatus
import social.tbone.settings.UiTheme
import social.tbone.updates.UpdateManager
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.parseHexColor
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class BonyApp : Application() {

    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var applicationScope: CoroutineScope
    @Inject lateinit var calendarRepository: CalendarRepository
    @Inject lateinit var calendarScheduler: CalendarAlarmScheduler
    @Inject lateinit var updateManager: UpdateManager
    @Inject lateinit var geohashPinnedMonitor: social.tbone.ui.toolbox.geohash.GeohashPinnedMonitor
    @Inject lateinit var noteWrites: social.tbone.notes.NoteWriteCoordinator
    @Inject lateinit var dataWiper: social.tbone.security.DataWiper
    @Inject lateinit var toolboxSecurity: social.tbone.ui.toolbox.ToolboxSecurity
    @Inject lateinit var imageLoaderRegistry: ImageLoaderRegistry
    @Inject lateinit var mediaCacheManager: MediaCacheManager
    @Inject lateinit var mediaScanCache: social.tbone.ai.MediaScanCache
    @Inject lateinit var aiEngine: social.tbone.ai.NsfwEngine
    @Inject lateinit var eventRepository: social.tbone.db.EventRepository
    @Inject lateinit var profileRepository: social.tbone.profile.ProfileRepository
    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var engagementCache: EngagementCacheRepository

    override fun onCreate() {
        super.onCreate()

        // Install BOTH isolated Coil ImageLoaders (dedicated avatar_cache +
        // feed image_cache) and configure Media3's video/audio SimpleCache
        // synchronously on frame 0 using the persisted CachePreset mirror.
        val initialPreset = imageLoaderRegistry.readPersistedPreset()
        imageLoaderRegistry.initialize(initialPreset)
        mediaCacheManager.applyPreset(initialPreset)

        val logFile = File(filesDir, "logs/$LOG_FILE_NAME")

        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        Timber.plant(FileLoggingTree(logFile))
        Thread.setDefaultUncaughtExceptionHandler(
            CrashRecorder(
                crashFile = File(filesDir, "logs/last-crash.log"),
                previous = Thread.getDefaultUncaughtExceptionHandler(),
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            ),
        )

        createNotificationChannels(this)
        Phrase.init(this)

        // A previously requested wipe must continue after process death. Its
        // durable marker closes the note gate before this application starts.
        val pending = noteWrites.pendingWipes()
        if (pending.isNotEmpty()) geohashPinnedMonitor.pauseForWipe()
        when {
            social.tbone.notes.NoteWriteCoordinator.WipeKind.UNKNOWN in pending -> Unit // Preserve data; do not guess a destructive action.
            social.tbone.notes.NoteWriteCoordinator.WipeKind.APP in pending -> dataWiper.wipeAndRestart()
            social.tbone.notes.NoteWriteCoordinator.WipeKind.TOOLBOX in pending -> toolboxSecurity.wipeToolbox(discreet = true)
        }

        // Keep fully-offline calendar alarms in sync with the event list:
        // whenever events change (add/edit/delete), re-schedule reminders.
        applicationScope.launch {
            calendarRepository.events.collect { events ->
                calendarScheduler.rescheduleAll(events)
            }
        }

        // Apply the persisted theme mode + accent as soon as DataStore emits,
        // strictly on the Main dispatcher so Compose Snapshot mutableStateOf
        // values are never mutated from a background IO thread.
        // When the user has chosen Youniversal we leave BonyColors alone — the
        // Youniversal composition will mirror its scheme into BonyColors via
        // ThemeHost's LaunchedEffect so legacy BonyColors usages stay in sync.
        applicationScope.launch {
            combine(appSettings.uiTheme, appSettings.themeMode, appSettings.accentColor) { uiTheme, mode, accent -> Triple(uiTheme, mode, accent) }
                .distinctUntilChanged()
                .collect { (uiTheme, mode, accent) ->
                    if (uiTheme == UiTheme.BONY) {
                        withContext(Dispatchers.Main.immediate) {
                            BonyColors.applyMode(mode)
                            BonyColors.setAccent(parseHexColor(accent))
                        }
                    }
                }
        }

        // One-time cache hygiene on every cold start: prune leaked transients
        // and trim Room caches according to the active CachePreset while keeping
        // followed profiles and every local account's feed + own notes protected.
        applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            appSettings.initialized.first { it }
            val preset = appSettings.cachePreset.value
            val ownAccounts = runCatching { accountRepository.accounts.first().map { it.pubkey } }
                .getOrDefault(emptyList())
            try {
                social.tbone.util.CacheCleaner.cleanup(this@BonyApp)
            } catch (_: Exception) { }
            try {
                mediaScanCache.prune()
            } catch (_: Exception) { }
            runCatching { eventRepository.pruneOld(preset.maxCachedEvents, ownAccounts) }
                .onFailure { Timber.w(it, "Startup event cache prune failed") }
            runCatching { profileRepository.pruneOld(preset.maxCachedProfiles) }
                .onFailure { Timber.w(it, "Startup profile cache prune failed") }
            runCatching { engagementCache.prune(preset.maxEngagementRows) }
                .onFailure { Timber.w(it, "Startup engagement cache prune failed") }
        }

        // Keep HTTP media transport in sync with Tor (using media-tuned timeouts)
        // and keep Coil + Media3 caches in sync with CachePreset changes.
        applicationScope.launch {
            appSettings.initialized.first { it }
            combine(appSettings.torEnabled, appSettings.cachePreset) { tor, preset -> tor to preset }
                .distinctUntilChanged()
                // The first emission is the same state onCreate already installed
                // synchronously (readPersistedPreset + startupRequiresTor). Acting on
                // it rebuilt both Coil loaders and shut the freshly installed ones
                // down — cancelling every image request in flight during the first
                // frame, then re-issuing them — for no change in configuration.
                .drop(1)
                .collect { (useTor, preset) ->
                    val status = if (useTor) OrbotHelper.getStatus(this@BonyApp) else null
                    val connected = status as? OrbotStatus.InstalledAndConnected
                    val client = RelayConnection.buildMediaClient(
                        useTor = useTor,
                        proxyType = connected?.proxyType ?: java.net.Proxy.Type.HTTP,
                        torPort = connected?.port ?: 8118,
                    )
                    ImageClientProvider.update(client, useTor)
                    imageLoaderRegistry.applyPreset(preset)
                    mediaCacheManager.applyPreset(preset)
                }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_RUNNING_LOW) {
            aiEngine.unloadAll()
        }
        imageLoaderRegistry.onTrimMemory(level)
    }
}
