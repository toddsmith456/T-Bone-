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
            val (verified, hw) = withContext(Dispatchers.IO) {
                repository.encryptionSelfTest() to repository.isHardwareBacked()
            }
            _encryptionVerified.value = verified
            _hardwareBacked.value = hw
        }
    }

    suspend fun getEvent(id: String): CalendarEventUi? = withContext(Dispatchers.IO) {
        repository.getById(id)
    }

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
        onSaved: (String) -> Unit = {},
    ) {
        if (!_encryptionVerified.value) {
            _error.value = "encryption not verified — calendar disabled"
            return
        }
        viewModelScope.launch {
            runCatching {
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
                .onSuccess { onSaved(it) }
                .onFailure { _error.value = "could not save event (${it.message})" }
        }
    }

    fun deleteEvent(id: String) = viewModelScope.launch {
        runCatching { repository.deleteEvent(id) }
            .onFailure { _error.value = "could not delete event (${it.message})" }
    }

    fun clearError() {
        _error.value = null
    }
}
