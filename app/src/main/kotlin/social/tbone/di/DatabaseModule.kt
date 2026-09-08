package social.tbone.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import social.tbone.db.AppDatabase
import social.tbone.db.CalendarEventDao
import social.tbone.db.EventDao
import social.tbone.db.GeohashMessageDao
import social.tbone.db.NoteAttachmentDao
import social.tbone.db.NoteFolderDao
import social.tbone.db.NoteDao
import social.tbone.db.NotificationDao
import social.tbone.db.ProfileDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "tbone.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
            )
            .build()

    @Provides
    fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()

    @Provides
    fun provideGeohashMessageDao(db: AppDatabase): GeohashMessageDao = db.geohashMessageDao()

    @Provides
    fun provideProfileDao(db: AppDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideNotificationDao(db: AppDatabase): NotificationDao = db.notificationDao()

    @Provides
    fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideCalendarEventDao(db: AppDatabase): CalendarEventDao = db.calendarEventDao()

    @Provides
    fun provideNoteFolderDao(db: AppDatabase): NoteFolderDao = db.noteFolderDao()

    @Provides
    fun provideNoteAttachmentDao(db: AppDatabase): NoteAttachmentDao = db.noteAttachmentDao()
}
