package social.tbone.reactions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PollVoteStoreTest {
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: PollVoteStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("poll-votes", ".preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = PollVoteStore(dataStore)
    }

    @After
    fun tearDown() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        file.delete()
        Unit
    }

    @Test
    fun `latest vote persists per account and older vote cannot replace it`() = runBlocking {
        store.saveVote("alice", "poll-1", PersistedPollVote(100, listOf("a")))
        store.saveVote("alice", "poll-1", PersistedPollVote(99, listOf("b")))
        store.saveVote("alice", "poll-1", PersistedPollVote(101, listOf("b", "c")))
        store.saveVote("bob", "poll-1", PersistedPollVote(50, listOf("c")))

        assertEquals(PersistedPollVote(101, listOf("b", "c")), store.getVotes("alice")["poll-1"])
        assertEquals(PersistedPollVote(50, listOf("c")), store.getVotes("bob")["poll-1"])
    }

    @Test
    fun `failed optimistic vote restores previous choice only when it is still current`() = runBlocking {
        val previous = PersistedPollVote(100, listOf("a"))
        val optimistic = PersistedPollVote(101, listOf("b"))
        store.saveVote("alice", "poll-1", previous)
        store.saveVote("alice", "poll-1", optimistic)
        store.restoreAfterFailure("alice", "poll-1", 101, previous)
        assertEquals(previous, store.getVotes("alice")["poll-1"])

        store.saveVote("alice", "poll-1", PersistedPollVote(102, listOf("c")))
        store.restoreAfterFailure("alice", "poll-1", 101, null)
        assertEquals(PersistedPollVote(102, listOf("c")), store.getVotes("alice")["poll-1"])
    }
}
