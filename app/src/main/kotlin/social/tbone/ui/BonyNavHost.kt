package social.tbone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import social.tbone.account.AccountRepository
import social.tbone.notifications.DeepLinkHandler
import social.tbone.security.AppLockManager
import social.tbone.ui.components.BonyBottomBar
import social.tbone.ui.components.LocalUiDeclutter
import social.tbone.ui.components.BottomTab
import social.tbone.ui.compose.ComposeScreen
import social.tbone.ui.lock.PinLockScreen
import social.tbone.ui.lock.PinSetupScreen
import social.tbone.ui.media.LocalAnimatedAvatars
import social.tbone.ui.media.LocalAvatarMode
import social.tbone.ui.media.LocalImageLoadMode
import social.tbone.ui.media.LocalVideoPlaybackMode
import social.tbone.ui.media.LocalVideoQuality
import social.tbone.ui.media.LocalVideoThumbnails
import social.tbone.ui.media.MediaSettingsViewModel
import social.tbone.ui.notifications.NotificationsScreen
import social.tbone.ui.notes.ChecklistEditorScreen
import social.tbone.ui.notes.NoteEditorScreen
import social.tbone.ui.notes.NotesInfoScreen
import social.tbone.ui.notes.FolderContentsScreen
import social.tbone.ui.notes.NotesScreen
import social.tbone.ui.toolbox.ToolboxScreen
import social.tbone.ui.toolbox.UpdateCenterScreen
import social.tbone.ui.toolbox.ToolboxSettingsScreen
import social.tbone.ui.toolbox.VoiceRecorderScreen
import social.tbone.ui.toolbox.WalletToolScreen
import social.tbone.ui.toolbox.webxdc.XdcPlayerScreen
import social.tbone.ui.toolbox.webxdc.XdcScreen
import social.tbone.ui.toolbox.calendar.CalendarEventEditorScreen
import social.tbone.ui.toolbox.calendar.CalendarScreen
import social.tbone.ui.toolbox.geohash.GeohashChannelScreen
import social.tbone.ui.toolbox.geohash.GeohashChatScreen
import social.tbone.ui.feed.FeedScreen
import social.tbone.ui.bookmarks.BookmarksScreen
import social.tbone.ui.hashtag.HashtagFeedScreen
import social.tbone.ui.onboarding.OnboardingScreen
import social.tbone.ui.profile.ProfileScreen
import social.tbone.ui.profile.VerifyIdentityScreen
import social.tbone.ui.search.SearchScreen
import social.tbone.ui.settings.AccountManagementScreen
import social.tbone.ui.settings.RelayManagementScreen
import social.tbone.ui.settings.ContentFiltersScreen
import social.tbone.ui.settings.BlossomSettingsScreen
import social.tbone.ui.settings.OfflineListScreen
import social.tbone.ui.lists.FollowListsScreen
import social.tbone.ui.settings.ScreenTimeSettingsScreen
import social.tbone.ui.settings.SettingsScreen
import social.tbone.ui.profile.ProfileEditScreen
import social.tbone.ui.thread.ThreadScreen
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.ThemeViewModel
import javax.inject.Inject

