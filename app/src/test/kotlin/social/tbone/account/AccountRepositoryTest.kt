package social.tbone.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import social.tbone.nostr.NostrJson
import social.tbone.security.PinHasher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import social.tbone.notes.NoteWriteCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import social.tbone.Tunables
import social.tbone.settings.AppSettings

class AccountRepositoryTest {
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: AccountRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("accounts", ".preferences_pb").also { it.delete() }
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        repository = AccountRepository(dataStore, AppSettings(dataStore, scope))
    }

    @After
    fun tearDown() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        file.delete()
        Unit
    }

    @Test
    fun `first account on a new install receives only the new install relay defaults`() = runBlocking {
        repository.addAccount(account("a"))
        assertEquals(Tunables.NEW_INSTALL_RELAYS, repository.accounts.first().single().relays)
    }

    @Test
    fun `adding an account to existing install does not seed or overwrite relay settings`() = runBlocking {
        repository.addAccount(account("a", listOf("wss://custom.example")))
        repository.addAccount(account("b"))
        val accounts = repository.accounts.first().associateBy { it.pubkey }
        assertEquals(listOf("wss://custom.example"), accounts.getValue("a").relays)
        assertTrue(accounts.getValue("b").relays.isEmpty())
    }

    @Test
    fun `first account custom relay list is preserved`() = runBlocking {
        val custom = listOf("wss://one.example", "wss://two.example")
        repository.addAccount(account("a", custom))
        assertEquals(custom, repository.accounts.first().single().relays)
    }

    @Test
    fun `hidden account verifies salted pin and rejects wrong pin`() = runBlocking {
        repository.addAccount(account("a"))
        repository.setHidden("a", true, "1234")
        assertNull(repository.findHiddenByPin("4321"))
        assertEquals("a", repository.findHiddenByPin("1234")?.pubkey)
        assertTrue(repository.unlockHiddenByPin("1234"))
    }

    @Test
    fun `legacy hidden pin is migrated only after successful verification`() = runBlocking {
        val legacy = java.security.MessageDigest.getInstance("SHA-256").digest("1234".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val key = stringPreferencesKey("accounts")
        val original = account("a").copy(isHidden = true, hiddenPinHash = legacy)
        dataStore.edit { it[key] = NostrJson.encodeToString(listOf(original)) }
        assertNull(repository.findHiddenByPin("4321"))
        assertEquals(legacy, NostrJson.decodeFromString<List<Account>>(dataStore.data.first()[key]!!).single().hiddenPinHash)
        assertEquals("a", repository.findHiddenByPin("1234")?.pubkey)
        val migrated = NostrJson.decodeFromString<List<Account>>(dataStore.data.first()[key]!!).single().hiddenPinHash!!
        assertFalse(PinHasher.isLegacy(migrated))
        assertTrue(PinHasher.verify("1234", migrated))
    }

    @Test
    fun `hide does not overwrite unreadable account data`() = runBlocking {
        val key = stringPreferencesKey("accounts")
        val raw = "not valid account json"
        dataStore.edit { it[key] = raw }
        try {
            repository.setHidden("a", true, "1234")
        } catch (_: Exception) { }
        assertEquals(raw, dataStore.data.first()[key])
    }

    @Test
    fun `stale account update does not recreate a cleared account list`() = runBlocking {
        val old = account("a")
        repository.addAccount(old)
        dataStore.edit { it.clear() }
        repository.updateAccount(old)
        assertNull(dataStore.data.first()[stringPreferencesKey("accounts")])
        assertTrue(repository.accounts.first().isEmpty())
    }

    @Test
    fun `switching to a removed account does not restore its active identifier`() = runBlocking {
        repository.addAccount(account("a"))
        repository.removeAccount("a")
        repository.setActiveAccount("a")
        assertEquals("", dataStore.data.first()[stringPreferencesKey("active_pubkey")])
        assertNull(repository.activeAccount.first())
    }

    @Test
    fun `onboarding completion captured before a wipe cannot recreate accounts`() = runBlocking {
        val writes = NoteWriteCoordinator(scope)
        val epoch = writes.currentWipeEpoch()
        repository.addAccount(account("old"))
        assertTrue(writes.runWipe(NoteWriteCoordinator.WipeKind.APP) {
            writes.pauseAndDrain()
            dataStore.edit { it.clear() }
            true
        })
        try {
            writes.runCancellableAtEpoch(epoch) { repository.addAccount(account("late")) }
            throw AssertionError("stale onboarding accepted")
        } catch (_: java.io.IOException) { }
        assertTrue(repository.accounts.first().isEmpty())
    }

    @Test
    fun `wipe waits for already started account creation before clearing store`() = runBlocking {
        val writes = NoteWriteCoordinator(scope)
        val epoch = writes.currentWipeEpoch()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val creation = async(Dispatchers.IO) {
            writes.runCancellableAtEpoch(epoch) {
                started.complete(Unit)
                resume.await()
                repository.addAccount(account("slow"))
            }
        }
        started.await()
        val wiped = async(Dispatchers.IO) {
            writes.runWipe(NoteWriteCoordinator.WipeKind.APP) {
                writes.pauseAndDrain()
                dataStore.edit { it.clear() }
                true
            }
        }
        withTimeout(3_000) { while (writes.available.value) kotlinx.coroutines.yield() }
        assertFalse(wiped.isCompleted)
        resume.complete(Unit)
        creation.await()
        assertTrue(wiped.await())
        assertTrue(repository.accounts.first().isEmpty())
    }

    private fun account(pubkey: String, relays: List<String> = emptyList()) = Account(
        pubkey = pubkey,
        signerType = SignerType.AMBER,
        relays = relays,
    )
}
