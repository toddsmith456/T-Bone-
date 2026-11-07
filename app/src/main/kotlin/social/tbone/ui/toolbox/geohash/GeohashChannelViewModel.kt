package social.tbone.ui.toolbox.geohash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.nostr.geohash.Geohash
import social.tbone.nostr.geohash.GeohashChannelEntry
import social.tbone.settings.AppSettings
import javax.inject.Inject

/**
 * Main geohash "messenger" screen: a persisted contact-style list of channels
 * (like Telegram groups), a consistent nickname, and identity helpers. It does
 * NOT connect to any relays — connections only happen when you open a channel
 * (see [GeohashChatViewModel]).
 */
@HiltViewModel
class GeohashChannelViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val noteWrites: social.tbone.notes.NoteWriteCoordinator,
) : ViewModel() {

    val channels: StateFlow<List<GeohashChannelEntry>> = appSettings.geohashChannels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cacheMillis = appSettings.geohashCacheMillis
    fun setCacheMillis(value: Long) { submitChange { appSettings.setGeohashCacheMillis(value) } }

    val nickname: StateFlow<String> = appSettings.geohashNickname
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Adds a channel if the geohash is valid and not already present (new → top). */
    fun addChannel(code: String, onResult: (Boolean) -> Unit = {}) {
        val g = code.lowercase().filter { it in "0123456789bcdefghjkmnpqrstuvwxyz" }
        if (g.isEmpty() || Geohash.decode(g) == null) {
            onResult(false)
            return
        }
        if (channels.value.any { it.code == g }) {
            onResult(true)
            return
        }
        val request = noteWrites.submit {
            val current = appSettings.geohashChannels.value
            if (current.none { it.code == g }) {
                val first = current.minOfOrNull { it.sortOrder } ?: 0.0
                appSettings.setGeohashChannels(
                    listOf(GeohashChannelEntry(g, pinned = false, sortOrder = first - 1.0)) + current,
                )
            }
        }
        viewModelScope.launch {
            try { request.await(); onResult(true) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { timber.log.Timber.e(error, "Could not add geohash channel"); onResult(false) }
        }
    }

    fun deleteChannel(code: String) {
        submitChange {
            appSettings.setGeohashChannels(appSettings.geohashChannels.value.filterNot { it.code == code })
        }
    }

    /** Pins/unpins a channel to the home screen strip. */
    fun pinToHomeToggle(code: String) {
        submitChange {
            appSettings.setGeohashChannels(
                appSettings.geohashChannels.value.map {
                    if (it.code == code) it.copy(pinnedToHome = !it.pinnedToHome) else it
                },
            )
        }
    }

    fun pinToggle(code: String) {
        submitChange {
            appSettings.setGeohashChannels(
                appSettings.geohashChannels.value.map { if (it.code == code) it.copy(pinned = !it.pinned) else it },
            )
        }
    }

    /** Moves one position within the unpinned channels (pinned stay on top). */
    fun moveUp(code: String) {
        val list = channels.value.filter { !it.pinned }
        val idx = list.indexOfFirst { it.code == code }
        if (idx <= 0) return
        swapInList(list[idx].code, list[idx - 1].code)
    }

    fun moveDown(code: String) {
        val list = channels.value.filter { !it.pinned }
        val idx = list.indexOfFirst { it.code == code }
        if (idx < 0 || idx >= list.lastIndex) return
        swapInList(list[idx].code, list[idx + 1].code)
    }

    private fun swapInList(a: String, b: String) {
        submitChange {
            val entries = appSettings.geohashChannels.value.toMutableList()
            val ia = entries.indexOfFirst { it.code == a }
            val ib = entries.indexOfFirst { it.code == b }
            if (ia < 0 || ib < 0) return@submitChange
            val tmp = entries[ia]
            entries[ia] = entries[ib]
            entries[ib] = tmp
            appSettings.setGeohashChannels(entries)
        }
    }

    fun setNickname(name: String) {
        submitChange { appSettings.setGeohashNickname(name.trim().take(24)) }
    }

    private fun submitChange(action: suspend () -> Unit) {
        // Submit before launching, so a delayed UI coroutine cannot sneak an
        // old write in after a wipe has begun.
        val request = noteWrites.submit(action)
        viewModelScope.launch {
            try { request.await() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { timber.log.Timber.e(error, "Could not update geohash settings") }
        }
    }

}
