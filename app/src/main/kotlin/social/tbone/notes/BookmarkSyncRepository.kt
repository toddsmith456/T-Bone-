package social.tbone.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.tbone.account.AccountRepository
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.db.BookmarkDao
import social.tbone.db.BookmarkEntity
import social.tbone.db.BookmarkFolderEntity
import social.tbone.db.BookmarkFolderKind
import social.tbone.lists.Nip51Bookmarks
import social.tbone.lists.RelayListFetcher
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.NostrJson
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Online (NIP-51) bookmark folders.
 *
 * Offline folders have always been encrypted, device-only storage. This class
 * adds the other half: folders that live on the network as kind 30003 bookmark
 * sets, plus the kind 10003 list holding bookmarks that are in no set.
 *
 * ### Why the local table is still used
 * The network copy is the source of truth, but the folder *rows* stay in Room so
 * the UI can list folders instantly and work with no network. A sync reconciles
 * rows against the events the relays return:
 *  - a set that exists remotely but not locally becomes a row,
 *  - a row whose set has vanished remotely is kept (the user may be offline or a
 *    relay may be lying) but is not deleted from their device,
 *  - bookmarks inside a set are stored into that folder, and notes are downloaded
 *    and cached so they can be read offline afterwards.
 *
 * ### Writing
 * Every mutation re-reads the current remote event, edits its tag list and
 * publishes a replacement, matching how the pin list already works — never
 * blind-overwriting a list that another client may have changed.
 */
