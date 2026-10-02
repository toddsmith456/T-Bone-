package social.tbone.lists

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import social.tbone.nostr.Event
import social.tbone.nostr.NostrJson
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore key names for everything the list feature persists (one set per account). */
object ListStorageKeys {
    /** Local list state of [type] for the account [pubkey]. Lists are NEVER shared between accounts. */
    fun state(type: ListType, pubkey: String) = stringPreferencesKey("offline_list_${type.slug}_$pubkey")

    /** Last-seen relay mute list (public p-tags only) so muted users stay hidden at cold start. */
    fun relayMuteCache(pubkey: String) = stringPreferencesKey("relay_mute_cache_$pubkey")

    /** Every key belonging to [pubkey]; used to wipe an account's data when it is removed. */
    fun allFor(pubkey: String): List<Preferences.Key<String>> =
        ListType.entries.map { state(it, pubkey) } + relayMuteCache(pubkey)
}

/** Outcome of reading the online (relay) list for a sync. */
sealed interface OnlineFetch {
    /** A list event was found. [createdAt] is its timestamp in epoch seconds. */
    data class Found(val entries: List<ListEntry>, val createdAt: Long) : OnlineFetch

    /** Relays answered but none has a list for this account. */
    data object Empty : OnlineFetch

    /** No relay answered. Nothing is known about the online list. */
    data object Unreachable : OnlineFetch
}

enum class AddResult { ADDED, ALREADY_PRESENT, INVALID, SELF, LIST_FULL }

/**
 * Storage and rules for the fully local ("offline") follow / mute lists.
 *
 * Rules enforced here:
 *  - One list per (type, ACCOUNT). Switching accounts shows that account's own list.
 *  - Nothing in this class ever publishes to a relay, and the only relay access
 *    is [fetchOnline] — a read-only REQ used by the (initial / manual) sync.
 *  - The list is only ever replaced from the relays by an explicit call to
 *    [replaceFromSync]; there is no background sync anywhere.
 *  - Entries hold pubkey / relay hint / petname only — never profile pictures.
 */
