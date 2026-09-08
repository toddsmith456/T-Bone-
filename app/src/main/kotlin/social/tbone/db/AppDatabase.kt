package social.tbone.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        EventEntity::class,
        ProfileEntity::class,
        NotificationEntity::class,
        NoteEntity::class,
        CalendarEventEntity::class,
        NoteFolderEntity::class,
        NoteAttachmentEntity::class,
        GeohashMessageEntity::class,
    ],
    version = 13,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun geohashMessageDao(): GeohashMessageDao
    abstract fun profileDao(): ProfileDao
    abstract fun notificationDao(): NotificationDao
    abstract fun noteDao(): NoteDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun noteFolderDao(): NoteFolderDao
    abstract fun noteAttachmentDao(): NoteAttachmentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profiles` " +
                        "(`pubkey` TEXT NOT NULL, `contentJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`pubkey`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN accountPubkey TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_kind_accountPubkey ON events (kind, accountPubkey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_createdAt ON events (createdAt)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notifications` (" +
                        "`id` TEXT NOT NULL, `pubkey` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`json` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_notifications_pubkey_createdAt " +
                        "ON notifications (pubkey, createdAt)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (" +
                        "`id` TEXT NOT NULL, `ciphertext` BLOB NOT NULL, `iv` BLOB NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Checklists share the same encrypted storage; existing rows are notes.
                db.execSQL("ALTER TABLE notes ADD COLUMN kind TEXT NOT NULL DEFAULT 'note'")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Pin + manual ordering for the notes list.
                db.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN sortOrder REAL NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Repeating events — recurrence columns (defaults keep old rows).
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN repeatUnit TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN repeatInterval INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Notes: folders, titles, and inline attachments.
                db.execSQL("ALTER TABLE notes ADD COLUMN folderId TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN titleCiphertext BLOB")
                db.execSQL("ALTER TABLE notes ADD COLUMN titleIv BLOB")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_folders` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`pinned` INTEGER NOT NULL DEFAULT 0, `sortOrder` REAL NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_attachments` (" +
                        "`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`mime` TEXT NOT NULL, `ciphertext` BLOB NOT NULL, `iv` BLOB NOT NULL, " +
                        "`size` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_note_attachments_noteId " +
                        "ON note_attachments (noteId)"
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Repeating events can end on a chosen date (null = never ends).
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN repeatEndMillis INTEGER")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `geohash_messages` (" +
                        "`id` TEXT NOT NULL, `channel_code` TEXT NOT NULL, `pubkey` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `content` TEXT NOT NULL, " +
                        "`nickname` TEXT, `own` INTEGER NOT NULL DEFAULT 0, `cached_at` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_geohash_messages_channel_created ON geohash_messages (channel_code, created_at)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Calendar events — encrypted title/description.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calendar_events` (" +
                        "`id` TEXT NOT NULL, `titleCiphertext` BLOB NOT NULL, `titleIv` BLOB NOT NULL, " +
                        "`descriptionCiphertext` BLOB, `descriptionIv` BLOB, " +
                        "`startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL, " +
                        "`allDay` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_calendar_events_startMillis " +
                        "ON calendar_events (startMillis)"
                )
            }
        }
    }
}
