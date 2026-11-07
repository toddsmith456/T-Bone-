package social.tbone.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EncryptedBackupUiState(
    val busy: Boolean = false,
    val status: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class EncryptedBackupViewModel @Inject constructor(
    private val backupRepository: EncryptedBackupRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(EncryptedBackupUiState())
    val uiState: StateFlow<EncryptedBackupUiState> = _uiState.asStateFlow()

    fun export(uri: Uri, passphrase: String) = runBackupOperation(
        successText = "Encrypted backup saved.",
        failureText = "Backup could not be saved",
    ) {
        backupRepository.exportTo(uri, passphrase)
    }

    fun restore(uri: Uri, passphrase: String) = runBackupOperation(
        successText = "Backup restored. Notes, folders and calendar were replaced.",
        failureText = "Backup restore did not finish. Check your notes and calendar before retrying",
    ) {
        backupRepository.importFrom(uri, passphrase)
    }

    fun clearStatus() {
        _uiState.value = _uiState.value.copy(status = null, isError = false)
    }

    private fun runBackupOperation(
        successText: String,
        failureText: String,
        operation: suspend () -> Unit,
    ) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.value = EncryptedBackupUiState(busy = true)
            val result = try {
                Result.success(withContext(Dispatchers.IO) { operation() })
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            _uiState.value = result.fold(
                onSuccess = { EncryptedBackupUiState(status = successText) },
                onFailure = { error ->
                    val detail = error.message?.takeIf { it.isNotBlank() }
                    EncryptedBackupUiState(
                        status = if (detail == null) "$failureText." else "$failureText: $detail",
                        isError = true,
                    )
                },
            )
        }
    }
}
