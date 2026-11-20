package social.tbone.settings.search

/** Which screen a catalog entry lives on. */
enum class SettingsGroup(val label: String) {
    SETTINGS("settings"),
    CONTENT_FILTERS("content filters"),
    SCREEN_TIME("screen time"),
    RELAYS("relays"),
    BLOSSOM("blossom uploads"),
    ACCOUNTS("accounts"),
    REACTIONS("reactions"),
}

/**
 * One searchable control in Settings and the screens Settings links to.
 *
 * [id] is the contract between this catalog and the UI: every entry must be
 * claimed by exactly one anchor in the composables (see
 * `SettingsSearchAnchorTest`), so a search hit always has somewhere to land
 * and no anchor can exist without being findable.
 */
data class SettingsEntry(
    val id: String,
    val group: SettingsGroup,
    /** Visual block this row lives in (see [SettingsSections]). */
    val section: String = "",
    /** Stable key used by the host screen to scroll and highlight the row. */
    val anchor: String,
    val title: String,
    val subtitle: String = "",
    /** Extra words: feature names, jargon, colloquial names, old labels. */
    val keywords: List<String> = emptyList(),
) {
    val breadcrumb: String get() = group.label
}

/**
 * Every searchable settings row.
 *
 * Grouped in the same order the screens present them so review diffs stay
 * readable. Only rows a person would plausibly search for are listed; pure
 * chrome (back arrows, "ADD", "CANCEL") is intentionally excluded.
 */
object SettingsCatalog {

    private fun e(
        id: String,
        group: SettingsGroup,
        section: String,
        anchor: String,
        title: String,
        subtitle: String = "",
        keywords: String = "",
    ) = SettingsEntry(
        id = id,
        group = group,
        section = section,
        anchor = anchor,
        title = title,
        subtitle = subtitle,
        keywords = keywords.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
    )

