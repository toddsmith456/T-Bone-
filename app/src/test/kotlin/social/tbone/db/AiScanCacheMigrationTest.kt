package social.tbone.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AiScanCacheMigrationTest {
    @Test
    fun migration18To19CreatesMediaScanCacheMatchingRoomEntitySchema() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_18_19.migrate(database)

        assertEquals(2, statements.size)
        val createTable = statements[0]
        assertTrue(createTable.contains("CREATE TABLE IF NOT EXISTS `media_scan_cache`"))
        assertTrue(createTable.contains("`keyHmac` TEXT NOT NULL"))
        assertTrue(createTable.contains("`explicitScore` REAL NOT NULL"))
        assertTrue(createTable.contains("`suggestiveScore` REAL NOT NULL"))
        assertTrue(createTable.contains("`stageReached` INTEGER NOT NULL"))
        assertTrue(createTable.contains("`modelVersion` INTEGER NOT NULL"))
        assertTrue(createTable.contains("`scannedAt` INTEGER NOT NULL"))
        assertTrue(createTable.contains("`userOverrideAllow` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(createTable.contains("PRIMARY KEY(`keyHmac`)"))

        val createIndex = statements[1]
        assertTrue(createIndex.contains("CREATE INDEX IF NOT EXISTS `index_media_scan_cache_scannedAt`"))
        assertTrue(createIndex.contains("ON `media_scan_cache` (`scannedAt`)"))
    }
}
