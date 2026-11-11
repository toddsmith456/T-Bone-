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
    suspend fun sync(): SyncReport = mutex.withLock {
        val account = accounts.activeAccount.first()
            ?: return@withLock SyncReport(0, 0, "Add an account before syncing online bookmarks")
        val relays = account.effectiveRelays().map(RelayPool::normalize).toSet()
        if (relays.isEmpty()) return@withLock SyncReport(0, 0, "No relays configured")

        val sets = fetcher.fetchMany(account.pubkey, EventKind.BOOKMARK_SET, relays.toList())
        val plain = fetcher.fetchLatest(account.pubkey, EventKind.BOOKMARKS, relays.toList())

        if (sets.isEmpty() && plain.event == null) {
            return@withLock SyncReport(0, 0, "No online bookmark folders found on your relays")
        }

        var folderCount = 0
        var bookmarkCount = 0
        val referenced = mutableListOf<String>()

        // 1) Named sets → ONLINE folders.
        for (event in sets) {
            val remote = Nip51Bookmarks.folder(event) ?: continue
            // A set with no `d` tag cannot be addressed; skip it rather than
            // creating a folder that can never be updated.
            if (remote.dTag.isBlank()) continue
            val folderId = folderRowFor(remote.dTag, remote.title, event.createdAt)
            folderCount++
            bookmarkCount += writeBookmarks(folderId, remote.eventIds)
            referenced += remote.eventIds
        }

        // 2) kind 10003 → the "Miscellaneous" bucket.
        plain.event?.let { event ->
            val remote = Nip51Bookmarks.folder(event) ?: return@let
            val folderId = miscFolderRow(event.createdAt)
            bookmarkCount += writeBookmarks(folderId, remote.eventIds)
            referenced += remote.eventIds
        }

        // 3) Download the notes themselves so the folder is readable offline.
        val known = eventRepository.getByIds(referenced.distinct()).map { it.id }.toSet()
        val missing = referenced.distinct().filterNot { it in known }
        if (missing.isNotEmpty()) {
            runCatching { fetchAndStoreNotes(missing) }
                .onFailure { Timber.w(it, "bookmarks: could not fetch %d referenced notes", missing.size) }
        }

        SyncReport(
            folders = folderCount,
            bookmarks = bookmarkCount,
            message = "Loaded $folderCount online folder${if (folderCount == 1) "" else "s"} " +
                "and $bookmarkCount bookmark${if (bookmarkCount == 1) "" else "s"} from your relays",
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
        if (!publish.ok) return@withLock publish
        writeBookmarks(folder.id, next)
        Result(true, "Saved to “${folder.name}”")
    }

    /** Removes [eventId] from an online folder. */
    suspend fun removeFromOnlineFolder(folder: BookmarkFolderUi, eventId: String): Result = mutex.withLock {
        val account = accounts.activeAccount.first() ?: return@withLock Result(false, "No active account")
        val next = currentEventIds(folder) - eventId
        val publish = publishFolderContents(folder, next, account.pubkey)
        if (!publish.ok) return@withLock publish
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
        if (publish.ok) Result(true, "Renamed to “$trimmed”") else publish
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
                    val publish = publishFolderContents(destination, current + eventId, account.pubkey)
                    if (!publish.ok) return@withLock publish
                    writeBookmarks(destination.id, current + eventId)
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
                if (publish.ok) writeBookmarks(source.id, next)
            }
        }
        Result(true, "Moved to “${destination.name}”")
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Current event ids of a folder, read from the network copy when possible. */
    private suspend fun currentEventIds(folder: BookmarkFolderUi): List<String> {
        // Prefer the remote list so concurrent edits from other clients survive.
        runCatching {
            val account = accounts.activeAccount.first() ?: return@runCatching null
            val relays = account.effectiveRelays().map(RelayPool::normalize).toSet().toList()
            val event = if (folder.isMiscellaneous) {
                fetcher.fetchLatest(account.pubkey, EventKind.BOOKMARKS, relays).event
            } else {
                fetcher.fetchMany(
                    account.pubkey,
                    EventKind.BOOKMARK_SET,
                    relays,
                    dTag = folder.dTag,
                ).firstOrNull { Nip51Bookmarks.folder(it)?.dTag == folder.dTag }
            }
            event?.let { Nip51Bookmarks.folder(it)?.eventIds }
        }.getOrNull()?.let { return it }
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
    private suspend fun writeBookmarks(folderId: String, eventIds: List<String>): Int {
        val existing = dao.getBookmarks(folderId)
        val byEventId = existing.mapNotNull { row ->
            runCatching { Event.fromJson(crypto.decrypt(row.ciphertext, row.iv)) }.getOrNull()
                ?.let { it.id to row }
        }.toMap()

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