    val entries: List<SettingsEntry> = listOf(
        // ── Settings: top level ───────────────────────────────────────────
        e(
            "settings.ui_declutter", SettingsGroup.SETTINGS, "set_ui_declutter", "ui_declutter",
            "UI declutter", "compact tabs and collapsible Settings categories",
            "compact,collapsible,sections,condensed,layout",
        ),
        e(
            "settings.tor_strip", SettingsGroup.SETTINGS, "set_tor_strip", "tor_strip",
            "Tor active", "all relay traffic routed through Orbot",
            "tor,orbot,proxy,onion,network,privacy",
        ),
        e(
            "settings.account", SettingsGroup.SETTINGS, "set_account", "account",
            "Account", "active identity, add or manage accounts",
            "profile,identity,signer,keys,npub,switch",
        ),
        e(
            "settings.add_account", SettingsGroup.SETTINGS, "set_account", "add_account",
            "add account", "sign in with another Nostr identity",
            "login,signin,new,identity,nsec,bunker,amber",
        ),
        e(
            "settings.relays", SettingsGroup.SETTINGS, "set_relays", "relays",
            "manage relays", "relay connection list, global and personal relays",
            "relay,relays,server,connection,websocket,url",
        ),
        e(
            "settings.lists", SettingsGroup.SETTINGS, "set_lists", "lists",
            "follow lists", "following, followers, mutes — offline or relay",
            "follows,followers,mutes,people,offline,publish",
        ),
        e(
            "settings.content_filters", SettingsGroup.SETTINGS, "set_content_filters", "content_filters",
            "content filters", "filters, parental controls, screen time",
            "nsfw,explicit,hide,bleep,words,moderation,child,limit",
        ),
        e(
            "settings.home_feeds", SettingsGroup.SETTINGS, "set_home_feeds", "home_feeds",
            "home feeds", "swap, reorder or hide the tabs on the home screen",
            "feeds,tabs,timeline,polls,hashtag,clock,reorder",
        ),
        e(
            "settings.note_moderation", SettingsGroup.SETTINGS, "set_note_actions", "note_moderation",
            "show mute/report actions", "adds mute poster and NIP-56 report to note menus",
            "mute,block,report,flag,moderation,menu",
        ),
        e(
            "settings.client_tag", SettingsGroup.SETTINGS, "set_note_actions", "client_tag",
            "add T-Bone client tag", "adds a public NIP-89 client tag to events you sign",
            "nip89,attribution,client,metadata,event",
        ),
        e(
            "settings.write_switcher", SettingsGroup.SETTINGS, "set_note_actions", "write_switcher",
            "Write-tab account switcher", "how the active account appears beside SEND",
            "compose,write,switcher,avatar,hash,profile,name",
        ),
        e(
            "settings.reactions", SettingsGroup.SETTINGS, "set_reactions", "reactions",
            "reactions", "multi emoji reactions and the saved emoji picker",
            "emoji,likes,heart,picker,saved",
        ),
        e(
            "settings.profile_picture", SettingsGroup.SETTINGS, "set_media", "profile_picture",
            "profile picture & banner", "upload or change your avatar and banner",
            "avatar,pfp,banner,image,upload,photo",
        ),
        e(
            "settings.blossom", SettingsGroup.SETTINGS, "set_media", "blossom",
            "blossom uploads", "media upload servers",
            "upload,server,host,media,blossom,nostr.download",
        ),
        e(
            "settings.inline_images", SettingsGroup.SETTINGS, "set_media", "inline_images",
            "inline images", "auto-load images in the feed, or lower quality for speed",
            "images,pictures,photos,media,load,quality,low,off,preview",
        ),
        e(
            "settings.video_quality", SettingsGroup.SETTINGS, "set_media", "video_quality",
            "video quality", "how much video data an inline player may request",
            "video,videos,quality,low,off,bandwidth,data",
        ),
        e(
            "settings.video_playback", SettingsGroup.SETTINGS, "set_media", "video_playback",
            "video playback", "load and play on tap, preload, or autoplay",
            "video,autoplay,play,preload,tap,inline",
        ),
        e(
            "settings.media_background_playback", SettingsGroup.SETTINGS, "set_media", "media_background_playback",
            "background playback", "continue full-screen video audio and Audio Player music when leaving or closing the player",
            "audio,music,background,lock screen,notification,continue,keep playing",
        ),
        e(
            "settings.video_thumbnails", SettingsGroup.SETTINGS, "set_media", "video_thumbnails",
            "video thumbnails", "show the preview still a post supplies for its video",
            "video,thumbnails,thumbs,preview,still,poster",
        ),
        e(
            "settings.media_fitted", SettingsGroup.SETTINGS, "set_media", "media_fitted",
            "fit media to its real shape", "pictures and videos keep their own proportions",
            "aspect,ratio,shape,crop,layout,blurred,blur",
        ),
        e(
            "settings.avatar_style", SettingsGroup.SETTINGS, "set_avatars", "avatar_style",
            "avatar style", "initial squares, low resolution, or full quality",
            "avatar,pfp,picture,initials,resolution,quality",
        ),
        e(
            "settings.avatar_animated", SettingsGroup.SETTINGS, "set_avatars", "avatar_animated",
            "animated avatars", "play GIF avatars or show a still picture",
            "gif,animation,animated,still,avatar",
        ),
        e(
            "settings.cache_preset", SettingsGroup.SETTINGS, "set_storage_cache", "cache_preset",
            "cache level", "storage preset: ultra-light, compact, balanced, performance, turbo",
            "storage,cache,preset,space,disk,memory,lightweight",
        ),
        e(
            "settings.cache_usage", SettingsGroup.SETTINGS, "set_storage_cache", "cache_usage",
            "cache usage", "how much space avatars, feed photos, videos and audio take",
            "storage,cache,size,usage,space,disk",
        ),
        e(
            "settings.clear_photos", SettingsGroup.SETTINGS, "set_storage_cache", "clear_photos",
            "clear photos", "delete cached feed images",
            "cache,clear,delete,images,pictures,photos",
        ),
        e(
            "settings.clear_videos", SettingsGroup.SETTINGS, "set_storage_cache", "clear_videos",
            "clear videos", "delete cached video and audio",
            "cache,clear,delete,video,audio,media",
        ),
        e(
            "settings.clear_avatars", SettingsGroup.SETTINGS, "set_storage_cache", "clear_avatars",
            "clear avatars", "delete cached profile pictures",
            "cache,clear,delete,avatars,pfp,pictures",
        ),
        e(
            "settings.clear_all_cache", SettingsGroup.SETTINGS, "set_storage_cache", "clear_all_cache",
            "clear all cache", "delete every cached file",
            "cache,clear,delete,wipe,everything,reset",
        ),
        e(
            "settings.database", SettingsGroup.SETTINGS, "set_storage_cache", "database",
            "database", "local note and profile database",
            "storage,db,notes,profiles,size",
        ),
        e(
            "settings.ui_theme", SettingsGroup.SETTINGS, "set_theme_engine", "ui_theme",
            "UI theme", "T-Bone classic or Youniversal",
            "theme,engine,look,design,system,youniversal,classic",
        ),
        e(
            "settings.theme_mode", SettingsGroup.SETTINGS, "set_appearance", "theme_mode",
            "theme", "light, dark or cream",
            "dark,light,cream,night,black,amoled,paper,color,scheme",
        ),
        e(
            "settings.accent_color", SettingsGroup.SETTINGS, "set_appearance", "accent_color",
            "accent color", "replaces the default green",
            "color,colour,accent,highlight,brand,green,theme",
        ),
        e(
            "settings.material_you", SettingsGroup.SETTINGS, "set_appearance", "material_you",
            "Material You", "derive the palette from your wallpaper (Android 12+)",
            "wallpaper,dynamic,color,palette,monet,android12",
        ),
        e(
            "settings.accent_seed", SettingsGroup.SETTINGS, "set_appearance", "accent_seed",
            "accent seed", "brand, teal, rose, forest, amber or violet",
            "color,seed,palette,brand,teal,rose,forest,amber,violet",
        ),
        e(
            "settings.corner_radius", SettingsGroup.SETTINGS, "set_appearance", "corner_radius",
            "corner radius", "how rounded corners are, as a percentage of the default",
            "rounded,corners,radius,shape,curves",
        ),
        e(
            "settings.text_size", SettingsGroup.SETTINGS, "set_appearance", "text_size",
            "text size", "app font scale as a percentage of the default",
            "font,size,text,zoom,scale,bigger,smaller",
        ),
        e(
            "settings.font", SettingsGroup.SETTINGS, "set_appearance", "font",
            "app font", "the typeface used across the app",
            "font,typeface,text,mono,jetbrains",
        ),
        e(
            "settings.background", SettingsGroup.SETTINGS, "set_appearance", "background",
            "background", "background style of the theme",
            "background,wallpaper,style,surface",
        ),
        e(
            "settings.contrast", SettingsGroup.SETTINGS, "set_appearance", "contrast",
            "contrast", "high contrast text and borders",
            "contrast,accessibility,legibility,readability",
        ),
        e(
            "settings.animate_theme", SettingsGroup.SETTINGS, "set_appearance", "animate_theme",
            "animate theme changes", "morph colors instead of snapping",
            "animation,transition,fade,color,theme",
        ),
        e(
            "settings.youniversal_reset", SettingsGroup.SETTINGS, "set_appearance", "youniversal_reset",
            "reset Youniversal to defaults", "restore the stock look, font scale and corners",
            "reset,defaults,restore,youniversal,theme",
        ),
        e(
            "settings.confirm_links", SettingsGroup.SETTINGS, "set_privacy", "confirm_links",
            "confirm before opening links", "ask before opening an external link in another app",
            "external,links,browser,intent,confirm,safety",
        ),
        e(
            "settings.orbot", SettingsGroup.SETTINGS, "set_privacy", "orbot",
            "route via tor", "send relay traffic through Orbot",
            "tor,orbot,proxy,onion,privacy,vpn",
        ),
        e(
            "settings.orbot_install", SettingsGroup.SETTINGS, "set_privacy", "orbot_install",
            "install orbot", "open the official Orbot download page",
            "tor,orbot,download,install,proxy",
        ),
        e(
            "settings.orbot_check_upload", SettingsGroup.SETTINGS, "set_privacy", "orbot_check_upload",
            "check Orbot before upload", "verify Orbot is running before media uploads",
            "tor,orbot,upload,media,privacy",
        ),
        e(
            "settings.app_lock", SettingsGroup.SETTINGS, "set_privacy", "app_lock",
            "app lock (PIN)", "require a PIN to open the app",
            "pin,passcode,password,lock,security,biometric",
        ),
        e(
            "settings.auto_lock", SettingsGroup.SETTINGS, "set_privacy", "auto_lock",
            "app auto-lock timeout", "require the PIN after the app has been in the background",
            "pin,lock,timeout,background,idle,minutes",
        ),
        e(
            "settings.scramble_keypad", SettingsGroup.SETTINGS, "set_privacy", "scramble_keypad",
            "scramble app PIN keypad", "randomize digit positions for each PIN entry",
            "pin,keypad,scramble,random,shoulder,surfing,security",
        ),
        e(
            "settings.screenshot_block", SettingsGroup.SETTINGS, "set_privacy", "screenshot_block",
            "screenshot blocker", "prevents screenshots of the whole app",
            "screenshot,screen,record,capture,privacy,fl,secure",
        ),
        e(
            "settings.duress_pin", SettingsGroup.SETTINGS, "set_privacy", "duress_pin",
            "duress pin", "entering it attempts to clear local app data",
            "duress,panic,wipe,emergency,coerce,pin",
        ),
        e(
            "settings.notepad_front", SettingsGroup.SETTINGS, "set_stealth", "notepad_front",
            "Notepad front", "disguise the app as a notepad with a decoy vault",
            "stealth,disguise,decoy,notepad,hide,icon,launcher",
        ),
        e(
            "settings.share_logs", SettingsGroup.SETTINGS, "set_debug", "share_logs",
            "share logs", "export the debug log and performance numbers",
            // The performance section lives inside this dialog as its own toggle,
            // so it is reachable through this row rather than a separate anchor.
            "logs,debug,export,share,diagnostics,support,performance,startup,slow,lag,timing,cold start,metrics",
        ),
        e(
            "settings.version", SettingsGroup.SETTINGS, "set_debug", "version",
            "version", "installed version and update check",
            "version,update,upgrade,release,changelog,build,about",
        ),
        e(
            "settings.sign_out", SettingsGroup.SETTINGS, "set_debug", "sign_out",
            "sign out", "remove the active account from this device",
            "logout,signout,remove,account,exit",
        ),

        // ── Content filters ───────────────────────────────────────────────
        e(
            "filters.screen_time", SettingsGroup.CONTENT_FILTERS, "cf_parental_controls", "screen_time",
            "screen time", "daily app limit enforced with the parental pin",
            "limit,allowance,minutes,child,kids,timer,lock",
        ),
        e(
            "filters.nsfw_mode", SettingsGroup.CONTENT_FILTERS, "cf_nsfw_warnings", "nsfw_mode",
            "NSFW / content warnings", "cover, hide or show notes tagged with a content warning",
            "nsfw,explicit,sensitive,blur,cover,hide,warning,nip36",
        ),
        e(
            "filters.ai_scanner", SettingsGroup.CONTENT_FILTERS, "cf_ai_scanner", "ai_scanner",
            "offline AI media scanner", "scan images on device with optional local models",
            "ai,scanner,nsfw,model,offline,ondevice,sensitivity,litert",
        ),
        e(
            "filters.ai_sensitivity", SettingsGroup.CONTENT_FILTERS, "cf_ai_scanner", "ai_sensitivity",
            "sensitivity", "how strict the offline media scanner is",
            "ai,threshold,sensitivity,strict,model,scan",
        ),
        e(
            "filters.ai_scan_avatars", SettingsGroup.CONTENT_FILTERS, "cf_ai_scanner", "ai_scan_avatars",
            "scan profile avatars", "fall back to an initial square if explicit",
            "ai,avatars,pfp,scan,models",
        ),
        e(
            "filters.ai_scan_banners", SettingsGroup.CONTENT_FILTERS, "cf_ai_scanner", "ai_scan_banners",
            "scan profile banners", "fall back to a stripe banner if explicit",
            "ai,banners,scan,profile",
        ),
        e(
            "filters.ai_exempt_follows", SettingsGroup.CONTENT_FILTERS, "cf_ai_scanner", "ai_exempt_follows",
            "exempt followed accounts", "skip scanning people you follow",
            "ai,follows,exempt,skip,trusted",
        ),
        e(
            "filters.ai_require_pin", SettingsGroup.CONTENT_FILTERS, "cf_ai_scanner", "ai_require_pin",
            "require parental pin to reveal", "a PIN is needed to uncover flagged media",
            "ai,pin,reveal,parental,uncover",
        ),
        e(
            "filters.ai_models", SettingsGroup.CONTENT_FILTERS, "cf_ai_scanner", "ai_models",
            "model download", "download or import the on-device scanner models",
            "ai,model,download,import,local,install",
        ),
        e(
            "filters.ai_clear_cache", SettingsGroup.CONTENT_FILTERS, "cf_ai_scanner", "ai_clear_cache",
            "clear scan cache", "forget cached scan results",
            "ai,cache,clear,reset,scan",
        ),
        e(
            "filters.bleep_words", SettingsGroup.CONTENT_FILTERS, "cf_bleep_words", "bleep_words",
            "bleep words", "words replaced with asterisks in notes",
            "swear,censor,asterisk,word,words,block",
        ),
        e(
            "filters.hide_words", SettingsGroup.CONTENT_FILTERS, "cf_hide_words", "hide_words",
            "hide words", "notes matching these words are hidden entirely",
            "hide,block,word,words,filter,remove",
        ),
        e(
            "filters.word_match_mode", SettingsGroup.CONTENT_FILTERS, "cf_bleep_words", "word_match_mode",
            "exact word or anywhere", "whether a filter word matches on its own or inside other words",
            "exact,anywhere,whole,substring,match,mode",
        ),
        e(
            "filters.parental_pin", SettingsGroup.CONTENT_FILTERS, "cf_parental_pin", "parental_pin",
            "parental pin", "lock these filter settings behind a PIN",
            "pin,parental,child,lock,protect,controls",
        ),

        // ── Screen time ───────────────────────────────────────────────────
        e(
            "screentime.enabled", SettingsGroup.SCREEN_TIME, "st_all", "st_enabled",
            "screen time limit", "locks the app after the daily allowance",
            "limit,on,off,timer,child,parental",
        ),
        e(
            "screentime.allowance", SettingsGroup.SCREEN_TIME, "st_all", "st_allowance",
            "daily allowance", "hours and minutes per day",
            "hours,minutes,time,allowance,limit,quota",
        ),

        // ── Relays ────────────────────────────────────────────────────────
        e(
            "relays.list", SettingsGroup.RELAYS, "relay_screen", "relay_list",
            "your relays", "every relay on this account, toggle one off to disconnect",
            "relay,relays,server,connection,on,off,toggle,solo",
        ),
        e(
            "relays.add", SettingsGroup.RELAYS, "relay_screen", "relay_add",
            "add relay", "paste a wss:// relay URL",
            "relay,url,wss,add,new,server",
        ),
        e(
            "relays.citrine", SettingsGroup.RELAYS, "relay_screen", "relay_citrine",
            "use local Citrine relay", "point the client at a relay running on this device",
            "citrine,local,relay,onion,self,hosted",
        ),
        e(
            "relays.import_export", SettingsGroup.RELAYS, "relay_screen", "relay_import_export",
            "import / export relays", "share your relay list as text or merge one in",
            "import,export,backup,restore,share,copy,paste,list",
        ),
        e(
            "relays.global", SettingsGroup.RELAYS, "relay_screen", "relay_global",
            "global relays", "the relay set used for discovery and the global feed",
            "global,relays,discovery,bootstrap,all",
        ),
        e(
            "relays.enable_all", SettingsGroup.RELAYS, "relay_screen", "relay_enable_all",
            "enable all", "reconnect every relay at once",
            "all,enable,connect,reset,toggle",
        ),

        // ── Blossom ───────────────────────────────────────────────────────
        e(
            "blossom.servers", SettingsGroup.BLOSSOM, "blossom", "blossom_servers",
            "upload servers", "which Blossom hosts receive your media",
            "blossom,server,host,media,upload,default",
        ),
        e(
            "blossom.add_server", SettingsGroup.BLOSSOM, "blossom", "blossom_add_server",
            "add blossom server", "add your own upload host",
            "blossom,server,url,add,custom,host",
        ),
        e(
            "blossom.beaver", SettingsGroup.BLOSSOM, "blossom", "blossom_beaver",
            "Beaver Mode", "compress media before uploading",
            "beaver,compress,compression,upload,size,bandwidth,data",
        ),
        e(
            "blossom.compress_images", SettingsGroup.BLOSSOM, "blossom", "blossom_compress_images",
            "compress uploads", "shrink images and video before upload",
            "compress,compression,images,video,size,upload",
        ),
        e(
            "blossom.compress_audio", SettingsGroup.BLOSSOM, "blossom", "blossom_compress_audio",
            "compress audio uploads", "shrink voice notes and audio before upload",
            "compress,audio,voice,size,upload",
        ),
        e(
            "blossom.sync_list", SettingsGroup.BLOSSOM, "blossom", "blossom_sync_list",
            "sync published Blossom list", "merge or replace your published server list",
            "blossom,sync,publish,list,merge,replace,relay",
        ),

        // ── Accounts ──────────────────────────────────────────────────────
        e(
            "accounts.active", SettingsGroup.ACCOUNTS, "accounts", "accounts_active",
            "active account", "the identity currently signing",
            "active,current,switch,identity,account",
        ),
        e(
            "accounts.hidden", SettingsGroup.ACCOUNTS, "accounts", "accounts_hidden",
            "hidden account", "an account protected by a special PIN",
            "hidden,secret,pin,protected,vault,alt",
        ),
        e(
            "accounts.hide", SettingsGroup.ACCOUNTS, "accounts", "accounts_hide",
            "hide account", "set a PIN and hide this account from the switcher",
            "hide,pin,protect,switcher,secret",
        ),
        e(
            "accounts.add", SettingsGroup.ACCOUNTS, "accounts", "accounts_add",
            "add account", "sign in with another Nostr identity",
            "add,login,signin,nsec,bunker,amber,new,identity",
        ),

        // ── Reactions ─────────────────────────────────────────────────────
        e(
            "reactions.multi_emoji", SettingsGroup.REACTIONS, "reactions_screen", "reactions_multi",
            "multi emoji reactions", "choose from saved emojis when you tap like",
            "emoji,reaction,like,heart,multi",
        ),
        e(
            "reactions.saved_emojis", SettingsGroup.REACTIONS, "reactions_screen", "reactions_saved",
            "saved emojis", "the emoji picker shown after tapping like",
            "emoji,saved,picker,add,limit",
        ),
    )

    val byId: Map<String, SettingsEntry> = entries.associateBy { it.id }

    /** Lookup used by [social.tbone.ui.settings.search.SettingsAnchor]. */
    val bySectionAnchor: Map<Pair<String, String>, SettingsEntry> =
        entries.associateBy { it.section to it.anchor }

    /** Every anchor the UI is expected to claim. */
    val anchors: Set<String> = entries.map { it.anchor }.toSet()
}
