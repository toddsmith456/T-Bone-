package social.tbone.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import social.tbone.account.AccountRepository
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.db.EventRepository
import social.tbone.lists.MuteListRepository
import social.tbone.lists.MuteOutcome
import social.tbone.nostr.Event
import social.tbone.nostr.Nip09Deletion
import social.tbone.nostr.Nip56Report
import social.tbone.nostr.Nip56ReportReason
import social.tbone.nostr.quotedEventId
import social.tbone.profile.ProfileRepository
import social.tbone.notes.BookmarkFolderUi
import social.tbone.notes.BookmarkSaveResult
import social.tbone.notes.BookmarksRepository
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import social.tbone.settings.AppSettings
import javax.inject.Inject

@HiltViewModel
class NoteActionsViewModel @Inject constructor(
    appSettings: AppSettings,
    private val accountRepository: AccountRepository,
    private val muteListRepository: MuteListRepository,
    private val signerFactory: NostrSignerFactory,
    private val relayPool: RelayPool,
    private val bookmarksRepository: BookmarksRepository,
    private val bookmarksSync: social.tbone.notes.BookmarkSyncRepository,
    private val eventRepository: EventRepository,
    private val profileRepository: ProfileRepository,
    private val pins: social.tbone.lists.PinListRepository,
) : ViewModel() {
    val moderationActionsEnabled = appSettings.noteMenuModerationEnabled

    /** The active account's pubkey — deletion requests only make sense on your own notes. */
    val activePubkey: StateFlow<String?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val bookmarkFolders: StateFlow<List<BookmarkFolderUi>> = bookmarksRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Resolves the note that [event] quotes, far enough to draw a real quote
     * block on a share card.
     *
     * Cache-first and synchronous with respect to the caller: the feed and
     * every thread view already store resolved quotes in the event cache, so
     * this is normally one indexed read with no relay round-trip. Returns null
     * only when the note quotes nothing or the quoted note is on no device /
     * relay we have seen — the card then draws an "unavailable" quote box.
     */
    suspend fun quoteFor(event: Event): ShareCardQuote? {
        val quotedId = event.parsedTags.quotedEventId
            ?: extractInlineQuoteId(event.content)
            ?: return null
        val quoted = eventRepository.getById(quotedId) ?: return null
        val media = ShareCardMediaParser.parse(quoted)
        val nestedId = quoted.parsedTags.quotedEventId ?: extractInlineQuoteId(quoted.content)
        val body = ShareCardMediaParser.stripMediaUrls(quoted.content, media)
        val text = if (nestedId != null) stripQuoteRefs(body, nestedId) else body
        return ShareCardQuote(
            eventId = quoted.id,
            authorPubkey = quoted.pubkey,
            authorName = profileRepository.profiles.value[quoted.pubkey]?.bestName,
            text = text,
            mediaCount = media.size,
        )
    }

    fun togglePin(event: Event, onFinished: (String) -> Unit) {
        viewModelScope.launch { onFinished(runCatching { pins.toggle(event) }.getOrElse { "Could not update pin list" }) }
    }

    /**
     * Bookmarks [event] into [folder].
     *
     * Online folders also publish the note id to the network (NIP-51), so the
     * bookmark appears in other clients; offline folders stay encrypted on this
     * device. The local row is always written first so the note is readable
     * offline even if publishing fails.
     */
    fun saveBookmark(event: Event, folder: BookmarkFolderUi, onFinished: (String) -> Unit) {
        val epoch = bookmarksRepository.currentWipeEpoch()
        viewModelScope.launch {
            val local = runCatching { bookmarksRepository.addBookmark(event, folder.id, epoch) }
            val localFailure = local.exceptionOrNull()?.message
            if (localFailure != null) {
                onFinished(localFailure)
                return@launch
            }
            if (!folder.isOnline) {
                onFinished(
                    when (local.getOrNull()) {
                        BookmarkSaveResult.SAVED -> "Saved to “${folder.name}”"
                        else -> "Already saved in that folder"
                    },
                )
                return@launch
            }
            val synced = bookmarksSync.addToOnlineFolder(folder, event.id)
            onFinished(if (synced.ok) "Saved to “${folder.name}” (online)" else synced.message)
        }
    }

    fun blockAuthor(pubkey: String, onFinished: (String) -> Unit) {
        viewModelScope.launch {
            val activePubkey = accountRepository.activeAccount.first()?.pubkey
            if (activePubkey == null) {
                onFinished("No active account")
                return@launch
            }
            if (activePubkey == pubkey) {
                onFinished("You can't mute your active account")
                return@launch
            }
            val message = when (runCatching { muteListRepository.setMuted(pubkey, true) }
                .getOrElse { MuteOutcome.SIGN_FAILED }) {
                MuteOutcome.SAVED_LOCAL -> "Poster muted on this device"
                MuteOutcome.PUBLISHED -> "Poster muted"
                MuteOutcome.UNCHANGED -> "Poster is already muted"
                MuteOutcome.NO_SIGNER -> "No signer available — poster was not muted"
                MuteOutcome.RELAYS_UNREACHABLE -> "Couldn't reach relays — poster was not muted"
                MuteOutcome.SIGN_FAILED -> "Mute failed — poster was not muted"
                MuteOutcome.NO_ACCOUNT -> "No active account"
                MuteOutcome.REJECTED -> "Can't mute this account"
            }
            onFinished(message)
        }
    }

    /**
     * Signs and publishes a NIP-09 deletion request for one of the user's
     * own notes. Relays are asked to delete the event; whether they do is
     * up to them, so the result wording says exactly that.
     */
    fun requestDeletion(
        event: Event,
        onFinished: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val signer = signerFactory.forActiveAccount()
            if (signer == null) {
                onFinished("No signer available — deletion was not requested")
                return@launch
            }
            if (signer.pubkey != event.pubkey) {
                onFinished("You can only request deletion of your own notes")
                return@launch
            }
            val unsigned = Nip09Deletion.forNote(
                requesterPubkey = signer.pubkey,
                target = event,
            )
            signer.signEvent(unsigned)
                .onSuccess { signed ->
                    val connected = relayPool.relayStatuses.value.values.count { it == RelayStatus.CONNECTED }
                    relayPool.publish(signed)
                    onFinished(
                        if (connected > 0) {
                            "Deletion requested on $connected connected relay(s) — relays may or may not remove the note, and copies may already exist elsewhere"
                        } else {
                            "Deletion request signed, but no relays are connected"
                        },
                    )
                }
                .onFailure { onFinished("Deletion request signing failed") }
        }
    }

    fun reportNote(
        event: Event,
        reason: Nip56ReportReason,
        comment: String,
        onFinished: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val signer = signerFactory.forActiveAccount()
            if (signer == null) {
                onFinished("No signer available — report was not sent")
                return@launch
            }
            val unsigned = Nip56Report.forNote(
                reporterPubkey = signer.pubkey,
                target = event,
                reason = reason,
                comment = comment,
            )
            signer.signEvent(unsigned)
                .onSuccess { signed ->
                    val connected = relayPool.relayStatuses.value.values.count { it == RelayStatus.CONNECTED }
                    relayPool.publish(signed)
                    onFinished(
                        if (connected > 0) "NIP-56 report sent to $connected connected relay(s)"
                        else "Report signed, but no relays are connected",
                    )
                }
                .onFailure { onFinished("Report signing failed") }
        }
    }
}
