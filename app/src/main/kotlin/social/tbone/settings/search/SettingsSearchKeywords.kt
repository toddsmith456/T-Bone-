package social.tbone.settings.search

/**
 * Query matching for the settings search.
 *
 * This file is deliberately free of Android and Compose imports so the matcher
 * can be covered by plain JVM unit tests. Two classes of bug are pinned by
 * [SettingsSearchTest]:
 *
 *  - substring leakage, where `tor` matched "sTORage" and `lock` matched
 *    "bLOCK", and
 *  - unreachable rows, where a real on-screen label such as "inline images"
 *    or "Beaver Mode" was not indexed at all.
 */
object SettingsSearchKeywords {

    private val WORD_SPLIT = Regex("[^a-z0-9]+")

    /**
     * Colloquial names people type, mapped onto the words the app actually
     * uses. Values are alternatives: typing the key also matches its aliases.
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        // privacy / networking
        "orbot" to listOf("tor", "proxy", "onion", "vpn"),
        "tor" to listOf("orbot", "proxy", "onion"),
        "onion" to listOf("tor", "orbot"),
        "vpn" to listOf("tor", "orbot", "proxy"),
        "duress" to listOf("panic", "wipe", "emergency"),
        "panic" to listOf("duress"),
        "wipe" to listOf("duress", "clear", "delete"),
        "pin" to listOf("passcode", "password", "code", "lock"),
        "passcode" to listOf("pin"),
        "password" to listOf("pin", "passcode"),
        "lock" to listOf("pin", "passcode"),
        "unlock" to listOf("pin", "lock"),

        // accounts / identity
        "logout" to listOf("sign", "out", "remove"),
        "sign" to listOf("logout", "in"),
        "account" to listOf("identity", "profile", "npub"),
        "npub" to listOf("key", "identity", "account"),
        "nsec" to listOf("key", "identity", "secret", "signer"),
        "amber" to listOf("signer", "signing", "external"),
        "bunker" to listOf("nip46", "signer", "remote"),
        "backup" to listOf("export", "restore", "save"),
        "restore" to listOf("import", "backup"),

        // appearance
        "dark" to listOf("night", "black", "amoled"),
        "night" to listOf("dark"),
        "light" to listOf("bright", "white", "paper"),
        "bright" to listOf("light"),
        "colour" to listOf("color", "accent", "palette"),
        "color" to listOf("accent", "palette", "appearance"),
        "font" to listOf("text", "type", "typeface", "size"),
        "text" to listOf("font", "size", "type"),
        "zoom" to listOf("size", "scale", "font"),
        "rounded" to listOf("corner", "radius", "shape"),
        "corner" to listOf("radius", "rounded", "shape"),
        "theme" to listOf("appearance", "look", "dark", "light"),

        // media
        "pictures" to listOf("images", "photos", "media"),
        "photos" to listOf("images", "pictures", "media"),
        "images" to listOf("pictures", "photos", "media"),
        "pics" to listOf("images", "photos"),
        "thumbnails" to listOf("preview", "still", "thumbs"),
        "autoplay" to listOf("play", "video", "inline"),
        "background" to listOf("playback", "audio", "music", "screen-off"),
        "playback" to listOf("background", "audio", "music", "lockscreen"),
        "video" to listOf("videos", "playback", "media"),
        "gif" to listOf("animated", "animation"),
        "compression" to listOf("compress", "beaver", "upload"),
        "compress" to listOf("beaver", "compression"),
        "beaver" to listOf("compress", "compression"),
        "blossom" to listOf("upload", "server", "media", "host"),
        "avatar" to listOf("picture", "profile", "pfp"),
        "pfp" to listOf("avatar", "picture"),

        // storage
        "cache" to listOf("storage", "space", "clear", "memory"),
        "storage" to listOf("cache", "space", "disk"),
        "space" to listOf("storage", "cache", "disk"),
        "disk" to listOf("storage", "cache"),
        "delete" to listOf("clear", "wipe", "remove"),
        "clear" to listOf("delete", "wipe", "reset"),

        // filtering / moderation
        "nsfw" to listOf("explicit", "sensitive", "nude", "content", "warning"),
        "nude" to listOf("nsfw", "explicit"),
        "explicit" to listOf("nsfw", "sensitive"),
        "mute" to listOf("muted", "block", "hide"),
        "muted" to listOf("mute", "block"),
        "block" to listOf("mute", "hide"),
        "bleep" to listOf("word", "swear", "censor", "asterisk"),
        "swear" to listOf("bleep", "censor", "word"),
        "words" to listOf("word", "bleep", "hide", "filter"),
        "filter" to listOf("filters", "hide", "block", "moderation"),
        "parental" to listOf("child", "kid", "pin", "controls"),
        "screentime" to listOf("screen", "time", "limit", "allowance"),

        // updates / diagnostics
        "version" to listOf("update", "upgrade", "release", "changelog", "build"),
        "changelog" to listOf("version", "update", "release", "whats", "new"),
        "update" to listOf("version", "upgrade", "release"),
        "upgrade" to listOf("update", "version"),
        "notifications" to listOf("notification", "alerts", "notify", "ping"),
        "alerts" to listOf("notifications", "notify"),
        "diagnostics" to listOf("debug", "logs", "crash"),
        "logs" to listOf("log", "debug", "diagnostics", "crash"),
        "crash" to listOf("crashes", "logs", "diagnostics"),

        // misc
        "notepad" to listOf("stealth", "decoy", "disguise", "notes"),
        "decoy" to listOf("stealth", "notepad", "hidden"),
        "hide" to listOf("hidden", "stealth", "mute"),
        "relays" to listOf("relay", "server", "connection"),
        "relay" to listOf("relays", "server"),
        "servers" to listOf("server", "relay", "upload"),
        "lists" to listOf("list", "follows", "followers", "mutes"),
        "follows" to listOf("follow", "following"),
        "feeds" to listOf("feed", "timeline", "tabs"),
        "tabs" to listOf("tab", "feeds", "home"),
        "reactions" to listOf("reaction", "emoji", "emojis", "like"),
        "emoji" to listOf("reactions", "reaction", "like"),
    )

    /** Tokens of a query: lowercase alphanumeric runs. */
    fun queryTokens(query: String): List<String> =
        query.lowercase().split(WORD_SPLIT).filter { it.isNotBlank() }

    /** Words of a searchable string, split the same way as a query. */
    fun wordsOf(text: String): List<String> =
        text.lowercase().split(WORD_SPLIT).filter { it.isNotBlank() }

    /** The token plus every colloquial alias for it. */
    fun expand(token: String): List<String> =
        listOf(token) + (ALIASES[token] ?: emptyList())

    /**
     * How well one query token matches one word of indexed text.
     *
     * Exact word beats prefix; short tokens never prefix-match, which is what
     * stopped `ui` from matching "disgUIse". No fuzzy distance is used below
     * four characters, so two-letter noise cannot fan out.
     */
    fun wordScore(token: String, word: String): Float = when {
        word == token -> 3f
        token.length >= 3 && word.startsWith(token) -> 2f
        else -> 0f
    }
}
