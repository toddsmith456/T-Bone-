package social.tbone.ui.toolbox.geohash

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.db.AppDatabase
import social.tbone.db.GeohashMessageEntity

@RunWith(AndroidJUnit4::class)
class GeohashMigrationTest {
    @Test fun oldCacheMigratesAndSameEventCanBelongToTwoChannels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "geohash-migration-${System.nanoTime()}.db"
        var db: AppDatabase? = null
        try {
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val sql = db.openHelper.writableDatabase
            // Recreate the actual version-16 cache schema, keeping all other tables intact.
            sql.execSQL("DROP TABLE geohash_messages")
            sql.execSQL("CREATE TABLE geohash_messages (id TEXT NOT NULL PRIMARY KEY, channel_code TEXT NOT NULL, pubkey TEXT NOT NULL, created_at INTEGER NOT NULL, content TEXT NOT NULL, nickname TEXT, own INTEGER NOT NULL DEFAULT 0, cached_at INTEGER NOT NULL DEFAULT 0)")
            sql.execSQL("CREATE INDEX index_geohash_messages_channel_created ON geohash_messages (channel_code, created_at)")
            sql.execSQL("INSERT INTO geohash_messages VALUES ('id1', '9q8yyk', 'pub', 42, 'message', NULL, 1, 1000)")
            sql.version = 16
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_16_17).build()
            val dao = db.geohashMessageDao()
            val old = dao.getForChannel("9q8yyk", 0).single()
            assertEquals("message", old.content)
            assertEquals(true, old.own)
            dao.insert(GeohashMessageEntity("id1", "9q8yy", "pub", 42, "message", null, true, 1000))
            assertEquals(1, dao.getForChannel("9q8yyk", 0).size)
            assertEquals(1, dao.getForChannel("9q8yy", 0).size)
        } finally {
            db?.close()
            context.deleteDatabase(name)
        }
    }
}
