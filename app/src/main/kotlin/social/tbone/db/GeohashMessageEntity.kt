package social.tbone.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geohash_messages")
data class GeohashMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "channel_code") val channelCode: String,
    @ColumnInfo(name = "pubkey") val pubkey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "nickname") val nickname: String?,
    @ColumnInfo(name = "own") val own: Boolean = false,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis(),
)