@Singleton
class OfflineListRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val fetcher: RelayListFetcher,
) {

    // ── Reading ───────────────────────────────────────────────────────────────

    /** Live state of the list. Re-decodes only when this list's own stored value changes. */
    fun state(type: ListType, pubkey: String): Flow<LocalListState> {
        val key = ListStorageKeys.state(type, pubkey)
        return dataStore.data
            .map { it[key] }
            .distinctUntilChanged()
            .map { decode(it) }
    }

    suspend fun snapshot(type: ListType, pubkey: String): LocalListState =
        decode(dataStore.data.first()[ListStorageKeys.state(type, pubkey)])

    // ── Switch ────────────────────────────────────────────────────────────────

    /**
     * Turns the list on or off. Turning ON is refused until the initial sync has
     * been completed (or the user explicitly chose to start from a local copy / empty).
     * Returns the resulting `enabled` value.
     */
    suspend fun setEnabled(type: ListType, pubkey: String, enabled: Boolean): Boolean =
        update(type, pubkey) { cur ->
            if (enabled && !cur.initialSyncDone) cur else cur.copy(enabled = enabled)
        }.enabled

    // ── Manual edits ──────────────────────────────────────────────────────────

    /** Adds whatever the user typed/pasted (npub, nprofile, hex, nostr: URI). */
    suspend fun addByInput(type: ListType, ownerPubkey: String, input: String): AddResult {
        val hex = ListFileFormat.normalise(input) ?: return AddResult.INVALID
        return add(type, ownerPubkey, ListEntry(hex))
    }

    suspend fun add(type: ListType, ownerPubkey: String, entry: ListEntry): AddResult {
        if (entry.pubkey == ownerPubkey) return AddResult.SELF
        var result = AddResult.ADDED
        update(type, ownerPubkey) { cur ->
            when {
                cur.contains(entry.pubkey) -> { result = AddResult.ALREADY_PRESENT; cur }
                cur.size >= ListFileFormat.MAX_ENTRIES -> { result = AddResult.LIST_FULL; cur }
                else -> cur.copy(entries = cur.entries + entry)
            }
        }
        return result
    }

    /** Removes [targetPubkey]. Returns the removed entry (for undo) or null if it wasn't there. */
    suspend fun remove(type: ListType, ownerPubkey: String, targetPubkey: String): ListEntry? {
        var removed: ListEntry? = null
        update(type, ownerPubkey) { cur ->
            removed = cur.entries.firstOrNull { it.pubkey == targetPubkey }
            if (removed == null) cur else cur.copy(entries = cur.entries.filterNot { it.pubkey == targetPubkey })
        }
        return removed
    }

    /** Puts a removed entry back (undo), keeping it at the end of the list. */
    suspend fun restore(type: ListType, ownerPubkey: String, entry: ListEntry) {
        update(type, ownerPubkey) { cur ->
            if (cur.contains(entry.pubkey)) cur else cur.copy(entries = cur.entries + entry)
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /** Adds the entries that aren't already in the list. Returns how many were new. */
    suspend fun mergeImport(type: ListType, ownerPubkey: String, incoming: List<ListEntry>): Int {
        var added = 0
        update(type, ownerPubkey) { cur ->
            val have = cur.pubkeySet()
            val fresh = incoming.filter { it.pubkey != ownerPubkey && it.pubkey !in have }
                .take((ListFileFormat.MAX_ENTRIES - cur.size).coerceAtLeast(0))
            added = fresh.size
            if (fresh.isEmpty()) cur else cur.copy(entries = cur.entries + fresh)
        }
        return added
    }

    /** Replaces the whole list with [incoming] (import → replace). Keeps switch + sync flags. */
    suspend fun replaceImport(type: ListType, ownerPubkey: String, incoming: List<ListEntry>): Int {
        val clean = incoming.filter { it.pubkey != ownerPubkey }.distinctBy { it.pubkey }
            .take(ListFileFormat.MAX_ENTRIES)
        update(type, ownerPubkey) { cur -> cur.copy(entries = clean) }
        return clean.size
    }

    // ── Online → offline sync (the ONLY direction that exists) ────────────────

    /**
     * Read-only fetch of the account's list from the relays.
     * Never publishes, never touches the relay list.
     */
    suspend fun fetchOnline(type: ListType, pubkey: String, seedRelays: List<String>): OnlineFetch {
        val result = fetcher.fetchLatest(pubkey, type.kind, seedRelays)
        val event = result.event
        return when {
            event != null -> OnlineFetch.Found(entriesFromEvent(event, pubkey), event.createdAt)
            result.eoseRelays > 0 -> OnlineFetch.Empty
            else -> OnlineFetch.Unreachable
        }
    }

    /**
     * Replaces the local list with [entries] (a copy of the online list).
     * Marks the initial sync as done and stamps [LocalListState.lastSyncedAt].
     * If [enable] is true the switch is also turned on, in the same atomic write.
     */
    suspend fun replaceFromSync(
        type: ListType,
        pubkey: String,
        entries: List<ListEntry>,
        enable: Boolean,
        stampSynced: Boolean = true,
    ) {
        val clean = entries.filter { it.pubkey != pubkey }.distinctBy { it.pubkey }
            .take(ListFileFormat.MAX_ENTRIES)
        update(type, pubkey) { cur ->
            cur.copy(
                enabled = if (enable) true else cur.enabled,
                initialSyncDone = true,
                lastSyncedAt = if (stampSynced) System.currentTimeMillis() / 1000 else cur.lastSyncedAt,
                entries = clean,
            )
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Atomic read-modify-write inside DataStore's edit mutex. */
    private suspend fun update(
        type: ListType,
        pubkey: String,
        transform: (LocalListState) -> LocalListState,
    ): LocalListState {
        val key = ListStorageKeys.state(type, pubkey)
        var result = LocalListState()
        dataStore.edit { prefs ->
            val next = transform(decode(prefs[key]))
            prefs[key] = NostrJson.encodeToString(LocalListState.serializer(), next)
            result = next
        }
        return result
    }

    private fun decode(raw: String?): LocalListState {
        if (raw.isNullOrEmpty()) return LocalListState()
        return runCatching { NostrJson.decodeFromString(LocalListState.serializer(), raw) }
            .onFailure { Timber.w(it, "Could not decode offline list state") }
            .getOrDefault(LocalListState())
    }

    companion object {
        /** p-tags of a list event → entries (valid pubkeys only, de-duplicated, owner excluded). */
        fun entriesFromEvent(event: Event, ownerPubkey: String? = null): List<ListEntry> {
            val out = LinkedHashMap<String, ListEntry>()
            for (tag in event.parsedTags) {
                if (tag.name != "p") continue
                val pk = ListFileFormat.normalise(tag.value(1)) ?: continue
                if (pk == ownerPubkey || pk in out) continue
                val relay = tag.value(2).orEmpty().takeIf { it.startsWith("wss://") || it.startsWith("ws://") } ?: ""
                out[pk] = ListEntry(pk, relay, tag.value(3).orEmpty())
            }
            return out.values.toList()
        }
    }
}
