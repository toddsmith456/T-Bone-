package social.tbone.home

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the home-layout preferences file.
 *
 * It is a separate file from `tbone_prefs` on purpose: the layout is one value
 * written occasionally by one screen, and keeping it out of the main settings
 * store means a layout edit cannot contend for the same write lock as, say, a
 * relay-list update.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HomeLayoutStore

private val Context.homeLayoutStore: DataStore<Preferences> by
    preferencesDataStore(name = "tbone_home_layout")

@Module
@InstallIn(SingletonComponent::class)
object HomeLayoutModule {

    @Provides
    @Singleton
    @HomeLayoutStore
    fun provideHomeLayoutStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.homeLayoutStore
}
