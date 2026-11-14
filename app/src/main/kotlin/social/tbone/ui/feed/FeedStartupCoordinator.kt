package social.tbone.ui.feed

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import social.tbone.account.Account
import social.tbone.home.FeedSlot
import social.tbone.home.HomeLayout

/**
 * Coordinates cold-start feed activation so layout loading and active-account
 * restoration can never race each other into an infinite spinner.
 *
 * ### Why this exists
 * Previously, `FeedViewModel` launched two independent coroutines during `init`:
 * one awaited `homeLayouts.loaded` and read a cold `WhileSubscribed`
 * `activeAccount.value` (still `null` before Compose subscribed), while the
 * second collected `accountRepository.activeAccount` directly and called
 * `switchToSlot(_activeSlot.value)`, which again read `activeAccount.value`
 * (still `null`) or found `_activeSlot.value == null`. Under either ordering,
 * neither coroutine started a load or armed `LoadWatchdog`, leaving
 * `FeedUiState(isLoading = true)` spinning forever until the user manually
 * refreshed.
 *
 * Waiting for the stored layout first and then passing each emitted [Account]
 * directly into [onActivate] makes that race impossible by construction.
 */
internal suspend fun coordinateFeedStartup(
    awaitInitialLayout: suspend () -> HomeLayout,
    syncLayout: (HomeLayout) -> Unit,
    currentLayout: () -> HomeLayout,
    currentActiveSlotId: () -> String?,
    accounts: Flow<Account?>,
    onAccountObserved: (Account?) -> Unit,
    onClear: () -> Unit,
    onActivate: (slot: FeedSlot, account: Account) -> Unit,
) {
    val initialLayout = awaitInitialLayout()
    syncLayout(initialLayout)

    accounts
        .distinctUntilChanged { old, new -> old?.pubkey == new?.pubkey }
        .collect { account ->
            onAccountObserved(account)
            if (account == null) {
                onClear()
                return@collect
            }
            val layout = currentLayout()
            syncLayout(layout)
            val activeId = currentActiveSlotId()
            val target = activeId?.let { layout.slot(it) } ?: layout.landingSlot ?: initialLayout.landingSlot
            if (target != null) {
                onActivate(target, account)
            } else {
                onClear()
            }
        }
}

/**
 * True when Room-cached home events for [accountPubkey] may be preloaded.
 *
 * On cold start [lastLoadedPubkey] is `null`, and the cache query is already
 * strictly partitioned by `accountPubkey`, so preloading is safe on both first
 * launch and same-account refreshes, while still skipped on account switches.
 */
internal fun shouldPreloadAccountCache(lastLoadedPubkey: String?, accountPubkey: String): Boolean =
    lastLoadedPubkey == null || lastLoadedPubkey == accountPubkey

/**
 * True when an incoming kind-3 follow list matches the set already subscribed
 * for the active home load, so metadata can be persisted without tearing down
 * and restarting an already-running or settled feed.
 */
internal fun isSameSubscribedFollowSet(
    followsReceived: Boolean,
    subscribedFollows: Set<String>?,
    incomingFollows: Collection<String>,
): Boolean = followsReceived && subscribedFollows != null && subscribedFollows == incomingFollows.toSet()
