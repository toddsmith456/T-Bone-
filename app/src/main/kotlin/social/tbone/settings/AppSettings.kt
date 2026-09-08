package social.tbone.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
        private val AVATAR_MODE = stringPreferencesKey("avatar_mode")
        private val AVATAR_ANIMATED = booleanPreferencesKey("avatar_animated")
        // Enabled notification-type filters (names of NotifFilter). Empty set = all on.
        private val NOTIFICATION_ENABLED_TYPES = stringSetPreferencesKey("notification_enabled_types")
        // Color theme: light / dark / cream.
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        // Custom accent color as "#RRGGBB"; null/absent = default green.
        private val ACCENT_COLOR = stringPreferencesKey("accent_color")
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
        // Blossom media uploads: enabled servers (defaults + custom), the
        // preferred default server, and whether uploads are compressed.
        private val BLOSSOM_SERVERS = stringSetPreferencesKey("blossom_servers")
        private val BLOSSOM_DEFAULT_SERVER = stringPreferencesKey("blossom_default_server")
        private val BLOSSOM_COMPRESS = booleanPreferencesKey("blossom_compress")
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

    private val _screenshotBlockEnabled = MutableStateFlow(false)
    val screenshotBlockEnabled = _screenshotBlockEnabled.asStateFlow()

    private val _toolboxScreenshotBlockEnabled = MutableStateFlow(false)
    val toolboxScreenshotBlockEnabled = _toolboxScreenshotBlockEnabled.asStateFlow()

    private val _toolboxPinEnabled = MutableStateFlow(false)
    val toolboxPinEnabled = _toolboxPinEnabled.asStateFlow()

    private val _toolboxDuressPinEnabled = MutableStateFlow(false)
    val toolboxDuressPinEnabled = _toolboxDuressPinEnabled.asStateFlow()

    /** Ordered list of tool ids in the toolbox ("notes","voice","geohash","calendar"). */
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

    /** Enabled Blossom servers (defaults + custom), for media uploads. */
    private val _blossomServers = MutableStateFlow<Set<String>>(BLOSSOM_DEFAULTS)
    val blossomServers = _blossomServers.asStateFlow()

    /** The preferred Blossom server, or null to pick randomly from the pool. */
    private val _blossomDefaultServer = MutableStateFlow<String?>(null)
    val blossomDefaultServer = _blossomDefaultServer.asStateFlow()

    /** Whether image/video uploads are compressed before sending. */
    private val _blossomCompress = MutableStateFlow(true)
    val blossomCompress = _blossomCompress.asStateFlow()

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
                _avatarMode.value = AvatarMode.fromPref(prefs[AVATAR_MODE])
                _avatarAnimated.value = prefs[AVATAR_ANIMATED] ?: true
                _notificationEnabledTypes.value = prefs[NOTIFICATION_ENABLED_TYPES] ?: emptySet()
                _themeMode.value = ThemeMode.fromPref(prefs[THEME_MODE])
                _accentColor.value = prefs[ACCENT_COLOR]
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
                _blossomServers.value = prefs[BLOSSOM_SERVERS]?.takeIf { it.isNotEmpty() } ?: BLOSSOM_DEFAULTS
                _blossomDefaultServer.value = prefs[BLOSSOM_DEFAULT_SERVER]
                _blossomCompress.value = prefs[BLOSSOM_COMPRESS] ?: true
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
        val normalized = url.trim().trimEnd('/')
        if (normalized.isBlank()) return
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
