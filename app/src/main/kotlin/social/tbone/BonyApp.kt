package social.tbone

import android.app.Application
import coil.Coil
import coil.ImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import social.tbone.BuildConfig
import social.tbone.di.ImageClientProvider
import social.tbone.calendar.CalendarAlarmScheduler
import social.tbone.calendar.CalendarRepository
import social.tbone.logging.FileLoggingTree
import social.tbone.logging.LOG_FILE_NAME
import social.tbone.notifications.createNotificationChannels
import social.tbone.nostr.identity.Phrase
import social.tbone.nostr.relay.RelayConnection
import social.tbone.settings.AppSettings
import social.tbone.settings.OrbotHelper
import social.tbone.settings.OrbotStatus
import social.tbone.settings.UiTheme
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

    override fun onCreate() {
        super.onCreate()

        val logFile = File(filesDir, "logs/$LOG_FILE_NAME")

        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        Timber.plant(FileLoggingTree(logFile))

        createNotificationChannels(this)
        Phrase.init(this)

        // Keep fully-offline calendar alarms in sync with the event list:
        // whenever events change (add/edit/delete), re-schedule reminders.
        applicationScope.launch {
            calendarRepository.events.collect { events ->
                calendarScheduler.rescheduleAll(events)
            }
        }

        // Apply the persisted theme mode + accent as soon as DataStore emits,
        // so the very first frame renders with the user's palette.
        // When the user has chosen Youniversal we leave BonyColors alone — the
        // Youniversal composition will mirror its scheme into BonyColors via
        // ThemeHost's LaunchedEffect so legacy BonyColors usages stay in sync.
        applicationScope.launch {
            combine(appSettings.uiTheme, appSettings.themeMode, appSettings.accentColor) { uiTheme, mode, accent -> Triple(uiTheme, mode, accent) }
                .collect { (uiTheme, mode, accent) ->
                    if (uiTheme == UiTheme.BONY) {
                        BonyColors.applyMode(mode)
                        BonyColors.setAccent(parseHexColor(accent))
                    }
                }
        }

        // Keep Coil's image loader in sync with the Tor toggle so avatar/banner/image
        // fetches are routed through the same proxy as relay WebSockets. The same
        // client is shared with the image viewer's download path (ImageClientProvider).
        applicationScope.launch {
            appSettings.torEnabled.collect { useTor ->
                val status = if (useTor) OrbotHelper.getStatus(this@BonyApp) else null
                val connected = status as? OrbotStatus.InstalledAndConnected
                val client = RelayConnection.buildClient(
                    useTor = useTor,
                    proxyType = connected?.proxyType ?: java.net.Proxy.Type.HTTP,
                    torPort = connected?.port ?: 8118,
                )
                ImageClientProvider.update(client)
                Coil.setImageLoader(
                    ImageLoader.Builder(this@BonyApp)
                        .okHttpClient(client)
                        .build()
                )
            }
        }
    }
}