private const val ROUTE_ONBOARDING        = "onboarding"
private const val ROUTE_FEED              = "feed"
private const val ROUTE_THREAD            = "thread/{eventId}"
private const val ROUTE_COMPOSE           = "compose?replyToId={replyToId}&quoteToId={quoteToId}&quoteFolderId={quoteFolderId}&draft={draft}&audioPath={audioPath}"
private const val ROUTE_SETTINGS          = "settings"
private const val ROUTE_CONTENT_FILTERS   = "content_filters"
private const val ROUTE_BLOSSOM           = "blossom_settings"
private const val ROUTE_OFFLINE_LIST      = "offline_list/{type}"
private const val ROUTE_FOLLOW_LISTS        = "follow_lists"
private const val ROUTE_SCREEN_TIME       = "screen_time_settings"
private const val ROUTE_PROFILE_EDIT      = "profile_edit"
private const val ROUTE_PROFILE           = "profile/{pubkey}"
private const val ROUTE_VERIFY_IDENTITY   = "verify/{pubkey}"
private const val ROUTE_ADD_ACCOUNT       = "add_account"
private const val ROUTE_ACCOUNT_MANAGEMENT = "account_management"
private const val ROUTE_RELAY_MANAGEMENT  = "relay_management"
private const val ROUTE_NOTIFICATIONS     = "notifications"
private const val ROUTE_NOTES              = "notes"
private const val ROUTE_BOOKMARKS          = "bookmarks"
private const val ROUTE_BOOKMARK_FOLDER    = "bookmarks/{folderId}"
private const val ROUTE_NOTE_EDITOR        = "note_editor?id={id}&folder={folder}"
private const val ROUTE_CHECKLIST_EDITOR   = "checklist_editor?id={id}&folder={folder}"
private const val ROUTE_FOLDER             = "folder/{id}?name={name}"
private const val ROUTE_NOTES_INFO         = "notes_info"
private const val ROUTE_TOOLBOX            = "toolbox"
private const val ROUTE_VOICE              = "voice"
private const val ROUTE_WALLET             = "wallet"
private const val ROUTE_UPDATE_CENTER      = "update_center"
private const val ROUTE_TOOLBOX_SETTINGS = "toolbox_settings"
private const val ROUTE_XDC             = "webxdc"
private const val ROUTE_XDC_PLAYER      = "webxdc_player/{appId}"
private const val ROUTE_GEOHASH          = "geohash"
private const val ROUTE_GEOHASH_CHAT     = "geohash_chat?code={code}"
private const val ROUTE_CALENDAR         = "calendar"
private const val ROUTE_CALENDAR_EVENT   = "calendar_event?dateMillis={dateMillis}&id={id}"
private const val ROUTE_SEARCH            = "search?query={query}&autoOpen={autoOpen}"
private const val ROUTE_HASHTAG           = "hashtag/{tag}"
private const val ROUTE_PIN_SETUP         = "pin_setup?duress={duress}"

// ── Decoy Notepad routes ─────────────────────────────────────────────────────
private const val ROUTE_DECOY_NOTES            = "decoy_notes"
private const val ROUTE_DECOY_NOTE_EDITOR      = "decoy_note_editor?id={id}&folder={folder}"
private const val ROUTE_DECOY_CHECKLIST_EDITOR = "decoy_checklist_editor?id={id}&folder={folder}"
private const val ROUTE_DECOY_FOLDER           = "decoy_folder/{id}?name={name}"
private const val ROUTE_DECOY_NOTES_INFO       = "decoy_notes_info"

