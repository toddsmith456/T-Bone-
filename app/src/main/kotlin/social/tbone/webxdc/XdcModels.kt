package social.tbone.webxdc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One entry of the official webxdc app store.
 *
 * The store front-end at https://webxdc.org/apps is a static page; the actual
 * catalogue is the xdcget export at [XdcStoreCatalog.INDEX_URL]. Icons and
 * `.xdc` downloads are hosted next to that index.
 */
@Serializable
data class XdcStoreEntry(
    @SerialName("app_id") val appId: String = "",
    @SerialName("tag_name") val tagName: String = "",
    val url: String = "",
    val date: String = "",
    val description: String = "",
    @SerialName("source_code_url") val sourceCodeUrl: String = "",
    val name: String = "",
    val category: String = "",
    @SerialName("cache_relname") val cacheRelname: String = "",
    val size: Long = 0L,
    @SerialName("icon_relname") val iconRelname: String = "",
)

object XdcStoreCatalog {
    const val BASE_URL = "https://apps.testrun.org"
    const val INDEX_URL = "$BASE_URL/xdcget-lock.json"

    fun iconUrl(entry: XdcStoreEntry): String? =
        entry.iconRelname.takeIf { it.isNotBlank() }?.let { "$BASE_URL/$it" }

    fun downloadUrl(entry: XdcStoreEntry): String? =
        entry.cacheRelname.takeIf { it.isNotBlank() }?.let { "$BASE_URL/$it" }
            ?: entry.url.takeIf { it.startsWith("http") }

    /** First line of a store description, used as the card subtitle. */
    fun subtitle(entry: XdcStoreEntry): String =
        entry.description.lineSequence().firstOrNull()?.trim().orEmpty()
}

/** An app the user saved into T-Bone. Persisted as `meta.json` next to its files. */
@Serializable
data class SavedXdcApp(
    val id: String,
    val name: String,
    val description: String? = null,
    val sourceCodeUrl: String? = null,
    val sizeBytes: Long = 0L,
    val importedAt: Long = 0L,
    /** "local", "store", "tbone" or "nostr" — where the user picked it up. */
    val origin: String = "local",
    /** NIP-DC coordination id from the Nostr event, when there was one. */
    val uuid: String? = null,
    val iconFile: String? = null,
)

/**
 * A saved mini app promoted to a first-class toolbox tool.
 * `appId` points at [SavedXdcApp.id]; `iconId` at an entry of XdcToolIcons.
 */
@Serializable
data class XdcCustomTool(
    val appId: String,
    val name: String,
    val iconId: String,
    val addedAt: Long = 0L,
)

object XdcCustomToolsCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(tools: List<XdcCustomTool>): String = json.encodeToString(tools)

    fun decode(raw: String?): List<XdcCustomTool> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<XdcCustomTool>>(raw) }.getOrDefault(emptyList())
    }
}
