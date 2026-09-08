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
) : ViewModel() {

    val channels: StateFlow<List<GeohashChannelEntry>> = appSettings.geohashChannels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        viewModelScope.launch {
            val current = channels.value
            val first = current.minOfOrNull { it.sortOrder } ?: 0.0
            appSettings.setGeohashChannels(
                listOf(GeohashChannelEntry(g, pinned = false, sortOrder = first - 1.0)) + current,
            )
            onResult(true)
        }
    }

    fun deleteChannel(code: String) {
        viewModelScope.launch {
            appSettings.setGeohashChannels(channels.value.filterNot { it.code == code })
        }
    }

    /** Pins/unpins a channel to the home screen strip. */
    fun pinToHomeToggle(code: String) {
        viewModelScope.launch {
            appSettings.setGeohashChannels(
                channels.value.map {
                    if (it.code == code) it.copy(pinnedToHome = !it.pinnedToHome) else it
                },
            )
        }
    }

    fun pinToggle(code: String) {
        viewModelScope.launch {
            appSettings.setGeohashChannels(
                channels.value.map { if (it.code == code) it.copy(pinned = !it.pinned) else it },
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
        viewModelScope.launch {
            val entries = channels.value.toMutableList()
            val ia = entries.indexOfFirst { it.code == a }
            val ib = entries.indexOfFirst { it.code == b }
            if (ia < 0 || ib < 0) return@launch
            val tmp = entries[ia]
            entries[ia] = entries[ib]
            entries[ib] = tmp
            appSettings.setGeohashChannels(entries)
        }
    }

    fun setNickname(name: String) {
        viewModelScope.launch { appSettings.setGeohashNickname(name.trim().take(24)) }
    }

}
