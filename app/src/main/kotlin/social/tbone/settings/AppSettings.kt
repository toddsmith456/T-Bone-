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
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypted-at-rest NWC connection material. Never contains the raw URI secret. */
data class StoredNwcConnection(
    val walletPubkey: String,
    val relayUrls: List<String>,
    val clientPubkey: String,
    val encryptedSecretBase64: String,
    val lud16: String?,
)

@Singleton
class AppSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    companion object {
        private val TOR_ENABLED = booleanPreferencesKey("tor_enabled")
        // False until the user (or auto-detect) explicitly sets the preference.
        // Lets first-run logic distinguish "never set" from "user chose off".
        private val TOR_EXPLICITLY_SET = booleanPreferencesKey("tor_explicitly_set")
        private val LAST_VIEWED_NOTIFICATIONS_AT = longPreferencesKey("last_viewed_notifications_at")
        private val PIN_ENABLED = booleanPreferencesKey("pin_enabled")
        private val PIN_HASH = stringPreferencesKey("pin_hash")
        // Duress pin: entering it on the lock screen wipes all app data instead
        // of unlocking. Default OFF.
        private val DURESS_PIN_ENABLED = booleanPreferencesKey("duress_pin_enabled")
        private val DURESS_PIN_HASH = stringPreferencesKey("duress_pin_hash")
        private val IMAGE_LOAD_MODE = stringPreferencesKey("image_load_mode")
        private val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        private val VIDEO_PLAYBACK_MODE = stringPreferencesKey("video_playback_mode")
        private val VIDEO_THUMBNAILS = booleanPreferencesKey("video_thumbnails")
        private val AVATAR_MODE = stringPreferencesKey("avatar_mode")
        private val AVATAR_ANIMATED = booleanPreferencesKey("avatar_animated")
        // Enabled notification-type filters (names of NotifFilter). Empty set = all on.
        private val NOTIFICATION_ENABLED_TYPES = stringSetPreferencesKey("notification_enabled_types")
        // Color theme: light / dark / cream.
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        // Custom accent color as "#RRGGBB"; null/absent = default green.
        private val ACCENT_COLOR = stringPreferencesKey("accent_color")
        // UI engine: bony (default) vs youniversal
        private val UI_THEME = stringPreferencesKey("ui_theme")
        // Screenshot blocker (FLAG_SECURE) — app-wide.
        private val SCREENSHOT_BLOCK_ENABLED = booleanPreferencesKey("screenshot_block_enabled")
        // Toolbox-only settings.
        private val TOOLBOX_SCREENSHOT_BLOCK_ENABLED = booleanPreferencesKey("toolbox_screenshot_block_enabled")
        private val TOOLBOX_PIN_ENABLED = booleanPreferencesKey("toolbox_pin_enabled")
        private val TOOLBOX_PIN_HASH = stringPreferencesKey("toolbox_pin_hash")
        private val TOOLBOX_DURESS_PIN_ENABLED = booleanPreferencesKey("toolbox_duress_pin_enabled")
        private val TOOLBOX_DURESS_PIN_HASH = stringPreferencesKey("toolbox_duress_pin_hash")
        private val TOOLBOX_TOOL_ORDER = stringPreferencesKey("toolbox_tool_order")
        // Geohash identity seed (base64, 32 bytes) — random per install, like Amethyst's remote-signer path.
        private val GEOHASH_IDENTITY_SEED = stringPreferencesKey("geohash_identity_seed")
        private val GEOHASH_CHANNELS = stringPreferencesKey("geohash_channels")
        private val GEOHASH_NICKNAME = stringPreferencesKey("geohash_nickname")
        // Content filtering: blocked pubkeys, NSFW hiding, word bleep/hide.
        private val BLOCKED_PUBKEYS = stringSetPreferencesKey("blocked_pubkeys")
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
        // Nostr Wallet Connect — the connection secret is stored only as an
        // Android-Keystore-encrypted blob. The raw NWC URI is never persisted.
        private val NWC_ZAPS_ENABLED = booleanPreferencesKey("nwc_zaps_enabled")
        private val NWC_ZAP_AMOUNT_SATS = longPreferencesKey("nwc_zap_amount_sats")
        private val NWC_ZAP_AMOUNTS = stringPreferencesKey("nwc_zap_amounts")
        // A deliberate Disconnect survives screen navigation and process restarts.
        private val NWC_USER_DISCONNECTED = booleanPreferencesKey("nwc_user_disconnected")
        private val NWC_WALLET_PUBKEY = stringPreferencesKey("nwc_wallet_pubkey")
        private val NWC_RELAY_URLS = stringPreferencesKey("nwc_relay_urls")
        private val NWC_CLIENT_PUBKEY = stringPreferencesKey("nwc_client_pubkey")
        private val NWC_ENCRYPTED_SECRET = stringPreferencesKey("nwc_encrypted_secret")
        private val NWC_LUD16 = stringPreferencesKey("nwc_lud16")
        private val NWC_PAID_ZAP_IDS = stringSetPreferencesKey("nwc_paid_zap_ids")
        private val NWC_UNCERTAIN_ZAP_IDS = stringSetPreferencesKey("nwc_uncertain_zap_ids")
        // Screen time (parental): daily allowance + rollover tracking.
        private val SCREEN_TIME_ENABLED = booleanPreferencesKey("screen_time_enabled")
        private val SCREEN_TIME_MINUTES = intPreferencesKey("screen_time_minutes")
        private val SCREEN_TIME_DATE = stringPreferencesKey("screen_time_date")
        private val SCREEN_TIME_USED_SECONDS = longPreferencesKey("screen_time_used_seconds")
        private val SCREEN_TIME_GRACE_UNTIL = longPreferencesKey("screen_time_grace_until")

