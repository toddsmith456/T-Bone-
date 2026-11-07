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

    @Test
    fun `verified hidden account cannot unlock after its pin is changed`() = runBlocking {
        repository.addAccount(account("hidden"))
        repository.setHidden("hidden", true, "1234")
        repository.addAccount(account("visible"))
        val verified = repository.findHiddenByPin("1234")!!
        repository.setHidden("hidden", true, "5678")
        assertFalse(repository.activateHiddenIfUnchanged(verified))
        assertEquals("visible", repository.activeAccount.first()?.pubkey)
        assertTrue(repository.unlockHiddenByPin("5678"))
        assertEquals("hidden", repository.activeAccount.first()?.pubkey)
    }

    @Test
    fun `verified hidden account cannot unlock after removal`() = runBlocking {
        repository.addAccount(account("hidden"))
        repository.setHidden("hidden", true, "1234")
        val verified = repository.findHiddenByPin("1234")!!
        repository.removeAccount("hidden")
        assertFalse(repository.activateHiddenIfUnchanged(verified))
        assertNull(repository.activeAccount.first())
    }

    @Test
    fun `shared pin reveals every matching hidden account without revealing others`() = runBlocking {
        repository.addAccount(account("first"))
        repository.setHidden("first", true, "1234")
        repository.addAccount(account("second"))
        repository.setHidden("second", true, "1234")
        repository.addAccount(account("other"))
        repository.setHidden("other", true, "9999")
        repository.addAccount(account("visible"))
        assertTrue(repository.unlockHiddenByPin("1234"))
        assertEquals(setOf("first", "second", "visible"), repository.accounts.first().map { it.pubkey }.toSet())
        assertEquals("first", repository.activeAccount.first()?.pubkey)
        repository.setActiveAccount("second")
        assertEquals("second", repository.activeAccount.first()?.pubkey)
        assertFalse(repository.accounts.first().any { it.pubkey == "first" })
    }

    @Test
    fun `hiding missing account reports failure without creating one`() = runBlocking {
        assertFalse(repository.setHidden("missing", true, "1234"))
        assertFalse(repository.setHidden("missing", false))
        assertTrue(repository.accounts.first().isEmpty())
    }

    @Test
    fun `unreadable accounts do not masquerade as wrong hidden pin`() = runBlocking {
        val key = stringPreferencesKey("accounts")
        val raw = "bad account json"
        dataStore.edit { it[key] = raw }
        var failed = false
        try { repository.unlockHiddenByPin("1234") } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(raw, dataStore.data.first()[key])
    }

    @Test
    fun `full wipe session reset clears hidden decoy and parental unlocks`() {
        val settings = AppSettings(dataStore, scope)
        settings.addHiddenUnlocked("first")
        settings.addHiddenUnlocked("second")
        settings.setDecoyUnlocked(true)
        settings.setParentalUnlocked(true)
        settings.clearSessionUnlocksForWipe()
        assertTrue(settings.hiddenUnlockedPubkeys.value.isEmpty())
        assertFalse(settings.decoyUnlocked.value)
        assertFalse(settings.parentalUnlocked.value)
    }

    @Test
    fun `concurrent hidden session unlocks do not overwrite one another`() = runBlocking {
        val settings = AppSettings(dataStore, scope)
        val work = (0 until 100).map { n -> async(Dispatchers.Default) { settings.addHiddenUnlocked("account-$n") } }
        work.forEach { it.await() }
        assertEquals(100, settings.hiddenUnlockedPubkeys.value.size)
    }

    @Test
    fun `late hidden session transition stays rejected after wipe completes`() {
        val settings = AppSettings(dataStore, scope)
        val oldEpoch = settings.hiddenSessionEpoch()
        settings.addHiddenUnlocked("before")
        settings.clearSessionUnlocksForWipe()
        assertFalse(settings.updateHiddenSession(oldEpoch, null, listOf("late")))
        assertFalse(settings.updateHiddenSession(settings.hiddenSessionEpoch(), null, listOf("during")))
        settings.resumeHiddenSessionsAfterWipe()
        assertFalse(settings.updateHiddenSession(oldEpoch, null, listOf("still-late")))
        assertTrue(settings.hiddenUnlockedPubkeys.value.isEmpty())
        assertTrue(settings.updateHiddenSession(settings.hiddenSessionEpoch(), null, listOf("fresh")))
        assertEquals(setOf("fresh"), settings.hiddenUnlockedPubkeys.value)
    }

    @Test
    fun `startup distinguishes empty from corrupt and inconsistent account storage`() = runBlocking {
        val accountsKey = stringPreferencesKey("accounts")
        val activeKey = stringPreferencesKey("active_pubkey")
        assertEquals(AccountStartupStatus.EMPTY, repository.startupStatus.first())
        dataStore.edit { it[accountsKey] = "not json" }
        assertEquals(AccountStartupStatus.UNREADABLE, repository.startupStatus.first())
        dataStore.edit { it[accountsKey] = NostrJson.encodeToString(listOf(account("known"))); it[activeKey] = "missing" }
        assertEquals(AccountStartupStatus.UNREADABLE, repository.startupStatus.first())
        dataStore.edit { it[activeKey] = "known" }
        assertEquals(AccountStartupStatus.READY, repository.startupStatus.first())
        dataStore.edit { it.remove(accountsKey) }
        assertEquals(AccountStartupStatus.UNREADABLE, repository.startupStatus.first())
        dataStore.edit { it.remove(activeKey) }
        assertEquals(AccountStartupStatus.EMPTY, repository.startupStatus.first())
    }

    @Test
    fun `all account mutations refuse to replace malformed account json`() = runBlocking {
        val key = stringPreferencesKey("accounts")
        val active = stringPreferencesKey("active_pubkey")
        val raw = "[{not a valid account}]"
        dataStore.edit { it[key] = raw; it[active] = "existing" }
        val operations: List<suspend () -> Unit> = listOf(
            { repository.addAccount(account("new")) },
            { repository.removeAccount("existing") },
            { repository.updateAccount(account("existing")) },
            { repository.updateAccount("existing") { it.copy(relays = emptyList()) } },
            { repository.setActiveAccount("existing") },
            { repository.setHidden("existing", true, "1234") },
            { repository.unlockHiddenByPin("1234") },
        )
        for (operation in operations) {
            var failed = false
            try { operation() } catch (_: Exception) { failed = true }
            assertTrue("mutation should reject corrupt accounts", failed)
            assertEquals(raw, dataStore.data.first()[key])
            assertEquals("existing", dataStore.data.first()[active])
        }
    }

    @Test
    fun `corrupt key map rolls back account removal and account addition`() = runBlocking {
        repository.addAccount(account("existing"))
        val accountsKey = stringPreferencesKey("accounts")
        val keysKey = stringPreferencesKey("encrypted_keys")
        val before = dataStore.data.first()[accountsKey]
        dataStore.edit { it[keysKey] = "broken key map" }
        try { repository.removeAccount("existing"); throw AssertionError("remove unexpectedly succeeded") } catch (_: Exception) { }
        assertEquals(before, dataStore.data.first()[accountsKey])
        assertEquals("broken key map", dataStore.data.first()[keysKey])
        try { repository.addAccount(account("new"), byteArrayOf(1)); throw AssertionError("add unexpectedly succeeded") } catch (_: Exception) { }
        assertEquals(before, dataStore.data.first()[accountsKey])
        assertEquals("existing", dataStore.data.first()[stringPreferencesKey("active_pubkey")])
        assertEquals("broken key map", dataStore.data.first()[keysKey])
    }

    @Test
    fun `correct pin migrates every legacy match without changing other accounts`() = runBlocking {
        val legacy = java.security.MessageDigest.getInstance("SHA-256").digest("1234".toByteArray()).joinToString("") { "%02x".format(it) }
        val other = PinHasher.hash("4321")
        val key = stringPreferencesKey("accounts")
        val original = listOf(account("a").copy(isHidden = true, hiddenPinHash = legacy), account("b").copy(isHidden = true, hiddenPinHash = legacy), account("c").copy(isHidden = true, hiddenPinHash = other), account("visible"))
        dataStore.edit { it[key] = NostrJson.encodeToString(original); it[stringPreferencesKey("active_pubkey")] = "visible" }
        assertTrue(repository.unlockHiddenByPin("1234"))
        val result = NostrJson.decodeFromString<List<Account>>(dataStore.data.first()[key]!!).associateBy { it.pubkey }
        for (id in listOf("a", "b")) {
            val hash = result.getValue(id).hiddenPinHash!!
            assertFalse(PinHasher.isLegacy(hash))
            assertTrue(PinHasher.verify("1234", hash))
        }
        assertEquals(other, result.getValue("c").hiddenPinHash)
        assertEquals(setOf("a", "b", "visible"), repository.accounts.first().map { it.pubkey }.toSet())
    }

    @Test
    fun `unknown future account fields are not erased by older app mutations`() = runBlocking {
        val key = stringPreferencesKey("accounts")
        val active = stringPreferencesKey("active_pubkey")
        val raw = """[{"pubkey":"existing","signerType":"AMBER","futurePrivateSetting":{"value":7}}]"""
        dataStore.edit { it[key] = raw; it[active] = "existing" }
        assertEquals(AccountStartupStatus.UNREADABLE, repository.startupStatus.first())
        var failed = false
        try { repository.updateAccount("existing") { it.copy(displayName = "changed") } } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(raw, dataStore.data.first()[key])
    }

    @Test
    fun `older account records with omitted optional fields remain usable`() = runBlocking {
        val key = stringPreferencesKey("accounts")
        dataStore.edit { it[key] = """[{"pubkey":"legacy","signerType":"AMBER"}]"""; it[stringPreferencesKey("active_pubkey")] = "legacy" }
        assertEquals(AccountStartupStatus.READY, repository.startupStatus.first())
        repository.updateAccount("legacy") { it.copy(displayName = "Legacy account") }
        assertEquals("Legacy account", repository.activeAccount.first()?.displayName)
    }

    @Test
    fun `unknown nested account fields are preserved by rejecting mutations`() = runBlocking {
        val key = stringPreferencesKey("accounts")
        val raw = """[{"pubkey":"legacy","signerType":"AMBER","followEntries":[{"pubkey":"friend","futureField":true}]}]"""
        dataStore.edit { it[key] = raw; it[stringPreferencesKey("active_pubkey")] = "legacy" }
        assertEquals(AccountStartupStatus.UNREADABLE, repository.startupStatus.first())
        var failed = false
        try { repository.removeAccount("legacy") } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(raw, dataStore.data.first()[key])
    }

    private fun account(pubkey: String, relays: List<String> = emptyList()) = Account(
        pubkey = pubkey,
        signerType = SignerType.AMBER,
        relays = relays,
    )
}
