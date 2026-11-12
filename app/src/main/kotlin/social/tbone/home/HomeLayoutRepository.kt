package social.tbone.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The home tab layout, persisted in its own small preferences file.
 *
 * ### Why not AppSettings
 * `AppSettings` already carries 86 keys behind one global state object that
 * every screen observes. A list of user-defined feeds is read by exactly one
 * screen and written only when the user edits it, so putting it there would
 * re-emit the entire settings payload on every layout change and grow every
 * unrelated settings read. A dedicated store keeps the blast radius at zero.
 *
 * ### The rule that matters
 * An absent layout means "the four defaults", never "no tabs". A brand-new
 * install, or an upgrade from a build that had no layout at all, must open to
 * exactly the home screen the user has always had. (This is the same failure
 * the notification filters had in 0.5.6: an unset preference read as an empty
 * set and silently hid a whole feed type. Here it would mean an empty app.)
 */
@Singleton
class HomeLayoutRepository @Inject constructor(
    @HomeLayoutStore private val store: DataStore<Preferences>,
) {

    // Not injected: a bare CoroutineDispatcher is not a Hilt binding, and adding
    // a default value would not help because Dagger ignores Kotlin defaults. The
    // store is the only collaborator that matters for testability.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeMutex = Mutex()

    /** Mirrors the store so callers can read synchronously after the first load. */
    private val _layout = MutableStateFlow(HomeLayout.default())
    val layout: StateFlow<HomeLayout> = _layout.asStateFlow()

    /** True once the stored value (or the defaults) has been read. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        // Read ONCE, then own the value in memory.
        //
        // This was originally a continuous collector on `store.data`, which is
        // the usual DataStore idiom — but it is wrong here. Nothing else writes
        // this file, and a live collector re-emits the previous value after a
        // write, so a fast sequence (add a slot, then reset) could resurrect the
        // pre-reset layout in memory while the store held the new one. A single
        // read makes the race impossible: memory is the truth after load, and
        // the store is just where it is kept.
        scope.launch {
            val stored = runCatching {
                store.data.first()[KEY]
            }.onFailure { Timber.w(it, "home layout: read failed, using defaults") }
                .getOrNull()
            _layout.value = HomeLayoutCodec.decode(stored) ?: HomeLayout.default()
            _loaded.value = true
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * Suspends until the stored layout has been read once.
     *
     * The init read always completes, so this waits for it rather than reading
     * the store a second time — two independent reads could disagree.
     */
    suspend fun awaitLoaded(): HomeLayout {
        _loaded.first { it }
        return _layout.value
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Applies [transform] to the current layout and persists the result.
     *
     * Serialised through a mutex so two quick edits (drag, then toggle) cannot
     * interleave their read-modify-write and lose one of them.
     */
    suspend fun update(transform: (HomeLayout) -> HomeLayout): HomeLayout = writeMutex.withLock {
        val current = awaitLoaded()
        val next = transform(current).normalised()
        persist(next)
        next
    }

    /** Replaces the whole layout — used by import. Rejects an empty result. */
    suspend fun replace(layout: HomeLayout): Boolean = writeMutex.withLock {
        val next = layout.normalised()
        if (next.slots.isEmpty()) return@withLock false
        persist(next)
        true
    }

    suspend fun reorder(orderedIds: List<String>) = update { layout ->
        val byId = layout.slots.associateBy { it.id }
        // Slot 1 is pinned to the follow feed, so it is never moved by a drag.
        val pinned = layout.slots.firstOrNull { it.source is FeedSource.Follows }
        val rest = orderedIds.mapNotNull { byId[it] }
            .filterNot { it.source is FeedSource.Follows }
        // Anything the caller did not mention keeps its relative position.
        val mentioned = rest.map { it.id }.toSet()
        val leftovers = layout.slots.filter {
            it.id !in mentioned && it.source !is FeedSource.Follows
        }
        layout.copy(slots = listOfNotNull(pinned) + rest + leftovers)
    }

    suspend fun upsert(slot: FeedSlot) = update { layout ->
        val index = layout.indexOf(slot.id)
        when {
            index < 0 -> layout.copy(slots = layout.slots + slot)
            else -> layout.copy(slots = layout.slots.toMutableList().also { it[index] = slot })
        }
    }

    /** Adds a source as a new slot at the end, honouring [HomeLayout.MAX_SLOTS]. */
    suspend fun add(source: FeedSource): FeedSlot? {
        var added: FeedSlot? = null
        update { layout ->
            if (layout.slots.size >= HomeLayout.MAX_SLOTS) layout
            else {
                val slot = FeedSlot.of(source)
                added = slot
                layout.copy(slots = layout.slots + slot)
            }
        }
        return added
    }

    /** Removes a slot. The follow slot cannot be removed (invariant 1). */
    suspend fun remove(slotId: String) = update { layout ->
        layout.copy(
            slots = layout.slots.filterNot { it.id == slotId && it.source !is FeedSource.Follows },
        )
    }

    /**
     * Hides or shows a slot. The follow slot stays visible.
     *
     * Hidden slots are kept so a user who hides the clock feed for a while does
     * not have to rebuild it later.
     */
    suspend fun setVisible(slotId: String, visible: Boolean) = update { layout ->
        layout.copy(
            slots = layout.slots.map { slot ->
                when {
                    slot.id != slotId -> slot
                    slot.source is FeedSource.Follows -> slot.copy(visible = true)
                    else -> slot.copy(visible = visible)
                }
            },
        )
    }

    fun rememberActiveSlot(slotId: String) {
        scope.launch {
            runCatching { update { it.copy(lastActiveSlotId = slotId) } }
                .onFailure { Timber.w(it, "home layout: could not remember active slot") }
        }
    }

    suspend fun resetToDefaults(): HomeLayout = update { HomeLayout.default() }

    // ── Transfer ─────────────────────────────────────────────────────────────

    /** The whole layout as copy-pasteable JSON. */
    suspend fun exportJson(): String = HomeLayoutCodec.encode(awaitLoaded())

    /**
     * Applies a layout from plain JSON.
     *
     * All-or-nothing: a partial apply would leave somebody with half of their
     * old tabs and half of the imported ones, which is worse than a clear error.
     */
    suspend fun importJson(raw: String): Result<Unit> {
        val decoded = HomeLayoutCodec.decode(raw)
            ?: return Result.failure(IllegalArgumentException("That is not a T-Bone feed layout"))
        return if (replace(decoded)) Result.success(Unit)
        else Result.failure(IllegalArgumentException("That layout has no feeds in it"))
    }

    private suspend fun persist(layout: HomeLayout) {
        _layout.value = layout
        val encoded = runCatching { HomeLayoutCodec.encode(layout) }.getOrElse { error ->
            Timber.w(error, "home layout: could not encode, keeping in-memory copy")
            return
        }
        runCatching { store.edit { it[KEY] = encoded } }
            .onFailure { Timber.w(it, "home layout: could not persist") }
    }

    companion object {
        private val KEY = stringPreferencesKey("home_layout_json")
    }
}
