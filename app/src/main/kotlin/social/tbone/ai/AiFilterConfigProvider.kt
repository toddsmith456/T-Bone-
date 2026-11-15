package social.tbone.ai

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import social.tbone.account.AccountRepository
import social.tbone.settings.AppSettings
import timber.log.Timber
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

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught exception in AppAiRuntimeStateProvider")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

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
            accountRepository.activeAccount
                .map { it?.follows?.toSet().orEmpty() }
                .distinctUntilChanged()
                .collect { follows ->
                    latestFollows = follows
                }
        }
    }

    override fun currentConfig(): AiFilterConfig = latestConfig

    override fun followedPubkeys(): Set<String> = latestFollows
}
