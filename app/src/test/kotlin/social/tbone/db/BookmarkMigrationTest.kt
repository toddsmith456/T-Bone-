package social.tbone.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class BookmarkMigrationTest {
    @Test
    fun migration14To15CreatesEncryptedBookmarkTablesAndCascadeIndex() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_14_15.migrate(database)

        assertEquals(3, statements.size)
        assertTrue(statements[0].contains("CREATE TABLE IF NOT EXISTS `bookmark_folders`"))
        assertTrue(statements[0].contains("`encryptedName` BLOB NOT NULL"))
        assertTrue(statements[0].contains("`iv` BLOB NOT NULL"))
        assertTrue(statements[1].contains("CREATE TABLE IF NOT EXISTS `bookmarks`"))
        assertTrue(statements[1].contains("`ciphertext` BLOB NOT NULL"))
        assertTrue(statements[1].contains("FOREIGN KEY(`folderId`) REFERENCES `bookmark_folders`(`id`)"))
        assertTrue(statements[1].contains("ON DELETE CASCADE"))
        assertTrue(statements[2].contains("index_bookmarks_folderId_sortOrder"))
        assertTrue(statements[2].contains("(`folderId`, `sortOrder`)"))
    }

    /**
     * 17 -> 18 added online bookmark folders.
     *
     * This test exists because the first version of this migration crashed the
     * app on launch: it created an index on `dTag` that the entity does not
     * declare, and Room compares the migrated schema against the entity and
     * throws IllegalStateException when they disagree — on first open, before
     * any UI can appear.
     *
     * The rules being pinned down:
     *  - `kind` is added WITH `DEFAULT 'OFFLINE'` because the entity declares
     *    that default; a missing default is also a mismatch;
     *  - `dTag` is added with NO default clause, matching the entity;
     *  - no index is created, because `@Entity` declares none for this table.
     */
    @Test
    fun migration17To18AddsFolderKindAndDTagWithoutExtraIndex() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_17_18.migrate(database)

        assertEquals(2, statements.size)
        assertEquals(
            "ALTER TABLE bookmark_folders ADD COLUMN kind TEXT NOT NULL DEFAULT 'OFFLINE'",
            statements[0],
        )
        assertEquals(
            "ALTER TABLE bookmark_folders ADD COLUMN dTag TEXT",
            statements[1],
        )
        assertTrue(
            "a migrated schema must not contain an index the entity does not declare",
            statements.none { it.contains("CREATE INDEX", ignoreCase = true) },
        )
    }
}
