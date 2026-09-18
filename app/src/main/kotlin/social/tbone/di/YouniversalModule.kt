package social.tbone.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.youniversal.theme.YouniversalThemeState
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object YouniversalModule {
    @Provides
    @Singleton
    fun provideYouniversalThemeState(
        @ApplicationContext context: Context
    ): YouniversalThemeState = YouniversalThemeState(context)
}
