package social.tbone.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import social.tbone.account.AccountRepository
import social.tbone.settings.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lock-free, synchronous snapshot provider used by [NsfwCoilInterceptor] on every
 * Coil image load without blocking on DataStore or Room flows.
 */
interface AiRuntimeStateProvider {
    fun currentConfig(): AiFilterConfig
    fun followedPubkeys(): Set<String>
}

@Singleton
class AppAiRuntimeStateProvider @Inject constructor(
    appSettings: AppSettings,
    accountRepository: AccountRepository,
) : AiRuntimeStateProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var latestConfig: AiFilterConfig = AiFilterConfig()

    @Volatile
    private var latestFollows: Set<String> = emptySet()

    init {
        scope.launch {
            appSettings.aiFilterConfig.collect { cfg ->
                latestConfig = cfg
            }
        }
        scope.launch {
            accountRepository.activeAccount.collect { account ->
                latestFollows = account?.follows?.toSet().orEmpty()
            }
        }
    }

    override fun currentConfig(): AiFilterConfig = latestConfig

    override fun followedPubkeys(): Set<String> = latestFollows
}
