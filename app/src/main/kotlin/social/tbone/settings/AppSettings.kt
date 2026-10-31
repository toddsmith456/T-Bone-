package social.tbone.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import social.tbone.media.blossom.BlossomServerUrl
import social.tbone.nostr.geohash.GeohashChannelEntry
import social.tbone.nostr.geohash.GeohashChannelStore
import social.tbone.webxdc.XdcCustomTool
import social.tbone.webxdc.XdcCustomToolsCodec
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    companion object {
        /** Default anchor for the clock feed: six hours back. */
        const val DEFAULT_CLOCK_HOURS = 6
        val GEOHASH_CACHE_OPTIONS = listOf(0L, 30*60_000L, 60*60_000L, 5*60*60_000L, 24*60*60_000L, 7*24*60*60_000L)

        private val TOR_ENABLED = booleanPreferencesKey("tor_enabled")
        private val TOR_KILL_SWITCH = booleanPreferencesKey("tor_kill_switch")
        // False until the user (or auto-detect) explicitly sets the preference.
        // Lets first-run logic distinguish "never set" from "user chose off".
        private val TOR_EXPLICITLY_SET = booleanPreferencesKey("tor_explicitly_set")
        private val LAST_VIEWED_NOTIFICATIONS_AT = longPreferencesKey("last_viewed_notifications_at")
        private val GLOBAL_AUTO_LOAD_ENABLED = booleanPreferencesKey("global_auto_load_enabled")
        private val GLOBAL_AUTO_LOAD_INTERVAL_MINUTES = intPreferencesKey("global_auto_load_interval_minutes")
        // Clock feed: how many hours back the timeline is anchored. Persists across launches.
        private val CLOCK_FEED_HOURS = intPreferencesKey("clock_feed_hours")
        private val PIN_ENABLED = booleanPreferencesKey("pin_enabled")
        private val PIN_HASH = stringPreferencesKey("pin_hash")
        private val PIN_AUTO_LOCK_MINUTES = intPreferencesKey("pin_auto_lock_minutes")
        private val PIN_LAST_UNLOCK_MILLIS = longPreferencesKey("pin_last_unlock_millis")
        // Duress pin: entering it on the lock screen wipes all app data instead
        // of unlocking. Default OFF.
        private val DURESS_PIN_ENABLED = booleanPreferencesKey("duress_pin_enabled")
        private val DURESS_PIN_HASH = stringPreferencesKey("duress_pin_hash")
        private val IMAGE_LOAD_MODE = stringPreferencesKey("image_load_mode")
        private val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        private val VIDEO_PLAYBACK_MODE = stringPreferencesKey("video_playback_mode")
        private val VIDEO_THUMBNAILS = booleanPreferencesKey("video_thumbnails")
        private val IMAGE_CACHE_LIGHTWEIGHT = booleanPreferencesKey("image_cache_lightweight")
        private val AVATAR_MODE = stringPreferencesKey("avatar_mode")
        private val AVATAR_ANIMATED = booleanPreferencesKey("avatar_animated")
        // Enabled notification-type filters (names of NotifFilter). Empty set = all on.
        private val NOTIFICATION_ENABLED_TYPES = stringSetPreferencesKey("notification_enabled_types")
        // Global relay list — separate from personal relay list, used only for the Global feed.
        private val GLOBAL_RELAYS = stringSetPreferencesKey("global_relays")
        private val GLOBAL_ACTIVE_RELAYS = stringSetPreferencesKey("global_active_relays")

        // Color theme: light / dark / cream.
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        // Custom accent color as "#RRGGBB"; null/absent = default green.
        private val ACCENT_COLOR = stringPreferencesKey("accent_color")
        // UI engine: bony (default) vs youniversal
        private val UI_THEME = stringPreferencesKey("ui_theme")
        // One-switch compact chrome and separately randomized PIN keypads.
        private val UI_DECLUTTER_ENABLED = booleanPreferencesKey("ui_declutter_enabled")
        private val APP_PIN_KEYPAD_SCRAMBLED = booleanPreferencesKey("app_pin_keypad_scrambled")
        private val TOOLBOX_PIN_KEYPAD_SCRAMBLED = booleanPreferencesKey("toolbox_pin_keypad_scrambled")
        // Screenshot blocker (FLAG_SECURE) — app-wide.
        private val SCREENSHOT_BLOCK_ENABLED = booleanPreferencesKey("screenshot_block_enabled")
        // Toolbox-only settings.
        private val TOOLBOX_SCREENSHOT_BLOCK_ENABLED = booleanPreferencesKey("toolbox_screenshot_block_enabled")
        private val TOOLBOX_PIN_ENABLED = booleanPreferencesKey("toolbox_pin_enabled")
        private val TOOLBOX_PIN_HASH = stringPreferencesKey("toolbox_pin_hash")
        private val TOOLBOX_DURESS_PIN_ENABLED = booleanPreferencesKey("toolbox_duress_pin_enabled")
        private val TOOLBOX_DURESS_PIN_HASH = stringPreferencesKey("toolbox_duress_pin_hash")
        private val TOOLBOX_PIN_AUTO_LOCK_MINUTES = intPreferencesKey("toolbox_pin_auto_lock_minutes")
        private val TOOLBOX_PIN_LAST_UNLOCK_MILLIS = longPreferencesKey("toolbox_pin_last_unlock_millis")
        private val TOOLBOX_TOOL_ORDER = stringPreferencesKey("toolbox_tool_order")
        // Saved webxdc mini apps promoted to toolbox tools (JSON array).
        private val XDC_CUSTOM_TOOLS = stringPreferencesKey("xdc_custom_tools")
        // Geohash identity seed (base64, 32 bytes) — random per install, like Amethyst's remote-signer path.
        private val GEOHASH_IDENTITY_SEED = stringPreferencesKey("geohash_identity_seed")
        private val GEOHASH_CHANNELS = stringPreferencesKey("geohash_channels")
        private val GEOHASH_NICKNAME = stringPreferencesKey("geohash_nickname")
        private val GEOHASH_CACHE_MILLIS = longPreferencesKey("geohash_cache_millis")
        // Content filtering: blocked pubkeys, NSFW hiding, word bleep/hide.
        private val BLOCKED_PUBKEYS = stringSetPreferencesKey("blocked_pubkeys")
        private val NOTE_MENU_MODERATION = booleanPreferencesKey("note_menu_moderation")
        private val CLIENT_TAG_ENABLED = booleanPreferencesKey("client_tag_enabled")
        private val HIDE_NSFW = booleanPreferencesKey("hide_nsfw")
        private val BLEEP_WORDS = stringSetPreferencesKey("bleep_words")
        private val HIDE_WORDS = stringSetPreferencesKey("hide_words")
        // Parental PIN: locks the content-filter settings behind a 4-digit pin
        // so a child can't change what the parent configured (e.g. NSFW hiding).
        private val PARENTAL_PIN_ENABLED = booleanPreferencesKey("parental_pin_enabled")
        private val PARENTAL_PIN_HASH = stringPreferencesKey("parental_pin_hash")

        // Multi emoji reactions: on/off + up to 10 saved emojis (newline-joined,
        // order preserved).
        private val MULTI_REACTIONS_ENABLED = booleanPreferencesKey("multi_reactions_enabled")
        private val REACTION_EMOJIS = stringPreferencesKey("reaction_emojis")

        /** Maximum number of saved reaction emojis. */
        const val MAX_REACTION_EMOJIS = 10
        // Blossom media uploads: enabled servers (defaults + custom), the
        // preferred default server, and whether uploads are compressed.
        private val BLOSSOM_SERVERS = stringSetPreferencesKey("blossom_servers")
        private val BLOSSOM_DEFAULT_SERVER = stringPreferencesKey("blossom_default_server")
        private val BLOSSOM_COMPRESS = booleanPreferencesKey("blossom_compress")
        private val BLOSSOM_AUDIO_COMPRESS = booleanPreferencesKey("blossom_audio_compress")
        // Monero xaps.
        private val LIGHTNING_ZAPS_ENABLED = booleanPreferencesKey("lightning_zaps_enabled")
        private val MONERO_XAPS_ENABLED = booleanPreferencesKey("monero_xaps_enabled")
        // Legacy preset keys — removed from UI but wiped on migration.
        private val LIGHTNING_ZAP_AMOUNT_SATS = longPreferencesKey("lightning_zap_amount_sats")
        private val LIGHTNING_ZAP_AMOUNTS = stringPreferencesKey("lightning_zap_amounts")

        // One-time migration sources from the removed NWC wallet feature. The
        // old connection and payment outcome data is deleted during migration.
        private val LEGACY_NWC_ZAPS_ENABLED = booleanPreferencesKey("nwc_zaps_enabled")
        private val LEGACY_NWC_ZAP_AMOUNT_SATS = longPreferencesKey("nwc_zap_amount_sats")
        private val LEGACY_NWC_ZAP_AMOUNTS = stringPreferencesKey("nwc_zap_amounts")
        private val LEGACY_NWC_USER_DISCONNECTED = booleanPreferencesKey("nwc_user_disconnected")
        private val LEGACY_NWC_WALLET_PUBKEY = stringPreferencesKey("nwc_wallet_pubkey")
        private val LEGACY_NWC_RELAY_URLS = stringPreferencesKey("nwc_relay_urls")
        private val LEGACY_NWC_CLIENT_PUBKEY = stringPreferencesKey("nwc_client_pubkey")
        private val LEGACY_NWC_ENCRYPTED_SECRET = stringPreferencesKey("nwc_encrypted_secret")
        private val LEGACY_NWC_LUD16 = stringPreferencesKey("nwc_lud16")
        private val LEGACY_NWC_PAID_ZAP_IDS = stringSetPreferencesKey("nwc_paid_zap_ids")
        private val LEGACY_NWC_UNCERTAIN_ZAP_IDS = stringSetPreferencesKey("nwc_uncertain_zap_ids")
        // Screen time (parental): daily allowance + rollover tracking.
        private val SCREEN_TIME_ENABLED = booleanPreferencesKey("screen_time_enabled")
        private val SCREEN_TIME_MINUTES = intPreferencesKey("screen_time_minutes")
        private val SCREEN_TIME_DATE = stringPreferencesKey("screen_time_date")
        private val SCREEN_TIME_USED_SECONDS = longPreferencesKey("screen_time_used_seconds")
        private val SCREEN_TIME_GRACE_UNTIL = longPreferencesKey("screen_time_grace_until")

        // Follow lists tool: follower list is off by default, disclaimer shown once
        private val FOLLOWERS_ENABLED = booleanPreferencesKey("followers_enabled")
        private val FOLLOWERS_DISCLAIMER_SHOWN = booleanPreferencesKey("followers_disclaimer_shown")

        // ── Stealth: Notepad front ──────────────────────────────────────────────
        private val NOTEPAD_FRONT_ENABLED = booleanPreferencesKey("notepad_front_enabled")
        private val NOTEPAD_FRONT_DISCLAIMER_SHOWN = booleanPreferencesKey("notepad_front_disclaimer_shown")

        /** The three default Blossom servers, always available unless disabled. */
        val BLOSSOM_DEFAULTS = setOf(
            "https://nostr.download",
            "https://blossom.data.haus",
            "https://blossom.ditto.pub",
        )

        /** Default relays for the Global feed — as requested. */
        val GLOBAL_RELAY_DEFAULTS = setOf(
            "wss://primus.nostr1.com",
            "wss://trending.relay.com",
            "wss://nostr.wine",
            "wss://pyramid.fiatjaf.com",
        )
    }

    private val _torEnabled = MutableStateFlow(false)
    val torEnabled = _torEnabled.asStateFlow()

    private val _torKillSwitch = MutableStateFlow(false)
    val torKillSwitch = _torKillSwitch.asStateFlow()

    private val _initialized = MutableStateFlow(false)
    val initialized = _initialized.asStateFlow()

    private val _torExplicitlySet = MutableStateFlow(false)
    val torExplicitlySet = _torExplicitlySet.asStateFlow()

    private val _lastViewedNotificationsAt = MutableStateFlow(0L)
    val lastViewedNotificationsAt = _lastViewedNotificationsAt.asStateFlow()

    private val _globalAutoLoadEnabled = MutableStateFlow(true)
    val globalAutoLoadEnabled = _globalAutoLoadEnabled.asStateFlow()

    private val _globalAutoLoadIntervalMinutes = MutableStateFlow(5)
    val globalAutoLoadIntervalMinutes = _globalAutoLoadIntervalMinutes.asStateFlow()

    /** Global feed's dedicated relay list — separate from personal relays. */
    private val _globalRelays = MutableStateFlow<Set<String>>(GLOBAL_RELAY_DEFAULTS)
    val globalRelays = _globalRelays.asStateFlow()

    /** Which global relays are currently enabled for viewing (subset of globalRelays). */
    private val _globalActiveRelays = MutableStateFlow<Set<String>>(GLOBAL_RELAY_DEFAULTS)
    val globalActiveRelays = _globalActiveRelays.asStateFlow()

    /** Hours back the clock feed is anchored (1..24). */
    private val _clockFeedHours = MutableStateFlow(DEFAULT_CLOCK_HOURS)
    val clockFeedHours = _clockFeedHours.asStateFlow()

    private val _pinEnabled = MutableStateFlow(false)
    val pinEnabled = _pinEnabled.asStateFlow()

    private val _pinAutoLockMinutes = MutableStateFlow(5)
    val pinAutoLockMinutes = _pinAutoLockMinutes.asStateFlow()
    private val _pinLastUnlockMillis = MutableStateFlow(0L)
    val pinLastUnlockMillis = _pinLastUnlockMillis.asStateFlow()

    private val _duressPinEnabled = MutableStateFlow(false)
    val duressPinEnabled = _duressPinEnabled.asStateFlow()

    private val _appPinKeypadScrambled = MutableStateFlow(false)
    val appPinKeypadScrambled = _appPinKeypadScrambled.asStateFlow()

    private val _imageLoadMode = MutableStateFlow(ImageLoadMode.ON)
    val imageLoadMode = _imageLoadMode.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQuality.REGULAR)
    val videoQuality = _videoQuality.asStateFlow()

    private val _videoPlaybackMode = MutableStateFlow(VideoPlaybackMode.PLAY_ON_TAP)
    val videoPlaybackMode = _videoPlaybackMode.asStateFlow()

    private val _videoThumbnails = MutableStateFlow(true)
    val videoThumbnails = _videoThumbnails.asStateFlow()

    private val _imageCacheLightweight = MutableStateFlow(false)
    val imageCacheLightweight = _imageCacheLightweight.asStateFlow()

    private val _avatarMode = MutableStateFlow(AvatarMode.REGULAR)
    val avatarMode = _avatarMode.asStateFlow()

    private val _avatarAnimated = MutableStateFlow(true)
    val avatarAnimated = _avatarAnimated.asStateFlow()

    /** Enabled notification-type filters. Empty set = all types shown. */
    private val _notificationEnabledTypes = MutableStateFlow<Set<String>>(emptySet())
    val notificationEnabledTypes = _notificationEnabledTypes.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode = _themeMode.asStateFlow()

    private val _accentColor = MutableStateFlow<String?>(null)
    val accentColor = _accentColor.asStateFlow()

    private val _uiTheme = MutableStateFlow(UiTheme.BONY)
    val uiTheme = _uiTheme.asStateFlow()

    private val _uiDeclutterEnabled = MutableStateFlow(false)
    val uiDeclutterEnabled = _uiDeclutterEnabled.asStateFlow()

    private val _screenshotBlockEnabled = MutableStateFlow(false)
    val screenshotBlockEnabled = _screenshotBlockEnabled.asStateFlow()

    private val _toolboxScreenshotBlockEnabled = MutableStateFlow(false)
    val toolboxScreenshotBlockEnabled = _toolboxScreenshotBlockEnabled.asStateFlow()

    private val _toolboxPinEnabled = MutableStateFlow(false)
    val toolboxPinEnabled = _toolboxPinEnabled.asStateFlow()

    private val _toolboxPinAutoLockMinutes = MutableStateFlow(5)
    val toolboxPinAutoLockMinutes = _toolboxPinAutoLockMinutes.asStateFlow()
    private val _toolboxPinLastUnlockMillis = MutableStateFlow(0L)
    val toolboxPinLastUnlockMillis = _toolboxPinLastUnlockMillis.asStateFlow()

    private val _toolboxPinKeypadScrambled = MutableStateFlow(false)
    val toolboxPinKeypadScrambled = _toolboxPinKeypadScrambled.asStateFlow()

    private val _toolboxDuressPinEnabled = MutableStateFlow(false)
    val toolboxDuressPinEnabled = _toolboxDuressPinEnabled.asStateFlow()

    /** Ordered list of tool ids in the toolbox. */
    private val _toolboxToolOrder = MutableStateFlow<List<String>>(emptyList())
    val toolboxToolOrder = _toolboxToolOrder.asStateFlow()

    /** User-created tools backed by saved webxdc apps. */
    private val _xdcCustomTools = MutableStateFlow<List<XdcCustomTool>>(emptyList())
    val xdcCustomTools = _xdcCustomTools.asStateFlow()

    /** Saved geohash channels (messenger contact list), in display order. */
    private val _geohashChannels = MutableStateFlow<List<GeohashChannelEntry>>(emptyList())
    val geohashChannels = _geohashChannels.asStateFlow()

    private val _geohashCacheMillis = MutableStateFlow(0L)
    val geohashCacheMillis = _geohashCacheMillis.asStateFlow()

    /** Consistent nickname used when posting to geohash channels. */
    private val _geohashNickname = MutableStateFlow("")
    val geohashNickname = _geohashNickname.asStateFlow()

    /** Pubkeys whose notes are completely hidden (block). */
    private val _blockedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val blockedPubkeys = _blockedPubkeys.asStateFlow()

    /** Show block/report actions in each note's overflow menu (off by default). */
    private val _noteMenuModerationEnabled = MutableStateFlow(false)
    val noteMenuModerationEnabled = _noteMenuModerationEnabled.asStateFlow()

    /** Add the NIP-89 [client, T-Bone] tag when signing events (on by default). */
    private val _clientTagEnabled = MutableStateFlow(true)
    val clientTagEnabled = _clientTagEnabled.asStateFlow()

    /** Whether the content-filters screen is locked behind the parental pin. */
    private val _parentalPinEnabled = MutableStateFlow(false)
    val parentalPinEnabled = _parentalPinEnabled.asStateFlow()

    /**
     * In-memory (never persisted) unlock of the Content Filters folder. Shared
     * by every screen inside the folder (filters + screen time) so one pin
     * entry opens the whole folder; it relocks when the folder is left.
     */
    private val _multiReactionsEnabled = MutableStateFlow(false)
    val multiReactionsEnabled = _multiReactionsEnabled.asStateFlow()

    private val _reactionEmojis = MutableStateFlow<List<String>>(emptyList())
    val reactionEmojis = _reactionEmojis.asStateFlow()

    suspend fun setMultiReactionsEnabled(enabled: Boolean) {
        _multiReactionsEnabled.value = enabled
        dataStore.edit { it[MULTI_REACTIONS_ENABLED] = enabled }
    }

    suspend fun setReactionEmojis(emojis: List<String>) {
        val clean = emojis.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_REACTION_EMOJIS)
        _reactionEmojis.value = clean
        dataStore.edit { it[REACTION_EMOJIS] = clean.joinToString("\n") }
    }

    private val _parentalUnlocked = MutableStateFlow(false)
    val parentalUnlocked = _parentalUnlocked.asStateFlow()
    fun setParentalUnlocked(unlocked: Boolean) { _parentalUnlocked.value = unlocked }

    /** Enabled Blossom servers (defaults + custom), for media uploads. */
    private val _blossomServers = MutableStateFlow<Set<String>>(BLOSSOM_DEFAULTS)
    val blossomServers = _blossomServers.asStateFlow()

    /** The preferred Blossom server, or null to pick randomly from the pool. */
    private val _blossomDefaultServer = MutableStateFlow<String?>(null)
    val blossomDefaultServer = _blossomDefaultServer.asStateFlow()

    /** Whether image/video uploads are compressed before sending. */
    private val _blossomCompress = MutableStateFlow(true)
    val blossomCompress = _blossomCompress.asStateFlow()

    /** Audio transcoding is opt-in; original audio is uploaded by default. */
    private val _blossomAudioCompress = MutableStateFlow(false)
    val blossomAudioCompress = _blossomAudioCompress.asStateFlow()


    private val _lightningZapsEnabled = MutableStateFlow(true)
    val lightningZapsEnabled = _lightningZapsEnabled.asStateFlow()

    private val _moneroXapsEnabled = MutableStateFlow(false)
    val moneroXapsEnabled = _moneroXapsEnabled.asStateFlow()

    /** Parental screen time: enabled / daily minutes / rollover tracking. */
    private val _screenTimeEnabled = MutableStateFlow(false)
    val screenTimeEnabled = _screenTimeEnabled.asStateFlow()

    private val _screenTimeMinutes = MutableStateFlow(120)
    val screenTimeMinutes = _screenTimeMinutes.asStateFlow()

    /** Hide notes carrying a NIP-36 content-warning ("content-warning") tag. */
    private val _hideNsfw = MutableStateFlow(false)
    val hideNsfw = _hideNsfw.asStateFlow()

    /** Words replaced with asterisks in note text (bleep). */
    private val _bleepWords = MutableStateFlow<Set<String>>(emptySet())
    val bleepWords = _bleepWords.asStateFlow()

    /** Words that hide any note containing them. */
    private val _hideWords = MutableStateFlow<Set<String>>(emptySet())
    val hideWords = _hideWords.asStateFlow()

    // Followers (centralized via Primal) — off by default, opt-in with one switch
    private val _followersEnabled = MutableStateFlow(false)
    val followersEnabled = _followersEnabled.asStateFlow()

    private val _followersDisclaimerShown = MutableStateFlow(false)
    val followersDisclaimerShown = _followersDisclaimerShown.asStateFlow()

    // ── Stealth: notepad front (persisted) + session unlock (in-memory) ───────
    private val _notepadFrontEnabled = MutableStateFlow(false)
    val notepadFrontEnabled = _notepadFrontEnabled.asStateFlow()

    private val _notepadFrontDisclaimerShown = MutableStateFlow(false)
    val notepadFrontDisclaimerShown = _notepadFrontDisclaimerShown.asStateFlow()

    /** Session-only: true after the user taps key storage 10× in decoy info. */
    private val _decoyUnlocked = MutableStateFlow(false)
    val decoyUnlocked = _decoyUnlocked.asStateFlow()
    fun setDecoyUnlocked(unlocked: Boolean) { _decoyUnlocked.value = unlocked }

    /** Session-only set of hidden pubkeys that have been unlocked via PIN. */
    private val _hiddenUnlockedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val hiddenUnlockedPubkeys = _hiddenUnlockedPubkeys.asStateFlow()
    fun addHiddenUnlocked(pubkey: String) { _hiddenUnlockedPubkeys.value += pubkey }
    fun removeHiddenUnlocked(pubkey: String) { _hiddenUnlockedPubkeys.value -= pubkey }
    fun clearHiddenUnlocked() { _hiddenUnlockedPubkeys.value = emptySet() }

    init {
        scope.launch {
            migrateRemovedNwcPreferences()
            dataStore.data.collect { prefs ->
                _torExplicitlySet.value = prefs[TOR_EXPLICITLY_SET] == true
                _torEnabled.value = prefs[TOR_ENABLED] ?: false
                _torKillSwitch.value = prefs[TOR_KILL_SWITCH] ?: false
                _lastViewedNotificationsAt.value = prefs[LAST_VIEWED_NOTIFICATIONS_AT] ?: 0L
                _globalAutoLoadEnabled.value = prefs[GLOBAL_AUTO_LOAD_ENABLED] ?: true
                _globalAutoLoadIntervalMinutes.value = (prefs[GLOBAL_AUTO_LOAD_INTERVAL_MINUTES] ?: 5).coerceIn(1, 20)
                _clockFeedHours.value = (prefs[CLOCK_FEED_HOURS] ?: DEFAULT_CLOCK_HOURS).coerceIn(1, 24)
                _pinEnabled.value = prefs[PIN_ENABLED] ?: false
                _pinAutoLockMinutes.value = (prefs[PIN_AUTO_LOCK_MINUTES] ?: 5).coerceIn(1,30)
                _pinLastUnlockMillis.value = prefs[PIN_LAST_UNLOCK_MILLIS] ?: 0L
                _duressPinEnabled.value = prefs[DURESS_PIN_ENABLED] ?: false
                _appPinKeypadScrambled.value = prefs[APP_PIN_KEYPAD_SCRAMBLED] ?: false
                _imageLoadMode.value = ImageLoadMode.fromPref(prefs[IMAGE_LOAD_MODE])
                _videoQuality.value = VideoQuality.fromPref(prefs[VIDEO_QUALITY])
                _videoPlaybackMode.value = VideoPlaybackMode.fromPref(prefs[VIDEO_PLAYBACK_MODE])
                _videoThumbnails.value = prefs[VIDEO_THUMBNAILS] ?: true
                _imageCacheLightweight.value = prefs[IMAGE_CACHE_LIGHTWEIGHT] ?: false
                _avatarMode.value = AvatarMode.fromPref(prefs[AVATAR_MODE])
                _avatarAnimated.value = prefs[AVATAR_ANIMATED] ?: true
                _notificationEnabledTypes.value = prefs[NOTIFICATION_ENABLED_TYPES] ?: emptySet()
                _themeMode.value = ThemeMode.fromPref(prefs[THEME_MODE])
                _accentColor.value = prefs[ACCENT_COLOR]
                _uiTheme.value = UiTheme.fromPref(prefs[UI_THEME])
                _uiDeclutterEnabled.value = prefs[UI_DECLUTTER_ENABLED] ?: false
                _screenshotBlockEnabled.value = prefs[SCREENSHOT_BLOCK_ENABLED] ?: false
                _toolboxScreenshotBlockEnabled.value = prefs[TOOLBOX_SCREENSHOT_BLOCK_ENABLED] ?: false
                _toolboxPinEnabled.value = prefs[TOOLBOX_PIN_ENABLED] ?: false
                _toolboxPinAutoLockMinutes.value = (prefs[TOOLBOX_PIN_AUTO_LOCK_MINUTES] ?: 5).coerceIn(1,30)
                _toolboxPinLastUnlockMillis.value = prefs[TOOLBOX_PIN_LAST_UNLOCK_MILLIS] ?: 0L
                _toolboxPinKeypadScrambled.value = prefs[TOOLBOX_PIN_KEYPAD_SCRAMBLED] ?: false
                _toolboxDuressPinEnabled.value = prefs[TOOLBOX_DURESS_PIN_ENABLED] ?: false
                _toolboxToolOrder.value = prefs[TOOLBOX_TOOL_ORDER]
                    ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                _xdcCustomTools.value = XdcCustomToolsCodec.decode(prefs[XDC_CUSTOM_TOOLS])
                _geohashChannels.value = prefs[GEOHASH_CHANNELS]?.let { GeohashChannelStore.decode(it) } ?: emptyList()
                _geohashNickname.value = prefs[GEOHASH_NICKNAME] ?: ""
                _geohashCacheMillis.value = prefs[GEOHASH_CACHE_MILLIS]?.takeIf { it in GEOHASH_CACHE_OPTIONS } ?: 0L
                _blockedPubkeys.value = prefs[BLOCKED_PUBKEYS] ?: emptySet()
                _noteMenuModerationEnabled.value = prefs[NOTE_MENU_MODERATION] ?: false
                _clientTagEnabled.value = prefs[CLIENT_TAG_ENABLED] ?: true
                _hideNsfw.value = prefs[HIDE_NSFW] ?: false
                _bleepWords.value = prefs[BLEEP_WORDS] ?: emptySet()
                _hideWords.value = prefs[HIDE_WORDS] ?: emptySet()
                _followersEnabled.value = prefs[FOLLOWERS_ENABLED] ?: false
                _followersDisclaimerShown.value = prefs[FOLLOWERS_DISCLAIMER_SHOWN] ?: false
                _notepadFrontEnabled.value = prefs[NOTEPAD_FRONT_ENABLED] ?: false
                _notepadFrontDisclaimerShown.value = prefs[NOTEPAD_FRONT_DISCLAIMER_SHOWN] ?: false
                _parentalPinEnabled.value = prefs[PARENTAL_PIN_ENABLED] ?: false
                _multiReactionsEnabled.value = prefs[MULTI_REACTIONS_ENABLED] ?: false
                _reactionEmojis.value = prefs[REACTION_EMOJIS]
                    ?.split("\n")?.filter { it.isNotBlank() }?.take(MAX_REACTION_EMOJIS) ?: emptyList()
                // Normalise on load: earlier builds stored whatever the user typed,
                // so a scheme-less entry ("blossom.example.com") would be handed to
                // OkHttp verbatim and every upload failed before leaving the device.
                val storedServers = prefs[BLOSSOM_SERVERS]
                    ?.mapNotNull { BlossomServerUrl.normalize(it) }
                    ?.toSet()
                    .orEmpty()
                _blossomServers.value = storedServers.ifEmpty { BLOSSOM_DEFAULTS }
                _blossomDefaultServer.value =
                    prefs[BLOSSOM_DEFAULT_SERVER]?.let { BlossomServerUrl.normalize(it) }
                        ?.takeIf { it in storedServers }
                _blossomCompress.value = prefs[BLOSSOM_COMPRESS] ?: true
                _blossomAudioCompress.value = prefs[BLOSSOM_AUDIO_COMPRESS] ?: false
                _lightningZapsEnabled.value = prefs[LIGHTNING_ZAPS_ENABLED] ?: true
                _moneroXapsEnabled.value = prefs[MONERO_XAPS_ENABLED] ?: false
                _screenTimeEnabled.value = prefs[SCREEN_TIME_ENABLED] ?: false
                _screenTimeMinutes.value = prefs[SCREEN_TIME_MINUTES] ?: 120
                // Absence means first-run defaults; a stored empty set is intentional.
                val storedGlobal = prefs[GLOBAL_RELAYS]?.mapNotNull { normalizeGlobalRelay(it) }?.toSet()
                _globalRelays.value = storedGlobal ?: GLOBAL_RELAY_DEFAULTS
                val storedActive = prefs[GLOBAL_ACTIVE_RELAYS]?.mapNotNull { normalizeGlobalRelay(it) }?.toSet()
                _globalActiveRelays.value = storedActive?.intersect(_globalRelays.value) ?: _globalRelays.value
                _initialized.value = true
            }
        }
    }

    suspend fun setTorKillSwitch(enabled: Boolean) {
        _torKillSwitch.value = enabled
        dataStore.edit { it[TOR_KILL_SWITCH] = enabled }
    }

    suspend fun setTorEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[TOR_ENABLED] = enabled
            prefs[TOR_EXPLICITLY_SET] = true
        }
    }

    suspend fun setLastViewedNotificationsAt(epochSeconds: Long) {
        dataStore.edit { it[LAST_VIEWED_NOTIFICATIONS_AT] = epochSeconds }
    }

    suspend fun setGlobalAutoLoadEnabled(enabled: Boolean) {
        _globalAutoLoadEnabled.value = enabled
        dataStore.edit { it[GLOBAL_AUTO_LOAD_ENABLED] = enabled }
    }

    suspend fun setGlobalAutoLoadIntervalMinutes(minutes: Int) {
        val safeMinutes = minutes.coerceIn(1, 20)
        _globalAutoLoadIntervalMinutes.value = safeMinutes
        dataStore.edit { it[GLOBAL_AUTO_LOAD_INTERVAL_MINUTES] = safeMinutes }
    }

    /** Clamps to 1..24 and persists, so the clock feed survives a restart. */
    suspend fun setClockFeedHours(hours: Int) {
        val safe = hours.coerceIn(1, 24)
        _clockFeedHours.value = safe
        dataStore.edit { it[CLOCK_FEED_HOURS] = safe }
    }

    suspend fun setPinEnabled(enabled: Boolean) {
        dataStore.edit { it[PIN_ENABLED] = enabled }
    }

    suspend fun setPinAutoLockMinutes(minutes: Int) {
        val safe = minutes.coerceIn(1,30)
        _pinAutoLockMinutes.value = safe
        dataStore.edit { it[PIN_AUTO_LOCK_MINUTES] = safe }
    }

    suspend fun setPinLastUnlockMillis(millis: Long) {
        _pinLastUnlockMillis.value = millis
        dataStore.edit { it[PIN_LAST_UNLOCK_MILLIS] = millis }
    }

    suspend fun getPinAutoLockMinutes(): Int = dataStore.data.first()[PIN_AUTO_LOCK_MINUTES] ?: 5
    suspend fun getPinLastUnlockMillis(): Long = dataStore.data.first()[PIN_LAST_UNLOCK_MILLIS] ?: 0L

    suspend fun setAppPinKeypadScrambled(enabled: Boolean) {
        _appPinKeypadScrambled.value = enabled
        dataStore.edit { it[APP_PIN_KEYPAD_SCRAMBLED] = enabled }
    }

    suspend fun setPinHash(hash: String) {
        dataStore.edit { it[PIN_HASH] = hash }
    }

    suspend fun getPinEnabled(): Boolean =
        dataStore.data.first()[PIN_ENABLED] ?: false

    suspend fun getPinHash(): String? =
        dataStore.data.first()[PIN_HASH]

    suspend fun getDuressPinEnabled(): Boolean =
        dataStore.data.first()[DURESS_PIN_ENABLED] ?: false

    suspend fun getDuressPinHash(): String? =
        dataStore.data.first()[DURESS_PIN_HASH]

    suspend fun setDuressPinEnabled(enabled: Boolean) {
        dataStore.edit { it[DURESS_PIN_ENABLED] = enabled }
    }

    suspend fun setDuressPinHash(hash: String) {
        dataStore.edit { it[DURESS_PIN_HASH] = hash }
    }

    suspend fun setImageLoadMode(mode: ImageLoadMode) {
        dataStore.edit { it[IMAGE_LOAD_MODE] = mode.prefValue }
    }

    suspend fun setVideoQuality(quality: VideoQuality) {
        dataStore.edit { it[VIDEO_QUALITY] = quality.prefValue }
    }

    suspend fun setVideoPlaybackMode(mode: VideoPlaybackMode) {
        dataStore.edit { it[VIDEO_PLAYBACK_MODE] = mode.prefValue }
    }

    suspend fun setVideoThumbnails(enabled: Boolean) {
        dataStore.edit { it[VIDEO_THUMBNAILS] = enabled }
    }

    suspend fun setImageCacheLightweight(enabled: Boolean) {
        _imageCacheLightweight.value = enabled
        dataStore.edit { it[IMAGE_CACHE_LIGHTWEIGHT] = enabled }
    }

    suspend fun setAvatarMode(mode: AvatarMode) {
        dataStore.edit { it[AVATAR_MODE] = mode.prefValue }
    }

    suspend fun setAvatarAnimated(animated: Boolean) {
        dataStore.edit { it[AVATAR_ANIMATED] = animated }
    }

    /** Persist the enabled notification-type filters (empty set = all on). */
    suspend fun setNotificationEnabledTypes(names: Set<String>) {
        dataStore.edit { it[NOTIFICATION_ENABLED_TYPES] = names }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.prefValue }
    }

    suspend fun setUiTheme(theme: UiTheme) {
        dataStore.edit { it[UI_THEME] = theme.prefValue }
    }

    suspend fun setUiDeclutterEnabled(enabled: Boolean) {
        _uiDeclutterEnabled.value = enabled
        dataStore.edit { it[UI_DECLUTTER_ENABLED] = enabled }
    }

    /** Custom accent color as "#RRGGBB", or null to use the default green. */
    suspend fun setAccentColor(hex: String?) {
        dataStore.edit { prefs ->
            if (hex == null) prefs.remove(ACCENT_COLOR)
            else prefs[ACCENT_COLOR] = hex
        }
    }

    suspend fun setScreenshotBlockEnabled(enabled: Boolean) {
        dataStore.edit { it[SCREENSHOT_BLOCK_ENABLED] = enabled }
    }

    suspend fun setToolboxToolOrder(order: List<String>) {
        _toolboxToolOrder.value = order
        dataStore.edit { it[TOOLBOX_TOOL_ORDER] = order.joinToString(",") }
    }

    suspend fun setXdcCustomTools(tools: List<XdcCustomTool>) {
        _xdcCustomTools.value = tools
        dataStore.edit { it[XDC_CUSTOM_TOOLS] = XdcCustomToolsCodec.encode(tools) }
    }

    suspend fun setToolboxScreenshotBlockEnabled(enabled: Boolean) {
        dataStore.edit { it[TOOLBOX_SCREENSHOT_BLOCK_ENABLED] = enabled }
    }

    suspend fun getToolboxPinEnabled(): Boolean =
        dataStore.data.first()[TOOLBOX_PIN_ENABLED] ?: false

    suspend fun getToolboxPinHash(): String? =
        dataStore.data.first()[TOOLBOX_PIN_HASH]

    suspend fun setToolboxPinEnabled(enabled: Boolean) {
        dataStore.edit { it[TOOLBOX_PIN_ENABLED] = enabled }
    }

    suspend fun setToolboxPinAutoLockMinutes(minutes: Int) {
        val safe = minutes.coerceIn(1,30)
        _toolboxPinAutoLockMinutes.value = safe
        dataStore.edit { it[TOOLBOX_PIN_AUTO_LOCK_MINUTES] = safe }
    }

    suspend fun setToolboxPinLastUnlockMillis(millis: Long) {
        _toolboxPinLastUnlockMillis.value = millis
        dataStore.edit { it[TOOLBOX_PIN_LAST_UNLOCK_MILLIS] = millis }
    }

    suspend fun getToolboxPinAutoLockMinutes(): Int = dataStore.data.first()[TOOLBOX_PIN_AUTO_LOCK_MINUTES] ?: 5
    suspend fun getToolboxPinLastUnlockMillis(): Long = dataStore.data.first()[TOOLBOX_PIN_LAST_UNLOCK_MILLIS] ?: 0L

    suspend fun setToolboxPinKeypadScrambled(enabled: Boolean) {
        _toolboxPinKeypadScrambled.value = enabled
        dataStore.edit { it[TOOLBOX_PIN_KEYPAD_SCRAMBLED] = enabled }
    }

    suspend fun setToolboxPinHash(hash: String) {
        dataStore.edit { it[TOOLBOX_PIN_HASH] = hash }
    }

    suspend fun getToolboxDuressPinEnabled(): Boolean =
        dataStore.data.first()[TOOLBOX_DURESS_PIN_ENABLED] ?: false

    suspend fun getToolboxDuressPinHash(): String? =
        dataStore.data.first()[TOOLBOX_DURESS_PIN_HASH]

    suspend fun setToolboxDuressPinEnabled(enabled: Boolean) {
        dataStore.edit { it[TOOLBOX_DURESS_PIN_ENABLED] = enabled }
    }

    suspend fun setToolboxDuressPinHash(hash: String) {
        dataStore.edit { it[TOOLBOX_DURESS_PIN_HASH] = hash }
    }

    suspend fun setBlockedPubkeys(pubkeys: Set<String>) {
        _blockedPubkeys.value = pubkeys
        dataStore.edit { it[BLOCKED_PUBKEYS] = pubkeys }
    }

    suspend fun getParentalPinEnabled(): Boolean =
        dataStore.data.first()[PARENTAL_PIN_ENABLED] ?: false

    suspend fun getParentalPinHash(): String? =
        dataStore.data.first()[PARENTAL_PIN_HASH]

    suspend fun setParentalPinEnabled(enabled: Boolean) {
        _parentalPinEnabled.value = enabled
        dataStore.edit { it[PARENTAL_PIN_ENABLED] = enabled }
    }

    suspend fun setParentalPinHash(hash: String) {
        dataStore.edit { it[PARENTAL_PIN_HASH] = hash }
    }

    // ── Blossom media uploads ────────────────────────────────────────────────

    suspend fun setBlossomServers(servers: Set<String>) {
        _blossomServers.value = servers
        dataStore.edit { it[BLOSSOM_SERVERS] = servers }
    }

    suspend fun addBlossomServer(url: String) {
        // Accepts what people actually type: "blossom.example.com" gets an
        // https:// scheme, trailing slashes are dropped, junk is ignored.
        val normalized = BlossomServerUrl.normalize(url) ?: return
        val next = (_blossomServers.value + normalized)
        _blossomServers.value = next
        dataStore.edit { it[BLOSSOM_SERVERS] = next }
    }

    suspend fun removeBlossomServer(url: String) {
        val next = _blossomServers.value - url
        _blossomServers.value = next
        dataStore.edit { it[BLOSSOM_SERVERS] = next }
        if (_blossomDefaultServer.value == url) {
            _blossomDefaultServer.value = null
            dataStore.edit { it.remove(BLOSSOM_DEFAULT_SERVER) }
        }
    }

    suspend fun setBlossomDefaultServer(url: String?) {
        _blossomDefaultServer.value = url
        dataStore.edit {
            if (url == null) it.remove(BLOSSOM_DEFAULT_SERVER)
            else it[BLOSSOM_DEFAULT_SERVER] = url
        }
    }

    suspend fun setBlossomCompress(enabled: Boolean) {
        _blossomCompress.value = enabled
        dataStore.edit { it[BLOSSOM_COMPRESS] = enabled }
    }

    suspend fun setBlossomAudioCompress(enabled: Boolean) {
        _blossomAudioCompress.value = enabled
        dataStore.edit { it[BLOSSOM_AUDIO_COMPRESS] = enabled }
    }

    suspend fun setNoteMenuModerationEnabled(enabled: Boolean) {
        _noteMenuModerationEnabled.value = enabled
        dataStore.edit { it[NOTE_MENU_MODERATION] = enabled }
    }

    suspend fun setClientTagEnabled(enabled: Boolean) {
        _clientTagEnabled.value = enabled
        dataStore.edit { it[CLIENT_TAG_ENABLED] = enabled }
    }

    // ── External-wallet payments ────────────────────────────────────────────

    suspend fun setLightningZapsEnabled(enabled: Boolean) {
        _lightningZapsEnabled.value = enabled
        dataStore.edit { it[LIGHTNING_ZAPS_ENABLED] = enabled }
    }

    suspend fun setMoneroXapsEnabled(enabled: Boolean) {
        _moneroXapsEnabled.value = enabled
        dataStore.edit { it[MONERO_XAPS_ENABLED] = enabled }
    }

    /** Wipes removed wallet connection and payment preferences on upgrade. */
    private suspend fun migrateRemovedNwcPreferences() {
        dataStore.edit { prefs ->
            prefs.remove(LEGACY_NWC_ZAPS_ENABLED)
            prefs.remove(LEGACY_NWC_ZAP_AMOUNT_SATS)
            prefs.remove(LEGACY_NWC_ZAP_AMOUNTS)
            prefs.remove(LEGACY_NWC_USER_DISCONNECTED)
            prefs.remove(LEGACY_NWC_WALLET_PUBKEY)
            prefs.remove(LEGACY_NWC_RELAY_URLS)
            prefs.remove(LEGACY_NWC_CLIENT_PUBKEY)
            prefs.remove(LEGACY_NWC_ENCRYPTED_SECRET)
            prefs.remove(LEGACY_NWC_LUD16)
            prefs.remove(LEGACY_NWC_PAID_ZAP_IDS)
            prefs.remove(LEGACY_NWC_UNCERTAIN_ZAP_IDS)
            // Wipe old preset keys (now handled via NIP-A3)
            prefs.remove(LIGHTNING_ZAP_AMOUNT_SATS)
            prefs.remove(LIGHTNING_ZAP_AMOUNTS)
            prefs.remove(LEGACY_NWC_ZAPS_ENABLED)
        }
    }

    // ── Screen time (parental) ───────────────────────────────────────────────

    suspend fun setScreenTimeEnabled(enabled: Boolean) {
        _screenTimeEnabled.value = enabled
        dataStore.edit { it[SCREEN_TIME_ENABLED] = enabled }
    }

    suspend fun setScreenTimeMinutes(minutes: Int) {
        _screenTimeMinutes.value = minutes
        dataStore.edit { it[SCREEN_TIME_MINUTES] = minutes }
    }

    /** Today's already-used screen time in seconds (rolls over at midnight). */
    suspend fun getScreenTimeUsedSeconds(): Long {
        val today = java.time.LocalDate.now().toString()
        val date = dataStore.data.first()[SCREEN_TIME_DATE]
        val used = dataStore.data.first()[SCREEN_TIME_USED_SECONDS] ?: 0L
        return if (date == today) used else 0L
    }

    suspend fun addScreenTimeSeconds(seconds: Long) {
        val today = java.time.LocalDate.now().toString()
        val prevDate = dataStore.data.first()[SCREEN_TIME_DATE]
        val prev = if (prevDate == today) dataStore.data.first()[SCREEN_TIME_USED_SECONDS] ?: 0L else 0L
        dataStore.edit {
            it[SCREEN_TIME_DATE] = today
            it[SCREEN_TIME_USED_SECONDS] = prev + seconds
        }
    }

    /** Parental unlock grace: until when (epoch millis) the app stays open. */
    suspend fun getScreenTimeGraceUntil(): Long =
        dataStore.data.first()[SCREEN_TIME_GRACE_UNTIL] ?: 0L

    suspend fun setScreenTimeGraceUntil(epochMillis: Long) {
        dataStore.edit { it[SCREEN_TIME_GRACE_UNTIL] = epochMillis }
    }

    suspend fun toggleBlockPubkey(pubkey: String) {
        val current = _blockedPubkeys.value
        val next = if (pubkey in current) current - pubkey else current + pubkey
        _blockedPubkeys.value = next
        dataStore.edit { it[BLOCKED_PUBKEYS] = next }
    }

    suspend fun setHideNsfw(enabled: Boolean) {
        _hideNsfw.value = enabled
        dataStore.edit { it[HIDE_NSFW] = enabled }
    }

    suspend fun setBleepWords(words: Set<String>) {
        _bleepWords.value = words
        dataStore.edit { it[BLEEP_WORDS] = words }
    }

    suspend fun addBleepWord(word: String) {
        val next = _bleepWords.value + word.trim().lowercase()
        _bleepWords.value = next
        dataStore.edit { it[BLEEP_WORDS] = next }
    }

    suspend fun removeBleepWord(word: String) {
        val next = _bleepWords.value - word
        _bleepWords.value = next
        dataStore.edit { it[BLEEP_WORDS] = next }
    }

    suspend fun setHideWords(words: Set<String>) {
        _hideWords.value = words
        dataStore.edit { it[HIDE_WORDS] = words }
    }

    suspend fun addHideWord(word: String) {
        val next = _hideWords.value + word.trim().lowercase()
        _hideWords.value = next
        dataStore.edit { it[HIDE_WORDS] = next }
    }

    suspend fun removeHideWord(word: String) {
        val next = _hideWords.value - word
        _hideWords.value = next
        dataStore.edit { it[HIDE_WORDS] = next }
    }

    suspend fun setFollowersEnabled(enabled: Boolean) {
        _followersEnabled.value = enabled
        dataStore.edit { it[FOLLOWERS_ENABLED] = enabled }
    }

    suspend fun setFollowersDisclaimerShown(shown: Boolean) {
        _followersDisclaimerShown.value = shown
        dataStore.edit { it[FOLLOWERS_DISCLAIMER_SHOWN] = shown }
    }

    // ── Stealth: notepad front ────────────────────────────────────────────────

    suspend fun setNotepadFrontEnabled(enabled: Boolean) {
        _notepadFrontEnabled.value = enabled
        dataStore.edit { it[NOTEPAD_FRONT_ENABLED] = enabled }
        // Keep current session unlocked so enabling doesn't immediately hide
        // the full app — stealth only takes effect after the process restarts.
        if (enabled) _decoyUnlocked.value = true else _decoyUnlocked.value = false
    }

    suspend fun setNotepadFrontDisclaimerShown(shown: Boolean) {
        _notepadFrontDisclaimerShown.value = shown
        dataStore.edit { it[NOTEPAD_FRONT_DISCLAIMER_SHOWN] = shown }
    }

    /**
     * Removes all geohash identity data: saved channels, the posting nickname
     * and the per-install identity seed. Used by the toolbox duress wipe so a
     * wiped device can't be re-identified through its geohash identity.
     */
    suspend fun clearGeohashData() {
        _geohashChannels.value = emptyList()
        _geohashNickname.value = ""
        dataStore.edit { prefs ->
            prefs.remove(GEOHASH_CHANNELS)
            prefs.remove(GEOHASH_NICKNAME)
            prefs.remove(GEOHASH_IDENTITY_SEED)
        }
    }

    suspend fun setGeohashChannels(entries: List<GeohashChannelEntry>) {
        _geohashChannels.value = entries
        dataStore.edit { it[GEOHASH_CHANNELS] = GeohashChannelStore.encode(entries) }
    }

    suspend fun setGeohashCacheMillis(millis: Long) {
        require(millis in GEOHASH_CACHE_OPTIONS)
        _geohashCacheMillis.value = millis
        dataStore.edit { it[GEOHASH_CACHE_MILLIS] = millis }
    }

    suspend fun setGeohashNickname(name: String) {
        _geohashNickname.value = name
        dataStore.edit { it[GEOHASH_NICKNAME] = name }
    }

    /** Returns the persistent geohash identity seed (32 random bytes), creating it if absent. */
    suspend fun geohashIdentitySeed(): ByteArray {
        var result: ByteArray? = null
        dataStore.edit { prefs ->
            val existing = prefs[GEOHASH_IDENTITY_SEED]?.let {
                runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
            }?.takeIf { it.size == 32 }
            val seed = existing ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            if (existing == null) prefs[GEOHASH_IDENTITY_SEED] = java.util.Base64.getEncoder().encodeToString(seed)
            result = seed
        }
        return checkNotNull(result)
    }

    // ── Global relays (separate from personal relay list) ───────────────────

    private fun normalizeGlobalRelay(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "wss" && scheme != "ws") return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return trimmed
    }

    private fun Preferences.globalConfigured(): Set<String> =
        this[GLOBAL_RELAYS]?.mapNotNull { normalizeGlobalRelay(it) }?.toSet() ?: GLOBAL_RELAY_DEFAULTS

    private fun Preferences.globalEnabled(configured: Set<String>): Set<String> =
        this[GLOBAL_ACTIVE_RELAYS]?.mapNotNull { normalizeGlobalRelay(it) }?.toSet()?.intersect(configured) ?: configured

    suspend fun setGlobalRelays(relays: Set<String>) {
        val next = relays.mapNotNull { normalizeGlobalRelay(it) }.toSet()
        dataStore.edit { prefs ->
            val active = prefs.globalEnabled(prefs.globalConfigured()).intersect(next)
            prefs[GLOBAL_RELAYS] = next
            prefs[GLOBAL_ACTIVE_RELAYS] = active
        }
    }

    suspend fun addGlobalRelay(url: String) {
        val normalized = normalizeGlobalRelay(url) ?: return
        dataStore.edit { prefs ->
            val current = prefs.globalConfigured()
            if (normalized !in current) {
                prefs[GLOBAL_RELAYS] = current + normalized
                prefs[GLOBAL_ACTIVE_RELAYS] = prefs.globalEnabled(current) + normalized
            }
        }
    }

    suspend fun removeGlobalRelay(url: String) {
        val normalized = normalizeGlobalRelay(url) ?: return
        dataStore.edit { prefs ->
            val current = prefs.globalConfigured()
            prefs[GLOBAL_RELAYS] = current - normalized
            prefs[GLOBAL_ACTIVE_RELAYS] = prefs.globalEnabled(current) - normalized
        }
    }

    suspend fun setGlobalActiveRelays(active: Set<String>) {
        val normalized = active.mapNotNull { normalizeGlobalRelay(it) }.toSet()
        dataStore.edit { prefs -> prefs[GLOBAL_ACTIVE_RELAYS] = normalized.intersect(prefs.globalConfigured()) }
    }

    suspend fun toggleGlobalRelayActive(url: String) {
        val normalized = normalizeGlobalRelay(url) ?: return
        dataStore.edit { prefs ->
            val current = prefs.globalConfigured()
            if (normalized in current) {
                val active = prefs.globalEnabled(current)
                prefs[GLOBAL_ACTIVE_RELAYS] = if (normalized in active) active - normalized else active + normalized
            }
        }
    }

    suspend fun soloGlobalRelay(url: String) {
        val normalized = normalizeGlobalRelay(url) ?: return
        dataStore.edit { prefs ->
            if (normalized in prefs.globalConfigured()) prefs[GLOBAL_ACTIVE_RELAYS] = setOf(normalized)
        }
    }

    suspend fun enableAllGlobalRelays() {
        dataStore.edit { prefs -> prefs[GLOBAL_ACTIVE_RELAYS] = prefs.globalConfigured() }
    }

    suspend fun resetGlobalRelays() {
        dataStore.edit { prefs ->
            prefs[GLOBAL_RELAYS] = GLOBAL_RELAY_DEFAULTS
            prefs[GLOBAL_ACTIVE_RELAYS] = GLOBAL_RELAY_DEFAULTS
        }
    }

}
