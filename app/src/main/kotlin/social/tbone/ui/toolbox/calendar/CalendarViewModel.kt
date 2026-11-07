package social.tbone.ui.toolbox.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.tbone.calendar.CalendarCrypto
import social.tbone.calendar.CalendarEventUi
import social.tbone.calendar.RepeatUnit
import social.tbone.calendar.CalendarRepository
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val crypto: CalendarCrypto,
    private val noteWrites: social.tbone.notes.NoteWriteCoordinator,
) : ViewModel() {

    val events: StateFlow<List<CalendarEventUi>> = repository.events
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _encryptionVerified = MutableStateFlow(false)
    val encryptionVerified: StateFlow<Boolean> = _encryptionVerified.asStateFlow()

    private val _hardwareBacked = MutableStateFlow(false)
    val hardwareBacked: StateFlow<Boolean> = _hardwareBacked.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val (verified, hw) = noteWrites.submit {
                    repository.encryptionSelfTest() to repository.isHardwareBacked()
                }.await()
                _encryptionVerified.value = verified
                _hardwareBacked.value = hw
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = "encryption unavailable (${error.message})"
            }
        }
    }

    suspend fun getEvent(id: String): CalendarEventUi? = noteWrites.submit { repository.getById(id) }.await()

    /**
     * Saves a new or existing event (idempotent by id — the same id always
     * upserts ONE row, so repeated save calls can never duplicate an event).
     */
    fun saveEvent(
        id: String?,
        title: String,
        description: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        repeatUnit: RepeatUnit,
        repeatInterval: Int,
        repeatEndMillis: Long?,
        onError: (Exception) -> Unit = {},
        onSaved: (String) -> Unit = {},
    ) {
        if (!_encryptionVerified.value) {
            val error = IllegalStateException("encryption not verified — calendar disabled")
            _error.value = error.message
            onError(error)
            return
        }
        val pending = noteWrites.submit {
                repository.saveEvent(
                    id = id,
                    title = title,
                    description = description,
                    startMillis = startMillis,
                    endMillis = endMillis,
                    allDay = allDay,
                    repeatUnit = repeatUnit,
                    repeatInterval = repeatInterval,
                    repeatEndMillis = repeatEndMillis,
                )
        }
        viewModelScope.launch {
            try { onSaved(pending.await()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not save event (${error.message})"; onError(error) }
        }
    }

    fun deleteEvent(id: String, onError: (Exception) -> Unit = {}, onDeleted: () -> Unit = {}) {
        val pending = noteWrites.submit { repository.deleteEvent(id) }
        viewModelScope.launch {
            try { pending.await(); onDeleted() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not delete event (${error.message})"; onError(error) }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