        /** The three default Blossom servers, always available unless disabled. */
        val BLOSSOM_DEFAULTS = setOf(
            "https://nostr.download",
            "https://blossom.data.haus",
            "https://blossom.ditto.pub",
        )
    }

    private val _torEnabled = MutableStateFlow(false)
    val torEnabled = _torEnabled.asStateFlow()

    private val _torExplicitlySet = MutableStateFlow(false)
    val torExplicitlySet = _torExplicitlySet.asStateFlow()

    private val _lastViewedNotificationsAt = MutableStateFlow(0L)
    val lastViewedNotificationsAt = _lastViewedNotificationsAt.asStateFlow()

    private val _pinEnabled = MutableStateFlow(false)
    val pinEnabled = _pinEnabled.asStateFlow()

    private val _duressPinEnabled = MutableStateFlow(false)
    val duressPinEnabled = _duressPinEnabled.asStateFlow()

    private val _imageLoadMode = MutableStateFlow(ImageLoadMode.ON)
    val imageLoadMode = _imageLoadMode.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQuality.REGULAR)
    val videoQuality = _videoQuality.asStateFlow()

    private val _videoPlaybackMode = MutableStateFlow(VideoPlaybackMode.PLAY_ON_TAP)
    val videoPlaybackMode = _videoPlaybackMode.asStateFlow()

    private val _videoThumbnails = MutableStateFlow(true)
    val videoThumbnails = _videoThumbnails.asStateFlow()

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

    private val _screenshotBlockEnabled = MutableStateFlow(false)
    val screenshotBlockEnabled = _screenshotBlockEnabled.asStateFlow()

    private val _toolboxScreenshotBlockEnabled = MutableStateFlow(false)
    val toolboxScreenshotBlockEnabled = _toolboxScreenshotBlockEnabled.asStateFlow()

    private val _toolboxPinEnabled = MutableStateFlow(false)
    val toolboxPinEnabled = _toolboxPinEnabled.asStateFlow()

    private val _toolboxDuressPinEnabled = MutableStateFlow(false)
    val toolboxDuressPinEnabled = _toolboxDuressPinEnabled.asStateFlow()

    /** Ordered list of tool ids in the toolbox (including the NWC wallet tool). */
    private val _toolboxToolOrder = MutableStateFlow<List<String>>(emptyList())
    val toolboxToolOrder = _toolboxToolOrder.asStateFlow()

    /** Saved geohash channels (messenger contact list), in display order. */
    private val _geohashChannels = MutableStateFlow<List<GeohashChannelEntry>>(emptyList())
    val geohashChannels = _geohashChannels.asStateFlow()

    /** Consistent nickname used when posting to geohash channels. */
    private val _geohashNickname = MutableStateFlow("")
    val geohashNickname = _geohashNickname.asStateFlow()

    /** Pubkeys whose notes are completely hidden (block). */
    private val _blockedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val blockedPubkeys = _blockedPubkeys.asStateFlow()

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

    private val _nwcZapsEnabled = MutableStateFlow(false)
    val nwcZapsEnabled = _nwcZapsEnabled.asStateFlow()

    private val _nwcZapAmountSats = MutableStateFlow(21L)
    val nwcZapAmountSats = _nwcZapAmountSats.asStateFlow()

    private val _nwcZapAmounts = MutableStateFlow(ZapAmounts.DEFAULT)
    val nwcZapAmounts = _nwcZapAmounts.asStateFlow()

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

    init {
        scope.launch {
            dataStore.data.collect { prefs ->
                _torExplicitlySet.value = prefs[TOR_EXPLICITLY_SET] == true
                _torEnabled.value = prefs[TOR_ENABLED] ?: false
                _lastViewedNotificationsAt.value = prefs[LAST_VIEWED_NOTIFICATIONS_AT] ?: 0L
                _pinEnabled.value = prefs[PIN_ENABLED] ?: false
                _duressPinEnabled.value = prefs[DURESS_PIN_ENABLED] ?: false
                _imageLoadMode.value = ImageLoadMode.fromPref(prefs[IMAGE_LOAD_MODE])
                _videoQuality.value = VideoQuality.fromPref(prefs[VIDEO_QUALITY])
                _videoPlaybackMode.value = VideoPlaybackMode.fromPref(prefs[VIDEO_PLAYBACK_MODE])
                _videoThumbnails.value = prefs[VIDEO_THUMBNAILS] ?: true
                _avatarMode.value = AvatarMode.fromPref(prefs[AVATAR_MODE])
                _avatarAnimated.value = prefs[AVATAR_ANIMATED] ?: true
                _notificationEnabledTypes.value = prefs[NOTIFICATION_ENABLED_TYPES] ?: emptySet()
                _themeMode.value = ThemeMode.fromPref(prefs[THEME_MODE])
                _accentColor.value = prefs[ACCENT_COLOR]
                _uiTheme.value = UiTheme.fromPref(prefs[UI_THEME])
                _screenshotBlockEnabled.value = prefs[SCREENSHOT_BLOCK_ENABLED] ?: false
                _toolboxScreenshotBlockEnabled.value = prefs[TOOLBOX_SCREENSHOT_BLOCK_ENABLED] ?: false
                _toolboxPinEnabled.value = prefs[TOOLBOX_PIN_ENABLED] ?: false
                _toolboxDuressPinEnabled.value = prefs[TOOLBOX_DURESS_PIN_ENABLED] ?: false
                _toolboxToolOrder.value = prefs[TOOLBOX_TOOL_ORDER]
                    ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                _geohashChannels.value = prefs[GEOHASH_CHANNELS]?.let { GeohashChannelStore.decode(it) } ?: emptyList()
                _geohashNickname.value = prefs[GEOHASH_NICKNAME] ?: ""
                _blockedPubkeys.value = prefs[BLOCKED_PUBKEYS] ?: emptySet()
                _hideNsfw.value = prefs[HIDE_NSFW] ?: false
                _bleepWords.value = prefs[BLEEP_WORDS] ?: emptySet()
                _hideWords.value = prefs[HIDE_WORDS] ?: emptySet()
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
                _nwcZapsEnabled.value = prefs[NWC_ZAPS_ENABLED] ?: false
                _nwcZapAmountSats.value = ZapAmounts.validOrDefault(prefs[NWC_ZAP_AMOUNT_SATS])
                _nwcZapAmounts.value = ZapAmounts.parse(prefs[NWC_ZAP_AMOUNTS])
                _screenTimeEnabled.value = prefs[SCREEN_TIME_ENABLED] ?: false
                _screenTimeMinutes.value = prefs[SCREEN_TIME_MINUTES] ?: 120
            }
        }
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

    suspend fun setPinEnabled(enabled: Boolean) {
        dataStore.edit { it[PIN_ENABLED] = enabled }
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

    // ── Nostr Wallet Connect ─────────────────────────────────────────────────

    suspend fun setNwcZapsEnabled(enabled: Boolean) {
        _nwcZapsEnabled.value = enabled
        dataStore.edit { it[NWC_ZAPS_ENABLED] = enabled }
    }

    /**
     * The amount used when a zap control is activated directly. It is deliberately
     * separate from the editable preset list: users can send an arbitrary amount
     * without having to keep that amount as a quick-choice preset.
     */
    suspend fun setNwcZapAmountSats(amount: Long) {
        val valid = ZapAmounts.requireValid(amount)
        _nwcZapAmountSats.value = valid
        dataStore.edit { it[NWC_ZAP_AMOUNT_SATS] = valid }
    }

    suspend fun setNwcZapAmounts(amounts: List<Long>) {
        val clean = ZapAmounts.normalize(amounts)
        _nwcZapAmounts.value = clean
        dataStore.edit { it[NWC_ZAP_AMOUNTS] = clean.joinToString(",") }
    }

    suspend fun addNwcZapAmount(amount: Long) {
        setNwcZapAmounts(_nwcZapAmounts.value + ZapAmounts.requireValid(amount))
    }

    suspend fun removeNwcZapAmount(amount: Long) {
        val next = ZapAmounts.remove(_nwcZapAmounts.value, amount)
        if (next == _nwcZapAmounts.value) return
        setNwcZapAmounts(next)
    }

    suspend fun moveNwcZapAmount(from: Int, to: Int) {
        val next = ZapAmounts.move(_nwcZapAmounts.value, from, to)
        if (next == _nwcZapAmounts.value) return
        setNwcZapAmounts(next)
    }

    suspend fun setNwcUserDisconnected(disconnected: Boolean) {
        dataStore.edit { it[NWC_USER_DISCONNECTED] = disconnected }
    }

    suspend fun isNwcUserDisconnected(): Boolean = dataStore.data.first()[NWC_USER_DISCONNECTED] ?: false

    suspend fun getNwcConnection(): StoredNwcConnection? {
        val prefs = dataStore.data.first()
        val wallet = prefs[NWC_WALLET_PUBKEY] ?: return null
        val relays = prefs[NWC_RELAY_URLS]
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val client = prefs[NWC_CLIENT_PUBKEY] ?: return null
        val encrypted = prefs[NWC_ENCRYPTED_SECRET] ?: return null
        if (relays.isEmpty()) return null
        return StoredNwcConnection(wallet, relays, client, encrypted, prefs[NWC_LUD16])
    }

    suspend fun setNwcConnection(connection: StoredNwcConnection) {
        dataStore.edit {
            it[NWC_WALLET_PUBKEY] = connection.walletPubkey
            it[NWC_RELAY_URLS] = connection.relayUrls.joinToString("\n")
            it[NWC_CLIENT_PUBKEY] = connection.clientPubkey
            it[NWC_ENCRYPTED_SECRET] = connection.encryptedSecretBase64
            if (connection.lud16.isNullOrBlank()) it.remove(NWC_LUD16)
            else it[NWC_LUD16] = connection.lud16
        }
    }

    suspend fun clearNwcConnection() {
        dataStore.edit {
            it.remove(NWC_WALLET_PUBKEY)
            it.remove(NWC_RELAY_URLS)
            it.remove(NWC_CLIENT_PUBKEY)
            it.remove(NWC_ENCRYPTED_SECRET)
            it.remove(NWC_LUD16)
        }
    }

    /** Event ids only; never persist invoices, preimages, or NWC secrets. */
    suspend fun nwcPaidZapIds(): Set<String> = dataStore.data.first()[NWC_PAID_ZAP_IDS].orEmpty()
    suspend fun nwcUncertainZapIds(): Set<String> = dataStore.data.first()[NWC_UNCERTAIN_ZAP_IDS].orEmpty()

    suspend fun markNwcZapPaid(eventId: String) {
        dataStore.edit { it[NWC_PAID_ZAP_IDS] = (it[NWC_PAID_ZAP_IDS].orEmpty() + eventId).toList().takeLast(500).toSet() }
    }

    suspend fun markNwcZapUncertain(eventId: String) {
        dataStore.edit { it[NWC_UNCERTAIN_ZAP_IDS] = (it[NWC_UNCERTAIN_ZAP_IDS].orEmpty() + eventId).toList().takeLast(500).toSet() }
    }

    /** Called once the wallet's late answer resolves a payment that had timed out. */
    suspend fun clearNwcZapUncertain(eventId: String) {
        dataStore.edit { it[NWC_UNCERTAIN_ZAP_IDS] = it[NWC_UNCERTAIN_ZAP_IDS].orEmpty() - eventId }
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

    suspend fun setGeohashNickname(name: String) {
        _geohashNickname.value = name
        dataStore.edit { it[GEOHASH_NICKNAME] = name }
    }

    /** Returns the persistent geohash identity seed (32 random bytes), creating it if absent. */
    suspend fun geohashIdentitySeed(): ByteArray {
        val existing = dataStore.data.first()[GEOHASH_IDENTITY_SEED]
        if (existing != null) {
            runCatching { return java.util.Base64.getDecoder().decode(existing) }.getOrNull()
        }
        val seed = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val encoded = java.util.Base64.getEncoder().encodeToString(seed)
        dataStore.edit { it[GEOHASH_IDENTITY_SEED] = encoded }
        return seed
    }
}


/** Validation and persistence helpers for user-editable zap quick choices. */
object ZapAmounts {
    const val MIN_SATS = 1L
    const val MAX_SATS = 1_000_000L
    const val MAX_PRESETS = 12
    val DEFAULT = listOf(21L, 100L, 500L)

    fun requireValid(amount: Long): Long = amount.also {
        require(it in MIN_SATS..MAX_SATS) { "zap amount must be between $MIN_SATS and $MAX_SATS sats" }
    }

    fun validOrDefault(amount: Long?): Long = amount?.takeIf { it in MIN_SATS..MAX_SATS } ?: DEFAULT.first()

    fun normalize(amounts: List<Long>): List<Long> {
        val clean = amounts.filter { it in MIN_SATS..MAX_SATS }.distinct().take(MAX_PRESETS)
        return clean.ifEmpty { DEFAULT }
    }

    fun parse(raw: String?): List<Long> = normalize(
        raw.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() },
    )

    /**
     * Removes one preset without ever leaving the user with zero choices.
     * An empty preset list is normalized to defaults when loading older data,
     * but an explicit delete of the final visible preset must be a no-op.
     */
    fun remove(amounts: List<Long>, amount: Long): List<Long> =
        if (amounts.size <= 1) amounts else amounts.filterNot { it == amount }

    fun move(amounts: List<Long>, from: Int, to: Int): List<Long> {
        if (from !in amounts.indices || to !in amounts.indices) return amounts
        val next = amounts.toMutableList()
        next.add(to, next.removeAt(from))
        return next
    }
}