@Composable
fun BonyNavHost() {
    val context = LocalContext.current
    val viewModel: StartupViewModel = hiltViewModel()
    val lockViewModel: LockViewModel = hiltViewModel()
    val mediaSettings: MediaSettingsViewModel = hiltViewModel()
    val chromeSettings: ThemeViewModel = hiltViewModel()
    val stealthGate: StealthGateViewModel = hiltViewModel()
    val notepadFrontEnabled by stealthGate.notepadFrontEnabled.collectAsStateWithLifecycle()
    val decoyUnlocked by stealthGate.decoyUnlocked.collectAsStateWithLifecycle()
    val isStealthLocked = notepadFrontEnabled && !decoyUnlocked

    // Stealth: show only the decoy Notepad — no feed, no toolbox, no settings.
    if (isStealthLocked) {
        DecoyNavHost(onUnlock = { stealthGate.unlock() })
        return
    }
    val uiDeclutter by chromeSettings.uiDeclutterEnabled.collectAsStateWithLifecycle()
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()
    val isLocked by lockViewModel.isLocked.collectAsStateWithLifecycle()
    val imageLoadMode by mediaSettings.imageLoadMode.collectAsStateWithLifecycle()
    val videoQuality by mediaSettings.videoQuality.collectAsStateWithLifecycle()
    val videoPlaybackMode by mediaSettings.videoPlaybackMode.collectAsStateWithLifecycle()
    val videoThumbnails by mediaSettings.videoThumbnails.collectAsStateWithLifecycle()
    val avatarMode by mediaSettings.avatarMode.collectAsStateWithLifecycle()
    val avatarAnimated by mediaSettings.avatarAnimated.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        viewModel.openThread.collect { eventId -> navController.navigate("thread/$eventId") }
    }
    LaunchedEffect(Unit) {
        viewModel.openProfile.collect { pubkey -> navController.navigate("profile/$pubkey") }
    }
    LaunchedEffect(startupState) {
        if (startupState is StartupState.Ready && !(startupState as StartupState.Ready).hasAccount) {
            navController.navigate(ROUTE_ONBOARDING) { popUpTo(0) { inclusive = true } }
        }
    }

    // Locked: draw ONLY the lock screen — the app content is never composed,
    // so a returning user can never see a flash of the previous screen.
    if (isLocked) {
        PinLockScreen(onUnlocked = { lockViewModel.unlock() })
        return
    }

    when (startupState) {
        StartupState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BonyColors.Accent, strokeWidth = 1.dp)
            }
        }
        is StartupState.Ready -> {
            val start = if ((startupState as StartupState.Ready).hasAccount) ROUTE_FEED else ROUTE_ONBOARDING

            CompositionLocalProvider(
                LocalUiDeclutter provides uiDeclutter,
                LocalImageLoadMode provides imageLoadMode,
                LocalVideoQuality provides videoQuality,
                LocalVideoPlaybackMode provides videoPlaybackMode,
                LocalVideoThumbnails provides videoThumbnails,
                LocalAvatarMode provides avatarMode,
                LocalAnimatedAvatars provides avatarAnimated,
            ) {
                NavHost(navController = navController, startDestination = start) {
                    composable(ROUTE_ONBOARDING) {
                        OnboardingScreen(
                            onAccountAdded = {
                                navController.navigate(ROUTE_FEED) {
                                    popUpTo(ROUTE_ONBOARDING) { inclusive = true }
                                }
                            }
                        )
                    }
                    composable(ROUTE_FEED) {
                        FeedScreen(
                            onThreadClick = { eventId -> navController.navigate("thread/$eventId") },
                            onComposeClick = { navController.navigate("compose") },
                            onSettingsClick = { navController.navigate(ROUTE_SETTINGS) },
                            onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                            onHashtagClick = { tag -> navController.navigate("hashtag/" + Uri.encode(tag)) },
                            onRelayManagementClick = { navController.navigate(ROUTE_RELAY_MANAGEMENT) },
                            onSearchClick = { navController.navigate("search?query=&autoOpen=false") },
                            onNotificationsClick = { navController.switchToTab(BottomTab.NOTIF) },
                            onToolboxClick = { navController.switchToTab(BottomTab.TOOLS) },
                            onOpenGeohash = { code ->
                                navController.navigate("geohash_chat?code=" + Uri.encode(code))
                            },
                            onReplyClick = { event -> navController.navigate("compose?replyToId=${event.id}") },
                            onQuoteClick = { event -> navController.navigate("compose?quoteToId=${event.id}") },
                        )
                    }
                composable(ROUTE_TOOLBOX) {
                    // Bottom bar stays visible here, exactly as on the home tab.
                    WithBottomBar(current = BottomTab.TOOLS, navController = navController) {
                        ToolboxScreen(
                            onBack = { navController.switchToTab(BottomTab.HOME) },
                            onNotes = { navController.navigate(ROUTE_NOTES) },
                            onBookmarks = { navController.navigate(ROUTE_BOOKMARKS) },
                            onVoiceRecorder = { navController.navigate(ROUTE_VOICE) },
                            onGeohashChannels = { navController.navigate(ROUTE_GEOHASH) },
                            onCalendar = { navController.navigate(ROUTE_CALENDAR) },
                            onWallet = { navController.navigate(ROUTE_WALLET) },
                            onUpdateCenter = { navController.navigate(ROUTE_UPDATE_CENTER) },
                            onWebxdc = { navController.navigate(ROUTE_XDC) },
                            onFollowLists = { navController.navigate(ROUTE_FOLLOW_LISTS) },
                            onOpenXdcTool = { appId ->
                                navController.navigate("webxdc_player/" + Uri.encode(appId))
                            },
                            onToolboxSettings = { navController.navigate(ROUTE_TOOLBOX_SETTINGS) },
                        )
                    }
                }
                composable(ROUTE_BOOKMARKS) {
                    WithBottomBar(current = BottomTab.TOOLS, navController = navController) {
                        BookmarksScreen(
                            onBack = { navController.popBackStack() },
                            onOpenFolder = { id -> navController.navigate("bookmarks/${Uri.encode(id)}") },
                            onOpenEventSearch = { nevent ->
                                navController.navigate("search?query=${Uri.encode("nostr:$nevent")}&autoOpen=true")
                            },
                            onQuoteFolder = { id -> navController.navigate("compose?quoteFolderId=${Uri.encode(id)}") },
                        )
                    }
                }
                composable(
                    ROUTE_BOOKMARK_FOLDER,
                    arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
                ) { entry ->
                    val folderId = entry.arguments?.getString("folderId") ?: ""
                    WithBottomBar(current = BottomTab.TOOLS, navController = navController) {
                        BookmarksScreen(
                            folderId = folderId,
                            onBack = { navController.popBackStack() },
                            onOpenFolder = { id -> navController.navigate("bookmarks/${Uri.encode(id)}") },
                            onOpenEventSearch = { nevent ->
                                navController.navigate("search?query=${Uri.encode("nostr:$nevent")}&autoOpen=true")
                            },
                            onQuoteFolder = { id -> navController.navigate("compose?quoteFolderId=${Uri.encode(id)}") },
                        )
                    }
                }
                composable(ROUTE_TOOLBOX_SETTINGS) {
                    ToolboxSettingsScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_XDC) {
                    // The tool keeps the shared bottom bar, like the other toolbox screens.
                    WithBottomBar(current = BottomTab.TOOLS, navController = navController) {
                        XdcScreen(
                            onBack = { navController.popBackStack() },
                            onOpenApp = { appId ->
                                navController.navigate("webxdc_player/" + Uri.encode(appId))
                            },
                        )
                    }
                }
                composable(
                    ROUTE_XDC_PLAYER,
                    arguments = listOf(navArgument("appId") { type = NavType.StringType }),
                ) {
                    // Full screen on purpose: a mini app needs every pixel and
                    // must not sit under the app's bottom navigation.
                    XdcPlayerScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_WALLET) {
                    // Keep the shared navigation bar below the wallet relay
                    // notifications and above Android's navigation buttons.
                    WithBottomBar(current = BottomTab.TOOLS, navController = navController) {
                        WalletToolScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(ROUTE_UPDATE_CENTER) {
                    WithBottomBar(current = BottomTab.TOOLS, navController = navController) {
                        UpdateCenterScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(ROUTE_GEOHASH) {
                    GeohashChannelScreen(
                        onBack = { navController.popBackStack() },
                        onOpenChannel = { code ->
                            navController.navigate("geohash_chat?code=" + Uri.encode(code))
                        },
                    )
                }
                composable(
                    ROUTE_GEOHASH_CHAT,
                    arguments = listOf(navArgument("code") { type = NavType.StringType; defaultValue = "" }),
                ) { entry ->
                    val code = entry.arguments?.getString("code") ?: ""
                    GeohashChatScreen(
                        code = code,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_CALENDAR) {
                    CalendarScreen(
                        onBack = { navController.popBackStack() },
                        onNewEvent = { dateMillis ->
                            navController.navigate("calendar_event?dateMillis=$dateMillis&id=")
                        },
                        onEditEvent = { id ->
                            navController.navigate("calendar_event?dateMillis=-1&id=" + Uri.encode(id))
                        },
                    )
                }
                composable(
                    ROUTE_CALENDAR_EVENT,
                    arguments = listOf(
                        navArgument("dateMillis") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val id = entry.arguments?.getString("id") ?: ""
                    val dateMillis = entry.arguments?.getLong("dateMillis") ?: -1L
                    CalendarEventEditorScreen(
                        id = id,
                        dateMillis = dateMillis,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_NOTES) {
                    NotesScreen(
                        onBack = { navController.popBackStack() },
                        onNewNote = { navController.navigate("note_editor?id=") },
                        onOpenNote = { id -> navController.navigate("note_editor?id=" + Uri.encode(id)) },
                        onNewChecklist = { navController.navigate("checklist_editor?id=&folder=") },
                        onOpenChecklist = { id -> navController.navigate("checklist_editor?id=" + Uri.encode(id) + "&folder=") },
                        onOpenFolder = { folderId, name ->
                            navController.navigate(
                                "folder/" + Uri.encode(folderId) + "?name=" + Uri.encode(name),
                            )
                        },
                        onInfo = { navController.navigate(ROUTE_NOTES_INFO) },
                        onShare = { text ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share note"))
                        },
                        onPublish = { text ->
                            navController.navigate("compose?draft=" + Uri.encode(text))
                        },
                    )
                }
                composable(
                    ROUTE_NOTE_EDITOR,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType; defaultValue = "" },
                        navArgument("folder") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val id = entry.arguments?.getString("id") ?: ""
                    val folder = entry.arguments?.getString("folder")?.takeIf { it.isNotBlank() }
                    NoteEditorScreen(
                        id = id,
                        folderId = folder,
                        onBack = { navController.popBackStack() },
                        onShare = { text ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share note"))
                        },
                        onPublish = { text ->
                            navController.navigate("compose?draft=" + Uri.encode(text))
                        },
                    )
                }
                composable(
                    ROUTE_CHECKLIST_EDITOR,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType; defaultValue = "" },
                        navArgument("folder") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val id = entry.arguments?.getString("id") ?: ""
                    val folder = entry.arguments?.getString("folder")?.takeIf { it.isNotBlank() }
                    ChecklistEditorScreen(
                        id = id,
                        folderId = folder,
                        onBack = { navController.popBackStack() },
                        onShare = { text ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share checklist"))
                        },
                        onPublish = { text ->
                            navController.navigate("compose?draft=" + Uri.encode(text))
                        },
                    )
                }
                composable(
                    ROUTE_FOLDER,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType; defaultValue = "folder" },
                    ),
                ) { entry ->
                    val folderId = entry.arguments?.getString("id") ?: ""
                    val folderName = entry.arguments?.getString("name") ?: "folder"
                    FolderContentsScreen(
                        folderId = folderId,
                        folderName = folderName,
                        onBack = { navController.popBackStack() },
                        onNewNote = { navController.navigate("note_editor?id=&folder=" + Uri.encode(folderId)) },
                        onOpenNote = { id -> navController.navigate("note_editor?id=" + Uri.encode(id) + "&folder=" + Uri.encode(folderId)) },
                        onNewChecklist = { navController.navigate("checklist_editor?id=&folder=" + Uri.encode(folderId)) },
                        onOpenChecklist = { id -> navController.navigate("checklist_editor?id=" + Uri.encode(id) + "&folder=" + Uri.encode(folderId)) },
                        onShare = { text ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share"))
                        },
                    )
                }
                composable(ROUTE_NOTES_INFO) {
                    NotesInfoScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_VOICE) {
                    VoiceRecorderScreen(
                        onBack = { navController.popBackStack() },
                        onUploadToWrite = { path ->
                            navController.navigate("compose?audioPath=" + Uri.encode(path))
                        },
                    )
                }
                composable(ROUTE_NOTIFICATIONS) {
                    // Bottom bar stays visible here, exactly as on the home tab.
                    WithBottomBar(current = BottomTab.NOTIF, navController = navController) {
                        NotificationsScreen(
                            onBack = { navController.switchToTab(BottomTab.HOME) },
                            onThreadClick = { eventId -> navController.navigate("thread/$eventId") },
                            onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                            onReplyClick = { event -> navController.navigate("compose?replyToId=${event.id}") },
                            onOpenCalendarEvent = { id ->
                                navController.navigate("calendar_event?dateMillis=-1&id=" + Uri.encode(id))
                            },
                        )
                    }
                }
                    composable(ROUTE_THREAD) {
                        ThreadScreen(
                            onBack = { navController.popBackStack() },
                            onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                            onThreadClick = { eventId -> navController.navigate("thread/$eventId") },
                            onHashtagClick = { tag -> navController.navigate("hashtag/" + Uri.encode(tag)) },
                            onReplyClick = { event -> navController.navigate("compose?replyToId=${event.id}") },
                            onQuoteClick = { event -> navController.navigate("compose?quoteToId=${event.id}") },
                        )
                    }
                    composable(
                        ROUTE_COMPOSE,
                        arguments = listOf(
                            navArgument("replyToId") { type = NavType.StringType; defaultValue = "" },
                            navArgument("quoteToId") { type = NavType.StringType; defaultValue = "" },
                            navArgument("quoteFolderId") { type = NavType.StringType; defaultValue = "" },
                            navArgument("draft") { type = NavType.StringType; defaultValue = "" },
                            navArgument("audioPath") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) {
                        ComposeScreen(onBack = { navController.popBackStack() })
                    }
                    composable(ROUTE_CONTENT_FILTERS) {
                        ContentFiltersScreen(
                            onBack = { navController.popBackStack() },
                            onScreenTime = { navController.navigate(ROUTE_SCREEN_TIME) },
                        )
                    }
                    composable(ROUTE_BLOSSOM) {
                        BlossomSettingsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        ROUTE_OFFLINE_LIST,
                        arguments = listOf(navArgument("type") { type = NavType.StringType }),
                    ) {
                        OfflineListScreen(onBack = { navController.popBackStack() })
                    }
                    composable(ROUTE_SCREEN_TIME) {
                        ScreenTimeSettingsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(ROUTE_PROFILE_EDIT) {
                        ProfileEditScreen(onBack = { navController.popBackStack() })
                    }
                    composable(ROUTE_FOLLOW_LISTS) {
                        FollowListsScreen(
                            onBack = { navController.popBackStack() },
                            onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                        )
                    }
                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onAddAccount = { navController.navigate(ROUTE_ADD_ACCOUNT) },
                            onAccountManagement = { navController.navigate(ROUTE_ACCOUNT_MANAGEMENT) },
                            onRelayManagement = { navController.navigate(ROUTE_RELAY_MANAGEMENT) },
                            onBlossom = { navController.navigate(ROUTE_BLOSSOM) },
                            onOfflineList = { type -> navController.navigate("offline_list/$type") },
                            onFollowLists = { navController.navigate(ROUTE_FOLLOW_LISTS) },
                            onProfileEdit = { navController.navigate(ROUTE_PROFILE_EDIT) },
                            onContentFilters = { navController.navigate(ROUTE_CONTENT_FILTERS) },
                            onPinSetup = { navController.navigate("pin_setup?duress=false") },
                            onDuressPinSetup = { navController.navigate("pin_setup?duress=true") },
                        )
                    }
                    composable(
                        ROUTE_PIN_SETUP,
                        arguments = listOf(
                            navArgument("duress") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) { entry ->
                        val duress = entry.arguments?.getBoolean("duress") ?: false
                        PinSetupScreen(
                            onDone = { navController.popBackStack() },
                            onBack = { navController.popBackStack() },
                            isDuress = duress,
                        )
                    }
                    composable(ROUTE_RELAY_MANAGEMENT) {
                        RelayManagementScreen(onBack = { navController.popBackStack() })
                    }
                    composable(ROUTE_ACCOUNT_MANAGEMENT) {
                        AccountManagementScreen(
                            onBack = { navController.popBackStack() },
                            onAddAccount = { navController.navigate(ROUTE_ADD_ACCOUNT) },
                        )
                    }
                    composable(ROUTE_ADD_ACCOUNT) {
                        OnboardingScreen(
                            onAccountAdded = {
                                navController.navigate(ROUTE_FEED) {
                                    popUpTo(ROUTE_FEED) { inclusive = false }
                                }
                            }
                        )
                    }
                    composable(ROUTE_PROFILE) {
                        ProfileScreen(
                            onBack = { navController.popBackStack() },
                            onVerifyClick = { pubkey -> navController.navigate("verify/$pubkey") },
                            onThreadClick = { eventId -> navController.navigate("thread/$eventId") },
                            onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                            onHashtagClick = { tag -> navController.navigate("hashtag/" + Uri.encode(tag)) },
                            onReplyClick = { event -> navController.navigate("compose?replyToId=${event.id}") },
                            onQuoteClick = { event -> navController.navigate("compose?quoteToId=${event.id}") },
                            onEditProfile = { navController.navigate(ROUTE_PROFILE_EDIT) },
                        )
                    }
                    composable(ROUTE_VERIFY_IDENTITY) {
                        VerifyIdentityScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        ROUTE_SEARCH,
                        arguments = listOf(
                            navArgument("query") { type = NavType.StringType; defaultValue = "" },
                            navArgument("autoOpen") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) { entry ->
                        SearchScreen(
                            onBack = { navController.popBackStack() },
                            onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                            onHashtagClick = { tag -> navController.navigate("hashtag/$tag") },
                            initialQuery = entry.arguments?.getString("query") ?: "",
                            autoOpenEvent = entry.arguments?.getBoolean("autoOpen") ?: false,
                            onOpenEventInFeed = { navController.popBackStack(ROUTE_FEED, inclusive = false) },
                        )
                    }
                    composable(ROUTE_HASHTAG) {
                        HashtagFeedScreen(
                            onBack = { navController.popBackStack() },
                            onThreadClick = { eventId -> navController.navigate("thread/$eventId") },
                            onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") },
                            onHashtagClick = { tag -> navController.navigate("hashtag/" + Uri.encode(tag)) },
                        )
                    }
                }
            }
        }
    }

}

// ── Decoy NavHost (shown when Notepad front is locked) ──────────────────────

@Composable
private fun DecoyNavHost(onUnlock: () -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current
    NavHost(navController = navController, startDestination = ROUTE_DECOY_NOTES) {
        composable(ROUTE_DECOY_NOTES) {
            // Decoy notes — no bottom bar, no way back to T-Bone except the secret tap.
            social.tbone.ui.decoy.DecoyNotesScreen(
                onBack = { /* in decoy, back does nothing — it's the root */ },
                onNewNote = { navController.navigate("decoy_note_editor?id=") },
                onOpenNote = { id -> navController.navigate("decoy_note_editor?id=" + Uri.encode(id)) },
                onNewChecklist = { navController.navigate("decoy_checklist_editor?id=&folder=") },
                onOpenChecklist = { id -> navController.navigate("decoy_checklist_editor?id=" + Uri.encode(id) + "&folder=") },
                onOpenFolder = { folderId, name ->
                    navController.navigate("decoy_folder/" + Uri.encode(folderId) + "?name=" + Uri.encode(name))
                },
                onInfo = { navController.navigate(ROUTE_DECOY_NOTES_INFO) },
                onShare = { text ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share note"))
                },
                onPublish = { _ -> },
            )
        }
        composable(
            ROUTE_DECOY_NOTE_EDITOR,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
                navArgument("folder") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: ""
            val folder = entry.arguments?.getString("folder")?.takeIf { it.isNotBlank() }
            social.tbone.ui.decoy.DecoyNoteEditorScreen(
                id = id,
                folderId = folder,
                onBack = { navController.popBackStack() },
                onShare = { text ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share note"))
                },
                onPublish = { _ -> },
            )
        }
        composable(
            ROUTE_DECOY_CHECKLIST_EDITOR,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
                navArgument("folder") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: ""
            val folder = entry.arguments?.getString("folder")?.takeIf { it.isNotBlank() }
            social.tbone.ui.decoy.DecoyChecklistEditorScreen(
                id = id,
                folderId = folder,
                onBack = { navController.popBackStack() },
                onShare = { text ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share checklist"))
                },
                onPublish = { _ -> },
            )
        }
        composable(
            ROUTE_DECOY_FOLDER,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "folder" },
            ),
        ) { entry ->
            val folderId = entry.arguments?.getString("id") ?: ""
            val folderName = entry.arguments?.getString("name") ?: "folder"
            social.tbone.ui.decoy.DecoyFolderContentsScreen(
                folderId = folderId,
                folderName = folderName,
                onBack = { navController.popBackStack() },
                onNewNote = { navController.navigate("decoy_note_editor?id=&folder=" + Uri.encode(folderId)) },
                onOpenNote = { id -> navController.navigate("decoy_note_editor?id=" + Uri.encode(id) + "&folder=" + Uri.encode(folderId)) },
                onNewChecklist = { navController.navigate("decoy_checklist_editor?id=&folder=" + Uri.encode(folderId)) },
                onOpenChecklist = { id -> navController.navigate("decoy_checklist_editor?id=" + Uri.encode(id) + "&folder=" + Uri.encode(folderId)) },
                onShare = { text ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share"))
                },
            )
        }
        composable(ROUTE_DECOY_NOTES_INFO) {
            social.tbone.ui.decoy.DecoyNotesInfoScreen(
                onBack = { navController.popBackStack() },
                onUnlock = onUnlock,
            )
        }
    }
}

@HiltViewModel
class StealthGateViewModel @Inject constructor(
    private val appSettings: social.tbone.settings.AppSettings,
) : ViewModel() {
    val notepadFrontEnabled: StateFlow<Boolean> = appSettings.notepadFrontEnabled
    val decoyUnlocked: StateFlow<Boolean> = appSettings.decoyUnlocked
    fun unlock() { appSettings.setDecoyUnlocked(true) }
}

// ── Bottom-bar tab navigation ────────────────────────────────────────────────

/** Route for each bottom-bar tab. */
private fun routeFor(tab: BottomTab): String = when (tab) {
    BottomTab.HOME -> ROUTE_FEED
    BottomTab.COMPOSE -> "compose"
    BottomTab.NOTIF -> ROUTE_NOTIFICATIONS
    BottomTab.TOOLS -> ROUTE_TOOLBOX
    BottomTab.CONF -> ROUTE_SETTINGS
}

/**
 * Switches tabs the way a bottom bar should: one entry per tab (no stacking),
 * with each tab's own scroll position preserved.
 */
private fun NavHostController.switchToTab(tab: BottomTab) {
    navigate(routeFor(tab)) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Wraps a top-level screen in the shared bottom bar so the toolbox and
 * notifications screens keep it, exactly like the home tab does.
 */
@Composable
private fun WithBottomBar(
    current: BottomTab,
    navController: NavHostController,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg),
    ) {
        Box(modifier = Modifier.weight(1f)) { content() }
        BonyBottomBar(
            selected = current,
            onSelect = { tab -> navController.switchToTab(tab) },
        )
    }
}

// ── Startup ViewModel ─────────────────────────────────────────────────────────

sealed interface StartupState {
    data object Loading : StartupState
    data class Ready(val hasAccount: Boolean) : StartupState
}

@HiltViewModel
class StartupViewModel @Inject constructor(
    accountRepository: AccountRepository,
    deepLinkHandler: DeepLinkHandler,
) : ViewModel() {
    val startupState: StateFlow<StartupState> = accountRepository.activeAccount
        .map { account -> StartupState.Ready(hasAccount = account != null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartupState.Loading)

    val openThread: SharedFlow<String> = deepLinkHandler.openThread
    val openProfile: SharedFlow<String> = deepLinkHandler.openProfile
}

// ── Lock ViewModel ────────────────────────────────────────────────────────────

@HiltViewModel
class LockViewModel @Inject constructor(
    private val lockManager: AppLockManager,
) : ViewModel() {
    val isLocked: StateFlow<Boolean> = lockManager.isLocked

    fun unlock() = lockManager.unlock()
}
