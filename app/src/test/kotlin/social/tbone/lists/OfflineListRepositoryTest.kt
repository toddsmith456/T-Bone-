package social.tbone.lists

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.account.SignerType
import social.tbone.nostr.Nip19
import social.tbone.nostr.relay.RelayPool
import java.io.File

class OfflineListRepositoryTest {

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: OfflineListRepository

    private fun pk(n: Int) = n.toString(16).padStart(64, '0')
    private val alice = pk(0xa11ce)
    private val bob = pk(0xb0b)

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("prefs", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val pool = RelayPool(scope) { error("no network in unit tests") }
        repo = OfflineListRepository(store, RelayListFetcher(pool))
    }

    @After fun tearDown() { scope.cancel(); file.delete() }

    private fun state(type: ListType, who: String) = runBlocking { repo.snapshot(type, who) }

    @Test fun startsOffAndEmpty() {
        val st = state(ListType.FOLLOWS, alice)
        assertFalse(st.enabled); assertFalse(st.initialSyncDone); assertEquals(0, st.size); assertEquals(0L, st.lastSyncedAt)
    }

    @Test fun cannotSwitchOnBeforeInitialSync() = runBlocking {
        assertFalse(repo.setEnabled(ListType.FOLLOWS, alice, true))
        assertFalse(state(ListType.FOLLOWS, alice).enabled)
    }

    @Test fun initialSyncEnablesAndSwitchRemembersState() = runBlocking {
        repo.replaceFromSync(ListType.FOLLOWS, alice, listOf(ListEntry(pk(1)), ListEntry(pk(2), "wss://r", "p")), enable = true)
        var st = state(ListType.FOLLOWS, alice)
        assertTrue(st.enabled); assertTrue(st.initialSyncDone); assertEquals(2, st.size); assertTrue(st.lastSyncedAt > 0)

        // Turn off → list is kept untouched. Turn on again → no sync needed, still the same list.
        assertFalse(repo.setEnabled(ListType.FOLLOWS, alice, false))
        st = state(ListType.FOLLOWS, alice)
        assertFalse(st.enabled); assertEquals(2, st.size)
        assertTrue(repo.setEnabled(ListType.FOLLOWS, alice, true))
        assertEquals(ListEntry(pk(2), "wss://r", "p"), state(ListType.FOLLOWS, alice).entries[1])
    }

    @Test fun syncReplacesTheLocalListWholesaleAndKeepsSwitch() = runBlocking {
        repo.replaceFromSync(ListType.FOLLOWS, alice, listOf(ListEntry(pk(1))), enable = true)
        repo.add(ListType.FOLLOWS, alice, ListEntry(pk(9))) // local-only addition
        repo.replaceFromSync(ListType.FOLLOWS, alice, listOf(ListEntry(pk(2)), ListEntry(pk(3))), enable = false)
        val st = state(ListType.FOLLOWS, alice)
        assertEquals(listOf(pk(2), pk(3)), st.entries.map { it.pubkey }) // pk(9) destroyed, as warned
        assertTrue(st.enabled)
    }

    @Test fun startFromCopyDoesNotStampSyncTime() = runBlocking {
        repo.replaceFromSync(ListType.MUTES, alice, listOf(ListEntry(pk(1))), enable = true, stampSynced = false)
        val st = state(ListType.MUTES, alice)
        assertTrue(st.enabled); assertTrue(st.initialSyncDone); assertEquals(0L, st.lastSyncedAt)
    }

    @Test fun addByNpubHexAndRejectsGarbageDuplicatesAndSelf() = runBlocking {
        val npub = Nip19.hexToNpub(pk(5))
        assertEquals(AddResult.ADDED, repo.addByInput(ListType.FOLLOWS, alice, npub))
        assertEquals(AddResult.ALREADY_PRESENT, repo.addByInput(ListType.FOLLOWS, alice, pk(5)))
        assertEquals(AddResult.ADDED, repo.addByInput(ListType.FOLLOWS, alice, "  nostr:" + Nip19.hexToNpub(pk(6)) + "  "))
        assertEquals(AddResult.INVALID, repo.addByInput(ListType.FOLLOWS, alice, "hello"))
        assertEquals(AddResult.INVALID, repo.addByInput(ListType.FOLLOWS, alice, ""))
        assertEquals(AddResult.SELF, repo.addByInput(ListType.FOLLOWS, alice, Nip19.hexToNpub(alice)))
        assertEquals(listOf(pk(5), pk(6)), state(ListType.FOLLOWS, alice).entries.map { it.pubkey })
    }

    @Test fun removeAndUndo() = runBlocking {
        repo.add(ListType.FOLLOWS, alice, ListEntry(pk(1), "wss://r", "x"))
        repo.add(ListType.FOLLOWS, alice, ListEntry(pk(2)))
        val removed = repo.remove(ListType.FOLLOWS, alice, pk(1))
        assertEquals(ListEntry(pk(1), "wss://r", "x"), removed)
        assertNull(repo.remove(ListType.FOLLOWS, alice, pk(1)))
        assertEquals(listOf(pk(2)), state(ListType.FOLLOWS, alice).entries.map { it.pubkey })
        repo.restore(ListType.FOLLOWS, alice, removed!!)
        assertEquals(setOf(pk(1), pk(2)), state(ListType.FOLLOWS, alice).pubkeySet())
    }

