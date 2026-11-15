package social.tbone

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import social.tbone.BuildConfig
import social.tbone.di.ImageClientProvider
import social.tbone.calendar.CalendarAlarmScheduler
import social.tbone.calendar.CalendarRepository
import social.tbone.logging.CrashRecorder
import social.tbone.logging.FileLoggingTree
import social.tbone.logging.LOG_FILE_NAME
import social.tbone.notifications.createNotificationChannels
import social.tbone.nostr.identity.Phrase
import social.tbone.nostr.relay.RelayConnection
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
    @Inject lateinit var legacyCleanup: social.tbone.migration.LegacyMiniAppCleanup
    @Inject lateinit var nsfwCoilInterceptor: social.tbone.ai.NsfwCoilInterceptor
    @Inject lateinit var mediaScanCache: social.tbone.ai.MediaScanCache
    @Inject lateinit var aiEngine: social.tbone.ai.NsfwEngine
    @Inject lateinit var eventRepository: social.tbone.db.EventRepository
    @Inject lateinit var profileRepository: social.tbone.profile.ProfileRepository

    override fun onCreate() {
        super.onCreate()

        // Install Coil's ImageLoader synchronously using a dynamic Call.Factory
        // that delegates to ImageClientProvider.client (which starts as a strict
        // proxy-only Tor-guarded client until persisted settings load). Reading
        // the lightweight-cache preference mirror synchronously lets the single
        // persistent DiskCache come online on frame 0 so previously cached
        // avatars/images render immediately without a mid-startup singleton swap.
        val dynamicCallFactory = Call.Factory { request -> ImageClientProvider.client.newCall(request) }
        val cachePrefs = getSharedPreferences("tbone_cache_prefs", MODE_PRIVATE)
        val initialLightweight = cachePrefs.getBoolean("image_cache_lightweight", false)
        var installedLightweightCache = initialLightweight
        fun buildCoilLoader(lightweight: Boolean): ImageLoader =
            ImageLoader.Builder(this)
                .callFactory(dynamicCallFactory)
                .components { add(nsfwCoilInterceptor) }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(if (lightweight) 64L * 1024 * 1024 else 250L * 1024 * 1024)
                        .build()
                }
                .build()
        Coil.setImageLoader(buildCoilLoader(initialLightweight))

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

        // Upgrade cleanup is idempotent and retains its pending marker on failure.
        legacyCleanup.retry()

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

        // One-time cache hygiene on every cold start (Option A): prune
        // leaked transients older than 7 days + stale .part files. This is
        // I/O-light and idempotent; users see the "3 GB cache" shrink after
        // the next launch without any data loss.
        applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                social.tbone.util.CacheCleaner.cleanup(this@BonyApp)
            } catch (_: Exception) { }
            try {
                mediaScanCache.prune()
            } catch (_: Exception) { }
            runCatching { eventRepository.pruneOld() }
                .onFailure { Timber.w(it, "Startup event cache prune failed") }
            runCatching { profileRepository.pruneOld() }
                .onFailure { Timber.w(it, "Startup profile cache prune failed") }
        }

        // Keep Coil's HTTP transport in sync with Tor and persist any changes
        // to the lightweight-cache setting. Because Coil was already configured
        // with dynamicCallFactory + DiskCache synchronously above, normal
        // cold starts never tear down or replace the global ImageLoader.
        applicationScope.launch {
            appSettings.initialized.first { it }
            combine(appSettings.torEnabled, appSettings.imageCacheLightweight) { tor, lightweight -> tor to lightweight }
                .distinctUntilChanged()
                .collect { (useTor, lightweight) ->
                    val status = if (useTor) OrbotHelper.getStatus(this@BonyApp) else null
                    val connected = status as? OrbotStatus.InstalledAndConnected
                    val client = RelayConnection.buildClient(
                        useTor = useTor,
                        proxyType = connected?.proxyType ?: java.net.Proxy.Type.HTTP,
                        torPort = connected?.port ?: 8118,
                    )
                    ImageClientProvider.update(client, useTor)
                    if (installedLightweightCache != lightweight) {
                        installedLightweightCache = lightweight
                        cachePrefs.edit().putBoolean("image_cache_lightweight", lightweight).apply()
                        Coil.setImageLoader(buildCoilLoader(lightweight))
                    }
                }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_RUNNING_LOW) {
            aiEngine.unloadAll()
        }
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            runCatching { Coil.imageLoader(this).memoryCache?.clear() }
        }
    }
}
