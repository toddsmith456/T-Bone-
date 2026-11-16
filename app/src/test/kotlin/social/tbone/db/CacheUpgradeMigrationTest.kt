package social.tbone.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class CacheUpgradeMigrationTest {

    @Test
    fun migration19To20UpgradesProfilesAndCreatesEngagementCacheMatchingRoomSchema() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_19_20.migrate(database)

        assertEquals(8, statements.size)
        assertTrue(statements[0].contains("ALTER TABLE `profiles` ADD COLUMN `lastAccessedAt` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(statements[1].contains("ALTER TABLE `profiles` ADD COLUMN `fetchedAt` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(statements[2].contains("ALTER TABLE `profiles` ADD COLUMN `isFollowed` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(statements[3].contains("ALTER TABLE `profiles` ADD COLUMN `searchText` TEXT NOT NULL DEFAULT ''"))
        assertTrue(statements[4].contains("CREATE INDEX IF NOT EXISTS `index_profiles_isFollowed_lastAccessedAt`"))
        assertTrue(statements[4].contains("ON `profiles` (`isFollowed`, `lastAccessedAt`)"))
        assertTrue(statements[5].contains("CREATE INDEX IF NOT EXISTS `index_profiles_createdAt`"))
        assertTrue(statements[5].contains("ON `profiles` (`createdAt`)"))

        val createEngagement = statements[6]
        assertTrue(createEngagement.contains("CREATE TABLE IF NOT EXISTS `engagement_cache`"))
        assertTrue(createEngagement.contains("`eventId` TEXT NOT NULL"))
        assertTrue(createEngagement.contains("`accountPubkey` TEXT NOT NULL"))
        assertTrue(createEngagement.contains("`likeCount` INTEGER NOT NULL"))
        assertTrue(createEngagement.contains("`replyCount` INTEGER NOT NULL"))
        assertTrue(createEngagement.contains("`likedByMe` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(createEngagement.contains("`repliedByMe` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(createEngagement.contains("`myReactionContent` TEXT"))
        assertTrue(createEngagement.contains("`updatedAt` INTEGER NOT NULL"))
        assertTrue(createEngagement.contains("PRIMARY KEY(`eventId`, `accountPubkey`)"))

        assertTrue(statements[7].contains("CREATE INDEX IF NOT EXISTS `index_engagement_cache_updatedAt`"))
        assertTrue(statements[7].contains("ON `engagement_cache` (`updatedAt`)"))
    }
}
