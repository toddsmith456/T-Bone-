package social.tbone.ui.feed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.account.Account
import social.tbone.account.SignerType
import social.tbone.home.FeedSlot
import social.tbone.home.FeedSource
import social.tbone.home.HomeLayout

/**
 * Regression tests for the cold-start infinite feed loading bug.
 *
 * The original hang happened when `homeLayouts.loaded` and
 * `accountRepository.activeAccount` raced in two separate coroutines that both
 * read a cold `WhileSubscribed` `activeAccount.value` (still `null` on launch).
 * Neither coroutine ever started a load or armed `LoadWatchdog`, leaving
 * `FeedUiState(isLoading = true)` spinning forever.
 */
class FeedStartupCoordinationTest {

    private fun account(pubkey: String, displayName: String? = null) = Account(
        pubkey = pubkey,
        displayName = displayName,
        signerType = SignerType.LOCAL_KEY,
    )

    @Test
    fun `activates landing slot when account is ready before layout finishes loading`() = runBlocking {
        val layoutGate = CompletableDeferred<HomeLayout>()
        val defaultLayout = HomeLayout.default()
        val accounts = MutableStateFlow<Account?>(account("alice"))
        val activated = mutableListOf<Pair<String, String>>()
        val cleared = mutableListOf<Unit>()
        val synced = mutableListOf<HomeLayout>()
        var currentLayout = defaultLayout

        val job = launch {
            coordinateFeedStartup(
                awaitInitialLayout = { layoutGate.await() },
                syncLayout = { synced += it },
                currentLayout = { currentLayout },
                currentActiveSlotId = { null },
                accounts = accounts,
                onAccountObserved = {},
                onClear = { cleared += Unit },
                onActivate = { slot, acc -> activated += slot.id to acc.pubkey },
            )
        }

        // Layout completes asynchronously AFTER the account flow already holds alice.
        layoutGate.complete(defaultLayout)
        // Wait until activation fires.
        while (activated.isEmpty()) kotlinx.coroutines.yield()
        job.cancel()

        assertTrue("clear must not fire for a logged-in account", cleared.isEmpty())
        assertEquals(listOf(defaultLayout.landingSlot!!.id to "alice"), activated)
        assertTrue("slot engine must be synced before activation", synced.isNotEmpty())
    }

    @Test
    fun `activates landing slot when layout loads before account arrives`() = runBlocking {
        val defaultLayout = HomeLayout.default()
        val accounts = MutableStateFlow<Account?>(null)
        val activated = mutableListOf<Pair<String, String>>()
        val cleared = mutableListOf<Unit>()

        val job = launch {
            coordinateFeedStartup(
                awaitInitialLayout = { defaultLayout },
                syncLayout = {},
                currentLayout = { defaultLayout },
                currentActiveSlotId = { null },
                accounts = accounts,
                onAccountObserved = {},
                onClear = { cleared += Unit },
                onActivate = { slot, acc -> activated += slot.id to acc.pubkey },
            )
        }

        while (cleared.isEmpty()) kotlinx.coroutines.yield()
        assertEquals(1, cleared.size)
        assertTrue(activated.isEmpty())

        accounts.value = account("bob")
        while (activated.isEmpty()) kotlinx.coroutines.yield()
        job.cancel()

        assertEquals(listOf(defaultLayout.landingSlot!!.id to "bob"), activated)
    }

    @Test
    fun `same-pubkey metadata updates do not restart the active feed`() = runBlocking {
        val defaultLayout = HomeLayout.default()
        val activated = mutableListOf<String>()

        coordinateFeedStartup(
            awaitInitialLayout = { defaultLayout },
            syncLayout = {},
            currentLayout = { defaultLayout },
            currentActiveSlotId = { null },
            accounts = flowOf(
                account("alice", displayName = null),
                account("alice", displayName = "Alice"),
                account("bob", displayName = "Bob"),
            ),
            onAccountObserved = {},
            onClear = {},
            onActivate = { _, acc -> activated += acc.pubkey },
        )

        assertEquals(listOf("alice", "bob"), activated)
    }

    @Test
    fun `honours custom lastActiveSlotId as the cold-start landing slot`() = runBlocking {
        val follows = FeedSlot.of(FeedSource.Follows())
        val hashtag = FeedSlot.of(FeedSource.Hashtag(tags = listOf("nostr")))
        val customLayout = HomeLayout(slots = listOf(follows, hashtag), lastActiveSlotId = hashtag.id)
        val activated = mutableListOf<String>()

        coordinateFeedStartup(
            awaitInitialLayout = { customLayout },
            syncLayout = {},
            currentLayout = { customLayout },
            currentActiveSlotId = { null },
            accounts = flowOf(account("alice")),
            onAccountObserved = {},
            onClear = {},
            onActivate = { slot, _ -> activated += slot.id },
        )

        assertEquals(listOf(hashtag.id), activated)
    }

    @Test
    fun `cold start preloads room cache for the active account but account switches skip it`() {
        assertTrue("cold start (null -> alice) should preload alice's cached feed",
            shouldPreloadAccountCache(lastLoadedPubkey = null, accountPubkey = "alice"))
        assertTrue("same-account refresh should keep cache preload enabled",
            shouldPreloadAccountCache(lastLoadedPubkey = "alice", accountPubkey = "alice"))
        assertFalse("account switch must not preload the previous account's cache",
            shouldPreloadAccountCache(lastLoadedPubkey = "alice", accountPubkey = "bob"))
    }

    @Test
    fun `identical follow list avoids tearing down an already-subscribed home feed`() {
        assertTrue(
            isSameSubscribedFollowSet(
                followsReceived = true,
                subscribedFollows = setOf("a", "b"),
                incomingFollows = listOf("b", "a"),
            ),
        )
        assertFalse(
            isSameSubscribedFollowSet(
                followsReceived = true,
                subscribedFollows = setOf("a", "b"),
                incomingFollows = listOf("a", "b", "c"),
            ),
        )
        assertFalse(
            isSameSubscribedFollowSet(
                followsReceived = false,
                subscribedFollows = setOf("a", "b"),
                incomingFollows = listOf("a", "b"),
            ),
        )
    }
}