    @Test fun listsAreIsolatedPerAccountAndPerType() = runBlocking {
        repo.replaceFromSync(ListType.FOLLOWS, alice, listOf(ListEntry(pk(1))), enable = true)
        repo.add(ListType.MUTES, alice, ListEntry(pk(2)))
        // Bob has nothing — alice's data is invisible to him.
        val bobFollows = state(ListType.FOLLOWS, bob)
        assertFalse(bobFollows.enabled); assertEquals(0, bobFollows.size)
        assertEquals(0, state(ListType.MUTES, bob).size)
        // Types are separate lists too.
        assertEquals(listOf(pk(1)), state(ListType.FOLLOWS, alice).entries.map { it.pubkey })
        assertEquals(listOf(pk(2)), state(ListType.MUTES, alice).entries.map { it.pubkey })
        assertFalse(state(ListType.MUTES, alice).enabled)
    }

    @Test fun exportFromOneAccountImportIntoAnother() = runBlocking {
        repo.replaceFromSync(ListType.FOLLOWS, alice, listOf(ListEntry(pk(1), "wss://r", "n"), ListEntry(pk(2))), enable = true)
        val file = ListFileFormat.export(ListType.FOLLOWS, alice, state(ListType.FOLLOWS, alice).entries)
        val parsed = ListFileFormat.parse(file, ListType.FOLLOWS)
        assertEquals(alice, parsed.ownerPubkey) // UI uses this to warn "different account"
        val n = repo.replaceImport(ListType.FOLLOWS, bob, parsed.entries)
        assertEquals(2, n)
        assertEquals(state(ListType.FOLLOWS, alice).entries, state(ListType.FOLLOWS, bob).entries)
        // Importing doesn't flip Bob's switch or mark a sync.
        assertFalse(state(ListType.FOLLOWS, bob).enabled)
        assertFalse(state(ListType.FOLLOWS, bob).initialSyncDone)
    }

    @Test fun mergeImportOnlyAddsNewEntriesAndSkipsOwner() = runBlocking {
        repo.add(ListType.FOLLOWS, alice, ListEntry(pk(1), petname = "keep"))
        val added = repo.mergeImport(ListType.FOLLOWS, alice,
            listOf(ListEntry(pk(1), petname = "other"), ListEntry(pk(2)), ListEntry(alice)))
        assertEquals(1, added)
        val st = state(ListType.FOLLOWS, alice)
        assertEquals(listOf(pk(1), pk(2)), st.entries.map { it.pubkey })
        assertEquals("keep", st.entries[0].petname)
    }

    @Test fun replaceImportSwapsListButNotFlags() = runBlocking {
        repo.replaceFromSync(ListType.MUTES, alice, listOf(ListEntry(pk(1))), enable = true)
        val before = state(ListType.MUTES, alice)
        repo.replaceImport(ListType.MUTES, alice, listOf(ListEntry(pk(7)), ListEntry(pk(7)), ListEntry(alice)))
        val st = state(ListType.MUTES, alice)
        assertEquals(listOf(pk(7)), st.entries.map { it.pubkey })
        assertEquals(before.enabled, st.enabled); assertEquals(before.lastSyncedAt, st.lastSyncedAt)
    }

    @Test fun persistsAcrossRepositoryInstances() = runBlocking {
        repo.replaceFromSync(ListType.FOLLOWS, alice, listOf(ListEntry(pk(1))), enable = true)
        val again = OfflineListRepository(store, RelayListFetcher(RelayPool(scope) { error("x") }))
        assertEquals(1, again.state(ListType.FOLLOWS, alice).first().size)
    }

    @Test fun storedStateHoldsNoPictureData() = runBlocking {
        repo.add(ListType.FOLLOWS, alice, ListEntry(pk(1), "wss://r", "pet"))
        val raw = store.data.first()[ListStorageKeys.state(ListType.FOLLOWS, alice)]!!
        assertFalse(raw.contains("picture", ignoreCase = true))
        assertFalse(raw.contains("http"))
        assertNotNull(raw)
    }

    @Test fun removingAnAccountWipesItsLists() = runBlocking {
        val accounts = AccountRepository(store)
        accounts.addAccount(Account(pubkey = alice, signerType = SignerType.AMBER))
        accounts.addAccount(Account(pubkey = bob, signerType = SignerType.AMBER))
        repo.replaceFromSync(ListType.FOLLOWS, alice, listOf(ListEntry(pk(1))), enable = true)
        repo.add(ListType.MUTES, alice, ListEntry(pk(2)))
        repo.add(ListType.MUTES, bob, ListEntry(pk(3)))
        accounts.removeAccount(alice)
        assertEquals(0, state(ListType.FOLLOWS, alice).size)
        assertEquals(0, state(ListType.MUTES, alice).size)
        assertEquals(1, state(ListType.MUTES, bob).size) // other account untouched
    }
}
