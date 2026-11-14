package social.tbone.ai

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    @Binds
    @Singleton
    abstract fun bindNsfwEngine(impl: LiteRtNsfwEngine): NsfwEngine

    @Binds
    @Singleton
    abstract fun bindAiRuntimeStateProvider(impl: AppAiRuntimeStateProvider): AiRuntimeStateProvider
}
