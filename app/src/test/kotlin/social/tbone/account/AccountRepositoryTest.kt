package social.tbone.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    fun tearDown() {
        scope.cancel()
        file.delete()
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

    private fun account(pubkey: String, relays: List<String> = emptyList()) = Account(
        pubkey = pubkey,
        signerType = SignerType.AMBER,
        relays = relays,
    )
}