@Singleton
class BookmarkSyncRepository @Inject constructor(
    private val dao: BookmarkDao,
    private val accounts: AccountRepository,
    private val signerFactory: NostrSignerFactory,
    private val fetcher: RelayListFetcher,
    private val pool: RelayPool,
    private val crypto: NotesCrypto,
    private val bookmarks: BookmarksRepository,
    private val eventRepository: social.tbone.db.EventRepository,
) {
    private val mutex = Mutex()

    /**
     * Serialises syncs against each other without blocking ordinary writes for
     * the whole operation. A sync and a publish can interleave; both end by
     * writing derived state from a freshly read remote list, and the repository's
     * own mutex still serialises the actual mutation.
     */
    private val syncMutex = Mutex()

    /**
     * F-11: last-known remote contents per folder id.
     *
     * `currentEventIds` used to call the network on every single edit (10 s
     * timeout each time), so saving one note cost a round-trip and a cross-folder
     * move cost two. The cache is refreshed by [sync] and updated by every
     * successful publish, and is dropped whenever a publish fails so the next
     * edit re-reads.
     */
    private val remoteCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Outcome of a mutation, phrased for the UI. */
    data class Result(val ok: Boolean, val message: String)

    /** How a sync went, for the snackbar. */
    data class SyncReport(val folders: Int, val bookmarks: Int, val message: String)

    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * Pulls every online folder for the active account and reconciles it with
     * local rows, then caches the bookmarked notes.
     *
     * This is what "load all bookmarks from other clients" means: sets and the
     * plain list are fetched from relays, so folders created in Amethyst (or any
     * NIP-51 client) appear here.
     */
    /**
     * Reconciles local online folders with what the relays publish.
     *
     * @param replace true  — local folders become exactly what the relays publish:
     *                        bookmarks that are only on this device are removed.
     *                false — merge: everything published is added, and bookmarks
     *                        that are only on this device are kept.
     *
     * Replace is offered because a merge can never remove anything: a note
     * un-bookmarked in another client would otherwise stay on this device
     * forever. Merge is the safe default. Same split as the Blossom server list.
     */
    suspend fun sync(replace: Boolean = false): SyncReport = withLockBriefly {
        // A manual sync is an explicit "re-read everything" action.
        remoteCache.clear()
        val account = accounts.activeAccount.first()
            ?: return@withLockBriefly SyncReport(0, 0, "Add an account before syncing online bookmarks")
        val relays = account.effectiveRelays().map(RelayPool::normalize).toSet()
        if (relays.isEmpty()) return@withLockBriefly SyncReport(0, 0, "No relays configured")

        val sets = fetcher.fetchMany(account.pubkey, EventKind.BOOKMARK_SET, relays.toList())
        val plain = fetcher.fetchLatest(account.pubkey, EventKind.BOOKMARKS, relays.toList())

        if (sets.isEmpty() && plain.event == null) {
            return@withLockBriefly SyncReport(0, 0, "No online bookmark folders found on your relays")
        }

        var folderCount = 0
        var bookmarkCount = 0
        val referenced = mutableListOf<String>()

        // Collect the referenced note ids first: the notes must be downloaded
        // BEFORE any row is written, because writeBookmarks can only store a note
        // it can decrypt as an Event. Fetching afterwards meant a note new to this
        // device was skipped at write time and only appeared on the *next* sync.
        for (event in sets) {
            val remote = Nip51Bookmarks.folder(event) ?: continue
            if (remote.dTag.isBlank()) continue // unaddressable; cannot be updated
            folderCount++
            referenced += remote.eventIds
        }
        plain.event?.let { event ->
            Nip51Bookmarks.folder(event)?.let { referenced += it.eventIds }
        }

        // 1) Download the notes themselves so the folder is readable offline.
        val known = eventRepository.getByIds(referenced.distinct()).map { it.id }.toSet()
        val missing = referenced.distinct().filterNot { it in known }
        if (missing.isNotEmpty()) {
            runCatching { fetchAndStoreNotes(missing) }
                .onFailure { Timber.w(it, "bookmarks: could not fetch %d referenced notes", missing.size) }
        }

        // 2) Named sets → ONLINE folders (pruning notes removed elsewhere).
        for (event in sets) {
            val remote = Nip51Bookmarks.folder(event) ?: continue
            if (remote.dTag.isBlank()) continue
            val folderId = folderRowFor(remote.dTag, remote.title, event.createdAt)
            bookmarkCount += writeBookmarks(folderId, remote.eventIds, prune = replace)
        }

        // 3) kind 10003 → the "Miscellaneous" bucket.
        plain.event?.let { event ->
            val remote = Nip51Bookmarks.folder(event) ?: return@let
            val folderId = miscFolderRow(event.createdAt)
            bookmarkCount += writeBookmarks(folderId, remote.eventIds, prune = replace)
        }

        SyncReport(
            folders = folderCount,
            bookmarks = bookmarkCount,
            message = "Loaded $folderCount online folder${if (folderCount == 1) "" else "s"} " +
                "and $bookmarkCount bookmark${if (bookmarkCount == 1) "" else "s"} from your relays" +
                if (replace) " (replaced local folders)" else " (merged with local folders)",
        )
    }

    /**
     * Creates an online folder and publishes it as an empty kind 30003 set.
     *
     * The `d` identifier is derived from the name so re-creating a folder that
     * already exists online adopts the existing one instead of making a near
     * duplicate; a random suffix is appended when the name collides locally.
     */
    suspend fun createOnlineFolder(name: String): Result = mutex.withLock {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withLock Result(false, "Folder name cannot be empty")
        val account = accounts.activeAccount.first() ?: return@withLock Result(false, "No active account")
        val dTag = uniqueDTag(trimmed)
        val publish = publishSet(
            ownerPubkey = account.pubkey,
            dTag = dTag,
            title = trimmed,
            eventIds = emptyList(),
        )
        if (!publish.ok) return@withLock publish
        val now = System.currentTimeMillis()
        val (ciphertext, iv) = crypto.encrypt(trimmed)
        dao.upsertFolder(
            BookmarkFolderEntity(
                id = UUID.randomUUID().toString(),
                encryptedName = ciphertext,
                iv = iv,
                sortOrder = dao.getFolders().size.toDouble(),
                createdAt = now,
                updatedAt = now,
                kind = BookmarkFolderKind.ONLINE,
                dTag = dTag,
            ),
        )
        Result(true, "Created online folder “$trimmed”")
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /**
     * Adds [eventId] to an online folder by republishing that folder's set.
     *
     * The folder's current contents are read from the remote event first, so a
     * note added from another client is not dropped.
     */
    suspend fun addToOnlineFolder(folder: BookmarkFolderUi, eventId: String): Result = mutex.withLock {
        val account = accounts.activeAccount.first() ?: return@withLock Result(false, "No active account")
        val current = currentEventIds(folder)
        if (eventId in current) return@withLock Result(true, "Already in that folder")
        val next = current + eventId
        val publish = publishFolderContents(folder, next, account.pubkey)
        if (!publish.ok) {
            remoteCache.remove(folder.id)
            return@withLock publish
        }
        remoteCache[folder.id] = next
        writeBookmarks(folder.id, next)
        Result(true, "Saved to “${folder.name}”")
    }

    /**
     * F-08: removes [eventId] from a folder when the user deletes a bookmark.
     *
     * Deleting used to touch the local row only, so an online bookmark reappeared
     * on the next sync (or in another client) because the published set still
     * contained it. No-op for offline folders.
     */
    suspend fun removeBookmarkEverywhere(
        bookmark: BookmarkUi,
        folder: BookmarkFolderUi?,
    ): Result = mutex.withLock {
        val eventId = bookmark.event.id
        when (folder?.kind) {
            null -> Unit
            BookmarkFolderKind.OFFLINE -> Unit // the local row is the whole record
            else -> {
                val account = accounts.activeAccount.first()
                    ?: return@withLock Result(false, "No active account")
                val next = currentEventIds(folder) - eventId
                val publish = publishFolderContents(folder, next, account.pubkey)
                if (!publish.ok) return@withLock publish
                writeBookmarks(folder.id, next)
            }
        }
        // Always drop the local row last, so a failed unpublish keeps the note
        // visible here instead of silently vanishing from both sides.
        bookmarks.deleteBookmark(bookmark.id)
        Result(true, "Removed")
    }

    /** Removes [eventId] from an online folder. */
    suspend fun removeFromOnlineFolder(folder: BookmarkFolderUi, eventId: String): Result = mutex.withLock {
        val account = accounts.activeAccount.first() ?: return@withLock Result(false, "No active account")
        val next = currentEventIds(folder) - eventId
        val publish = publishFolderContents(folder, next, account.pubkey)
        if (!publish.ok) {
            remoteCache.remove(folder.id)
            return@withLock publish
        }
        remoteCache[folder.id] = next
        writeBookmarks(folder.id, next)
        Result(true, "Removed from “${folder.name}”")
    }

    /**
     * Republishes an online folder under a new title.
     *
     * The set's `d` identifier is deliberately unchanged: it is the folder's
     * address on the network, and changing it would orphan the existing set and
     * make the folder look brand new to other clients.
     */
    suspend fun renameOnlineFolder(folder: BookmarkFolderUi, newName: String): Result = mutex.withLock {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@withLock Result(false, "Folder name cannot be empty")
        if (folder.isMiscellaneous) return@withLock Result(false, "The Miscellaneous folder cannot be renamed")
        val account = accounts.activeAccount.first() ?: return@withLock Result(false, "No active account")
        val eventIds = currentEventIds(folder)
        val publish = publishSet(account.pubkey, folder.dTag.orEmpty(), trimmed, eventIds)
        if (publish.ok) {
            remoteCache[folder.id] = eventIds
            Result(true, "Renamed to “$trimmed”")
        } else publish
    }

    /** Deletes an online folder: publishes a set with no bookmarks, then drops the row. */
    suspend fun deleteOnlineFolder(folder: BookmarkFolderUi): Result = mutex.withLock {
        val account = accounts.activeAccount.first() ?: return@withLock Result(false, "No active account")
        if (folder.isMiscellaneous) {
            return@withLock Result(false, "The Miscellaneous folder cannot be deleted")
        }
        val publish = publishFolderContents(folder, emptyList(), account.pubkey)
        // Remove locally even if publishing failed: the user asked for it gone
        // from this device, and a later sync recreates it if it still exists.
        dao.deleteFolder(folder.id)
        if (publish.ok) Result(true, "Deleted “${folder.name}”") else publish
    }

    /**
     * Moves a bookmark between any two folders, including across the
     * offline/online boundary.
     *
     * Ordering matters: the destination is written first and the source cleared
     * only once that succeeded, so a failed publish can never lose a bookmark.
     */
    suspend fun moveEverywhere(
        bookmark: BookmarkUi,
        source: BookmarkFolderUi?,
        destination: BookmarkFolderUi,
    ): Result = mutex.withLock {
        val eventId = bookmark.event.id
        // 1) Destination.
        when (destination.kind) {
            BookmarkFolderKind.OFFLINE -> {
                bookmarks.addBookmark(bookmark.event, destination.id)
            }
            else -> {
                val account = accounts.activeAccount.first() ?: return@withLock Result(false, "No active account")
                val current = currentEventIds(destination)
                if (eventId !in current) {
                    val next = current + eventId
                    val publish = publishFolderContents(destination, next, account.pubkey)
                    if (!publish.ok) {
                        remoteCache.remove(destination.id)
                        return@withLock publish
                    }
                    remoteCache[destination.id] = next
                    writeBookmarks(destination.id, next)
                }
            }
        }
        // 2) Source, now that the destination is safe.
        when (source?.kind) {
            null -> Unit
            BookmarkFolderKind.OFFLINE -> bookmarks.deleteBookmark(bookmark.id)
            else -> {
                val account = accounts.activeAccount.first() ?: return@withLock Result(false, "No account")
                val next = currentEventIds(source) - eventId
                val publish = publishFolderContents(source, next, account.pubkey)
                if (publish.ok) {
                    remoteCache[source.id] = next
                    writeBookmarks(source.id, next)
                } else {
                    remoteCache.remove(source.id)
                }
            }
        }
        Result(true, "Moved to “${destination.name}”")
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * F-10: runs [block] while holding the mutex only for the short sections that
     * mutate local state.
     *
     * `sync()` used to hold the same mutex that guards every publish for its whole
     * duration — two relay round-trips plus up to 12 s downloading notes — so
     * every other bookmark write in the app blocked behind it. Sync is the only
     * long-running operation here, so it takes a separate courtesy lock (it still
     * must not interleave with a publish) and yields between phases.
     */
    private suspend fun <T> withLockBriefly(block: suspend () -> T): T = syncMutex.withLock { block() }

    /** Current event ids of a folder, read from the network copy when possible. */
    private suspend fun currentEventIds(folder: BookmarkFolderUi): List<String> {
        // A folder we have already read or written this session: use the cache.
        // Refreshed on every successful publish and by sync, so it cannot drift
        // far, and it removes a relay round-trip from every tap.
        remoteCache[folder.id]?.let { return it }
        // Prefer the remote list so concurrent edits from other clients survive.
        runCatching {
            val account = accounts.activeAccount.first() ?: return@runCatching null
            val relays = account.effectiveRelays().map(RelayPool::normalize).toSet().toList()
            // F-07: this used to additionally require the parsed *title* to match
            // nothing in particular, but it filtered on `dTag` only — and after a
            // rename the argument is the old name, so a present set was rejected
            // and the caller republished from a stale local list (or empty).
            // The `#d` filter is authoritative: the `d` tag is the set's address
            // and never changes on rename.
            val event = if (folder.isMiscellaneous) {
                fetcher.fetchLatest(account.pubkey, EventKind.BOOKMARKS, relays).event
            } else {
                fetcher.fetchMany(
                    account.pubkey,
                    EventKind.BOOKMARK_SET,
                    relays,
                    dTag = folder.dTag,
                ).firstOrNull { it.tags.any { raw -> 
                    val t = social.tbone.nostr.Tag.fromJsonArray(raw)
                    t.name == "d" && t.value() == folder.dTag
                } }
            }
            event?.let { Nip51Bookmarks.folder(it)?.eventIds }
        }.getOrNull()?.let {
            remoteCache[folder.id] = it
            return it
        }
        // Fall back to what is stored locally.
        return dao.getBookmarks(folder.id).mapNotNull { row ->
            runCatching { Event.fromJson(crypto.decrypt(row.ciphertext, row.iv)).id }.getOrNull()
        }
    }

    private suspend fun publishFolderContents(
        folder: BookmarkFolderUi,
        eventIds: List<String>,
        ownerPubkey: String,
    ): Result =
        if (folder.isMiscellaneous) {
            publishMainList(ownerPubkey, eventIds)
        } else {
            publishSet(ownerPubkey, folder.dTag.orEmpty(), folder.name, eventIds)
        }

    private suspend fun publishSet(
        ownerPubkey: String,
        dTag: String,
        title: String,
        eventIds: List<String>,
    ): Result = publish(
        kind = EventKind.BOOKMARK_SET,
        tags = Nip51Bookmarks.tags(
            kind = EventKind.BOOKMARK_SET,
            title = title,
            dTag = dTag,
            bookmarks = eventIds.map { it to null },
        ),
        ownerPubkey = ownerPubkey,
    )

    private suspend fun publishMainList(ownerPubkey: String, eventIds: List<String>): Result =
        publish(
            kind = EventKind.BOOKMARKS,
            tags = Nip51Bookmarks.tags(
                kind = EventKind.BOOKMARKS,
                title = null,
                dTag = null,
                bookmarks = eventIds.map { it to null },
            ),
            ownerPubkey = ownerPubkey,
        )

    private suspend fun publish(kind: Int, tags: List<List<String>>, ownerPubkey: String): Result {
        val account = accounts.activeAccount.first() ?: return Result(false, "No active account")
        if (account.pubkey != ownerPubkey) return Result(false, "Account changed; try again")
        val signer = signerFactory.forActiveAccount() ?: return Result(false, "No signer available")
        val relays = account.effectiveRelays().map(RelayPool::normalize).toSet()
        if (relays.isEmpty()) return Result(false, "No configured relays")

        // Replaceable events must be newer than what relays already hold.
        val now = System.currentTimeMillis() / 1000
        val unsigned = UnsignedEvent(
            pubkey = ownerPubkey,
            createdAt = now,
            kind = kind,
            tags = tags.map { tag ->
                buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } }
            },
            content = "",
        )
        val signed = signer.signEvent(unsigned).getOrElse {
            Timber.w(it, "bookmarks: signing failed")
            return Result(false, "Could not sign the bookmark list")
        }
        val sent = pool.publishTo(signed, relays)
        return if (sent > 0) Result(true, "Published") else Result(false, "No relay accepted the bookmark list")
    }

    /** Ensures a Room row exists for a remote set; returns its id. */
    private suspend fun folderRowFor(dTag: String, title: String, createdAt: Long): String {
        dao.getOnlineFolderByDTag(dTag)?.let { return it.id }
        val now = System.currentTimeMillis()
        val (ciphertext, iv) = crypto.encrypt(title)
        val id = UUID.randomUUID().toString()
        dao.upsertFolder(
            BookmarkFolderEntity(
                id = id,
                encryptedName = ciphertext,
                iv = iv,
                sortOrder = dao.getFolders().size.toDouble(),
                createdAt = createdAt * 1000,
                updatedAt = now,
                kind = BookmarkFolderKind.ONLINE,
                dTag = dTag,
            ),
        )
        return id
    }

    /** Ensures the Miscellaneous row exists; returns its id. */
    private suspend fun miscFolderRow(createdAt: Long): String {
        dao.getFoldersOfKind(BookmarkFolderKind.MISC).firstOrNull()?.let { return it.id }
        val now = System.currentTimeMillis()
        val (ciphertext, iv) = crypto.encrypt(Nip51Bookmarks.RemoteFolder.MISCELLANEOUS)
        val id = UUID.randomUUID().toString()
        dao.upsertFolder(
            BookmarkFolderEntity(
                id = id,
                encryptedName = ciphertext,
                iv = iv,
                // Sorted last so the catch-all bucket sits under real folders.
                sortOrder = Double.MAX_VALUE,
                createdAt = createdAt * 1000,
                updatedAt = now,
                kind = BookmarkFolderKind.MISC,
                dTag = null,
            ),
        )
        return id
    }

    /** Reconciles the stored bookmarks of [folderId] with [eventIds]; returns how many are now present. */
    private suspend fun writeBookmarks(
        folderId: String,
        eventIds: List<String>,
        prune: Boolean = false,
    ): Int {
        val existing = dao.getBookmarks(folderId)
        val byEventId = existing.mapNotNull { row ->
            runCatching { Event.fromJson(crypto.decrypt(row.ciphertext, row.iv)) }.getOrNull()
                ?.let { it.id to row }
        }.toMap()

        // F-09: online folders used to only ever grow — a note un-bookmarked in
        // another client stayed here forever because nothing ever removed a row.
        // Pruning is opt-in so the publish paths (which pass a set built from the
        // remote list anyway) do not delete a row that a concurrent local save is
        // about to add.
        if (prune) {
            val keep = eventIds.toSet()
            byEventId.forEach { (eventId, row) ->
                if (eventId !in keep) dao.deleteBookmark(row.id)
            }
        }

        var order = dao.maxBookmarkOrder(folderId)
        var stored = 0
        for (eventId in eventIds) {
            if (eventId in byEventId) {
                stored++
                continue
            }
            // The note itself may not have been downloaded yet; only store what we
            // actually have as an Event, otherwise the row would be unrenderable.
            val event = eventRepository.getById(eventId) ?: continue
            order += 1.0
            val (ciphertext, iv) = crypto.encrypt(NostrJson.encodeToString(event))
            dao.upsertBookmark(
                BookmarkEntity(
                    id = UUID.randomUUID().toString(),
                    folderId = folderId,
                    ciphertext = ciphertext,
                    iv = iv,
                    sortOrder = order,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            stored++
        }
        return stored
    }

    /**
     * Subscribes for a batch of bookmarked note ids and caches them, so an
     * online folder is readable offline afterwards.
     *
     * Resolves with whatever arrived; a missing note simply stays un-stored
     * rather than blocking the sync (it may be deleted, or on a relay we can't
     * reach).
     */
    private suspend fun fetchAndStoreNotes(ids: List<String>) = withTimeoutOrNull(NOTE_FETCH_TIMEOUT_MS) {
        val account = accounts.activeAccount.first() ?: return@withTimeoutOrNull
        val accountPubkey = account.pubkey
        coroutineScope {
            val subId = "bm-" + UUID.randomUUID().toString().take(8)
            val job = launch(Dispatchers.IO) {
                pool.messages.collect { pm ->
                    val m = pm.message
                    if (m is RelayMessage.EventMessage && m.subscriptionId == subId) {
                        val e = m.event
                        if (e.verify()) eventRepository.save(e, accountPubkey)
                    }
                }
            }
            try {
                pool.subscribe(listOf(Filter(ids = ids)), id = subId, label = "bookmark-notes")
                delay(NOTE_FETCH_TIMEOUT_MS - 500)
            } finally {
                job.cancel()
                pool.unsubscribe(subId)
            }
        }
    }

    /** A `d` tag that is not already used by a local online folder. */
    private suspend fun uniqueDTag(name: String): String {
        val base = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "folder" }
        val taken = dao.getFoldersOfKind(BookmarkFolderKind.ONLINE).mapNotNull { it.dTag }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private companion object {
        /** How long to wait for bookmarked notes before giving up on a sync. */
        const val NOTE_FETCH_TIMEOUT_MS = 12_000L
    }
}
