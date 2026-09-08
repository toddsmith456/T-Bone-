package social.tbone.notes

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A checklist's plaintext form. Before it touches storage the whole object is
 * serialized to JSON and encrypted with the exact same AES-256-GCM key as
 * notes ([NotesCrypto]) — only ciphertext + IV are written to the database.
 */
@Serializable
data class ChecklistItem(
    val text: String = "",
    val checked: Boolean = false,
)

@Serializable
data class ChecklistData(
    val title: String = "",
    val items: List<ChecklistItem> = emptyList(),
) {
    val doneCount: Int get() = items.count { it.checked }

    /** Human-readable share/copy form. */
    fun toDisplayText(): String = buildString {
        if (title.isNotBlank()) {
            append(title.trim())
            append('\n')
        }
        items.forEach { item ->
            append(if (item.checked) "☑ " else "☐ ")
            append(item.text)
            append('\n')
        }
    }
}

object ChecklistCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(data: ChecklistData): String = json.encodeToString(data)

    fun decode(text: String): ChecklistData = runCatching {
        json.decodeFromString<ChecklistData>(text)
    }.getOrDefault(ChecklistData())
}
