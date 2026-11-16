package social.tbone.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import social.tbone.nostr.ProfileContent
import social.tbone.profile.ProfileSearch

@Entity(
    tableName = "profiles",
    indices = [
        Index(value = ["isFollowed", "lastAccessedAt"]),
        Index(value = ["createdAt"]),
    ],
)
data class ProfileEntity(
    @PrimaryKey val pubkey: String,
    val contentJson: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val lastAccessedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val fetchedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val isFollowed: Boolean = false,
    @ColumnInfo(defaultValue = "") val searchText: String = "",
)

fun ProfileEntity.toProfileContent(): ProfileContent? = ProfileContent.parse(contentJson)

/** Builds normalized lowercase NFKD text used for offline Room profile & @mention search. */
fun buildProfileSearchText(content: ProfileContent): String {
    val parts = listOfNotNull(
        content.displayName,
        content.name,
        content.nip05,
        content.about?.take(160),
    )
        .map { ProfileSearch.normalize(it) }
        .filter { it.isNotBlank() }
        .distinct()
    return parts.joinToString(" ").take(320)
}
