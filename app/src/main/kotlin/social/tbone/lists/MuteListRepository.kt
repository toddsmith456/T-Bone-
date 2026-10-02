package social.tbone.lists

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import social.tbone.account.AccountRepository
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.nostr.Event
import social.tbone.nostr.Filter
import social.tbone.nostr.NostrJson
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.settings.AppSettings
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** What happened when the user pressed BLOCK / UNBLOCK. */
enum class MuteOutcome {
    /** Offline mode: saved to the local list only (nothing sent anywhere). */
    SAVED_LOCAL,

    /** Online mode: a new NIP-51 mute list was signed and published. */
    PUBLISHED,

    /** Nothing needed doing (already in the requested state). */
    UNCHANGED,

    /** Online mode: no signer available for the active account. */
    NO_SIGNER,

    /** Online mode: no relay answered, so the existing list couldn't be read safely. Nothing was changed. */
    RELAYS_UNREACHABLE,

    /** Online mode: signing failed or was cancelled. */
    SIGN_FAILED,

    /** No active account. */
    NO_ACCOUNT,

    /** Local list is full / invalid target. */
    REJECTED,
}

/**
 * The single source of truth for "who is muted/blocked" for the ACTIVE account.
 *
 *  - Offline mute list ON  → [effectiveMuted] is the local list and NOTHING else.
 *    The relay mute list is neither read nor written.
 *  - Offline mute list OFF → [effectiveMuted] is the account's NIP-51 relay mute list
 *    (kind 10000, public `p` tags) plus any pre-existing device-local blocks from
 *    before this feature existed (so nobody silently becomes un-blocked on update).
 *
 * BLOCK/UNBLOCK ([setMuted]) edits whichever list is active, and only that one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MuteListRepository @Inject constructor(
    private val accountRepository: AccountRepository,
    private val offline: OfflineListRepository,
    private val fetcher: RelayListFetcher,
    private val pool: RelayPool,
    private val appSettings: AppSettings,
    private val signerFactory: NostrSignerFactory,
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    /** The relay mute list as last seen for [pubkey]. [event] is null when only the disk cache is known. */
    private data class RelayMute(
        val pubkey: String,
        val muted: Set<String>,
        val createdAt: Long,
        val event: Event?,
    )

    @Serializable
    private data class RelayMuteCache(val createdAt: Long = 0L, val pubkeys: List<String> = emptyList())

    private val _relay = MutableStateFlow<RelayMute?>(null)

    private val activePubkey: Flow<String?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .distinctUntilChanged()

    /** (active pubkey, local mute list state of that account). */
    private val offlineState: Flow<Pair<String?, LocalListState>> = activePubkey.flatMapLatest { pk ->
        if (pk == null) flowOf<Pair<String?, LocalListState>>(null to LocalListState())
        else offline.state(ListType.MUTES, pk).map { pk to it }
    }

    /** Who is hidden right now (see class doc). */
    val effectiveMuted: StateFlow<Set<String>> = combine(
        offlineState, _relay, appSettings.blockedPubkeys,
    ) { (pk, local), relay, legacy ->
        when {
            pk == null -> emptySet()
            local.enabled -> local.pubkeySet()
            else -> (relay?.takeIf { it.pubkey == pk }?.muted ?: emptySet()) + legacy
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** True while the active account's mute list is in offline mode. */
    val offlineEnabled: StateFlow<Boolean> = offlineState
        .map { (pk, local) -> pk != null && local.enabled }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Live-follow the relay mute list ONLY while offline mode is off.
        // The moment offline mode turns on, this subscription is torn down.
        scope.launch {
            offlineState
                .map { (pk, local) -> if (pk != null && !local.enabled) pk else null }
                .distinctUntilChanged()
                .collectLatest { pk ->
                    _relay.value = null
                    if (pk == null) return@collectLatest
                    loadCache(pk)?.let { c ->
                        _relay.value = RelayMute(pk, c.pubkeys.toSet(), c.createdAt, null)
                    }
                    followRelayList(pk)
                }
        }
    }

    private suspend fun followRelayList(pk: String) {
        var subId: String? = null
        try {
            pool.messages
                .onSubscription {
                    subId = pool.subscribe(
                        listOf(Filter(authors = listOf(pk), kinds = listOf(KIND), limit = 1)),
                        label = "mute-list",
                    )
                }
                .collect { pm ->
                    val m = pm.message
                    if (m is RelayMessage.EventMessage && m.subscriptionId == subId) {
                        val e = m.event
                        if (e.pubkey == pk && e.kind == KIND && e.verify()) acceptRelayEvent(pk, e)
                    }
                }
        } finally {
            subId?.let { pool.unsubscribe(it) }
        }
    }

    private suspend fun acceptRelayEvent(pk: String, e: Event) {
        var accepted = false
        _relay.update { cur ->
            val have = cur?.takeIf { it.pubkey == pk }
            val take = have == null || e.createdAt > have.createdAt ||
                (e.createdAt == have.createdAt && have.event == null)
            accepted = take
            if (take) RelayMute(pk, publicMuted(e, pk), e.createdAt, e) else cur
        }
        if (accepted) saveCache(pk, e.createdAt, publicMuted(e, pk))
    }

    // ── BLOCK / UNBLOCK ───────────────────────────────────────────────────────

    /**
     * Blocks ([muted] = true) or unblocks [target] in whichever list is active.
     *
     * Offline: edits the local list only. Online: signs and publishes an updated
     * NIP-51 mute list that is based on the LATEST list read from the relays
     * (existing tags and the encrypted private part are preserved untouched).
     */
    suspend fun setMuted(target: String, muted: Boolean): MuteOutcome {
        val account = accountRepository.activeAccount.first() ?: return MuteOutcome.NO_ACCOUNT
        val owner = account.pubkey

        // ── Offline mode: local list only. No relay is read or written. ──────
        if (offline.snapshot(ListType.MUTES, owner).enabled) {
            return if (muted) {
                when (offline.add(ListType.MUTES, owner, ListEntry(target))) {
                    AddResult.ADDED -> MuteOutcome.SAVED_LOCAL
                    AddResult.ALREADY_PRESENT -> MuteOutcome.UNCHANGED
                    else -> MuteOutcome.REJECTED
                }
            } else {
                if (offline.remove(ListType.MUTES, owner, target) != null) MuteOutcome.SAVED_LOCAL
                else MuteOutcome.UNCHANGED
            }
        }

        // ── Online mode ──────────────────────────────────────────────────────
        if (target == owner) return MuteOutcome.REJECTED

        // Device-local blocks from before this feature: an UNBLOCK always clears them.
        val hadLegacy = target in appSettings.blockedPubkeys.value
        if (!muted && hadLegacy) appSettings.setBlockedPubkeys(appSettings.blockedPubkeys.value - target)

        val signer = signerFactory.forActiveAccount() ?: return MuteOutcome.NO_SIGNER

        // Always re-read the newest list from the relays right before changing it, so a stale
        // cache can never overwrite the user's real list.
        val fetched = fetcher.fetchLatest(owner, KIND, account.relays)
        if (!fetched.reachedRelays) {
            return if (!muted && hadLegacy) MuteOutcome.SAVED_LOCAL else MuteOutcome.RELAYS_UNREACHABLE
        }
        val base: Event? = listOfNotNull(fetched.event, _relay.value?.takeIf { it.pubkey == owner }?.event)
            .maxByOrNull { it.createdAt }

        val baseTags: List<JsonArray> = base?.tags ?: emptyList()
        val alreadyMuted = base?.let { target in publicMuted(it, owner) } ?: false

        val newTags: List<JsonArray> = if (muted) {
            if (alreadyMuted) return MuteOutcome.UNCHANGED
            baseTags + listOf(buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive(target)) })
        } else {
            if (!alreadyMuted) return if (hadLegacy) MuteOutcome.SAVED_LOCAL else MuteOutcome.UNCHANGED
            baseTags.filterNot { it.isPTagFor(target) }
        }

        val now = System.currentTimeMillis() / 1000
        val unsigned = UnsignedEvent(
            pubkey = signer.pubkey,
            // Replaceable event: must be strictly newer than the one it replaces.
            createdAt = maxOf(now, (base?.createdAt ?: 0L) + 1),
            kind = KIND,
            tags = newTags,
            content = base?.content ?: "",
        )
        val signed = signer.signEvent(unsigned).getOrElse {
            Timber.w(it, "Mute list signing failed")
            return MuteOutcome.SIGN_FAILED
        }
        pool.publish(signed)

        // Reflect immediately in the UI instead of waiting for the relay echo.
        _relay.update { RelayMute(owner, publicMuted(signed, owner), signed.createdAt, signed) }
        saveCache(owner, signed.createdAt, publicMuted(signed, owner))
        return MuteOutcome.PUBLISHED
    }

    // ── Helpers used by the offline-list screen ───────────────────────────────

    /** Pre-feature device-local blocks (see class doc). */
    fun legacyBlocked(): Set<String> = appSettings.blockedPubkeys.value

    /** The last relay mute list we saw for [pubkey] (disk cache); used as a fallback copy. */
    suspend fun cachedRelayMuted(pubkey: String): List<String> =
        _relay.value?.takeIf { it.pubkey == pubkey }?.muted?.toList()
            ?: loadCache(pubkey)?.pubkeys.orEmpty()

    // ── Cache ─────────────────────────────────────────────────────────────────

    private suspend fun loadCache(pk: String): RelayMuteCache? {
        val raw = dataStore.data.first()[ListStorageKeys.relayMuteCache(pk)] ?: return null
        return runCatching { NostrJson.decodeFromString(RelayMuteCache.serializer(), raw) }.getOrNull()
    }

    private suspend fun saveCache(pk: String, createdAt: Long, muted: Set<String>) {
        val json = NostrJson.encodeToString(RelayMuteCache.serializer(), RelayMuteCache(createdAt, muted.toList()))
        dataStore.edit { it[ListStorageKeys.relayMuteCache(pk)] = json }
    }

    private fun publicMuted(e: Event, owner: String): Set<String> =
        OfflineListRepository.entriesFromEvent(e, owner).mapTo(LinkedHashSet()) { it.pubkey }

    private fun JsonArray.isPTagFor(pubkey: String): Boolean {
        val name = (getOrNull(0) as? JsonPrimitive)?.content
        val value = (getOrNull(1) as? JsonPrimitive)?.content
        return name == "p" && value?.lowercase() == pubkey
    }

    companion object {
        const val KIND = 10000
    }
}
