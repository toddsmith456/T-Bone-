package social.tbone.ai

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import social.tbone.di.ImageClientProvider
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ModelInstallState {
    data object NotInstalled : ModelInstallState
    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : ModelInstallState

    data class Installed(
        val file: File,
        val sizeBytes: Long,
        val sha256: String,
    ) : ModelInstallState

    data class Error(val message: String) : ModelInstallState
}

/**
 * Manages optional installable `.tflite` add-on weights in `filesDir/ai_models/`.
 *
 * - **Zero bundled models:** APK stays lean; users install Fast, High-Accuracy, or both.
 * - **Tor-safe downloads:** Uses [ImageClientProvider.client] so Tor/Orbot & TorCallGuard
 *   are strictly respected (never silently bypasses Tor).
 * - **Cryptographic verification:** Streams into a `.part` file, enforces a hard byte cap,
 *   and verifies exact SHA-256 against [AiModelCatalog] BEFORE atomic rename.
 * - **Offline import:** Supports importing locally transferred `.tflite` files via SAF,
 *   likewise gated by exact SHA-256 verification against [AiModelCatalog].
 * - **Duress wipe safe:** Lives inside `filesDir/ai_models/` and exposes [wipeForDuress].
 */
@Singleton
class AiModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val MODELS_DIR_NAME = "ai_models"
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val MAX_IMPORT_BYTES = 28_000_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelsDir: File = File(context.filesDir, MODELS_DIR_NAME)

    private val activeJobs = HashMap<AiModelId, Job>()
    private val jobLock = Any()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    private val _states = MutableStateFlow(scanDiskStates())
    val states: StateFlow<Map<AiModelId, ModelInstallState>> = _states.asStateFlow()

    val hasAnyInstalled: StateFlow<Boolean> = states
        .map { map -> map.values.any { it is ModelInstallState.Installed } }
        .stateIn(scope, SharingStarted.Eagerly, _states.value.values.any { it is ModelInstallState.Installed })

    fun addOnModelsChangedListener(listener: () -> Unit) {
        changeListeners.add(listener)
    }

    fun isInstalled(id: AiModelId): Boolean =
        _states.value[id] is ModelInstallState.Installed

    fun hasAnyInstalledNow(): Boolean =
        _states.value.values.any { it is ModelInstallState.Installed }

    fun installedFile(id: AiModelId): File? {
        val spec = AiModelCatalog.specFor(id)
        val file = File(modelsDir, spec.filename)
        return if (file.isFile && file.length() > 0L) file else null
    }

    /** Download and cryptographically verify an add-on model over T-Bone's media HTTP client. */
    fun downloadModel(id: AiModelId) {
        val spec = AiModelCatalog.specFor(id)
        synchronized(jobLock) {
            if (activeJobs[id]?.isActive == true) return
            val job = scope.launch {
                runDownload(spec)
            }
            activeJobs[id] = job
            job.invokeOnCompletion {
                synchronized(jobLock) {
                    if (activeJobs[id] === job) activeJobs.remove(id)
                }
            }
        }
    }

    fun cancelDownload(id: AiModelId) {
        synchronized(jobLock) {
            activeJobs.remove(id)?.cancel()
        }
        val spec = AiModelCatalog.specFor(id)
        runCatching { File(modelsDir, "${spec.filename}.part").delete() }
        val existing = installedFile(id)
        updateState(
            id,
            if (existing != null) {
                ModelInstallState.Installed(existing, existing.length(), spec.sha256)
            } else {
                ModelInstallState.NotInstalled
            },
        )
    }

    /**
     * Import a locally stored `.tflite` file via Android's Storage Access Framework.
     * The file's SHA-256 must match a pinned entry in [AiModelCatalog].
     */
    suspend fun importLocalModel(uri: Uri): Result<AiModelSpec> = withContext(Dispatchers.IO) {
        runCatching {
            modelsDir.mkdirs()
            val tempFile = File(modelsDir, "import_${System.currentTimeMillis()}.part")
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("could not open selected file")
                val sha256 = input.use { stream ->
                    copyWithSha256(
                        input = stream,
                        dest = tempFile,
                        maxBytes = MAX_IMPORT_BYTES,
                        onProgress = null,
                    )
                }
                val matched = AiModelCatalog.findBySha256(sha256)
                    ?: throw IllegalArgumentException(
                        "file checksum does not match a verified T-Bone AI model",
                    )

                // Cancel any active download for the matched slot before swapping.
                synchronized(jobLock) {
                    activeJobs.remove(matched.id)?.cancel()
                }
                notifyModelsChanged()

                val finalFile = File(modelsDir, matched.filename)
                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
                updateState(
                    matched.id,
                    ModelInstallState.Installed(finalFile, finalFile.length(), sha256),
                )
                notifyModelsChanged()
                matched
            } finally {
                if (tempFile.exists()) runCatching { tempFile.delete() }
            }
        }
    }

    /** Unload and delete an installed add-on model to free storage. */
    fun removeModel(id: AiModelId) {
        synchronized(jobLock) {
            activeJobs.remove(id)?.cancel()
        }
        val spec = AiModelCatalog.specFor(id)
        // Update state and unload interpreters first so mmap references are released before deletion.
        updateState(id, ModelInstallState.NotInstalled)
        notifyModelsChanged()
        runCatching {
            File(modelsDir, spec.filename).delete()
            File(modelsDir, "${spec.filename}.part").delete()
        }
    }

    /** Called by `DataWiper` during duress wipe. */
    fun wipeForDuress() {
        synchronized(jobLock) {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
        }
        _states.value = AiModelId.entries.associateWith { ModelInstallState.NotInstalled }
        notifyModelsChanged()
        runCatching { modelsDir.deleteRecursively() }
    }

    private suspend fun runDownload(spec: AiModelSpec) {
        if (spec.sha256.startsWith("PENDING_")) {
            updateState(spec.id, ModelInstallState.Error("model digest not configured in this build"))
            return
        }
        modelsDir.mkdirs()
        val partFile = File(modelsDir, "${spec.filename}.part")
        val finalFile = File(modelsDir, spec.filename)
        if (partFile.exists()) runCatching { partFile.delete() }

        updateState(spec.id, ModelInstallState.Downloading(0, 0L, spec.expectedSizeBytes))

        val request = Request.Builder()
            .url(spec.downloadUrl)
            .header("User-Agent", "T-Bone-Android")
            .get()
            .build()

        val call = ImageClientProvider.client.newCall(request)
        val currentJob = currentCoroutineContext()[Job]
        val cancelHandle = currentJob?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("download failed (HTTP ${resp.code})")
                }
                val body = resp.body ?: throw IOException("empty response body")
                val reportedLength = body.contentLength().takeIf { it > 0L } ?: spec.expectedSizeBytes
                if (reportedLength > spec.maxDownloadBytes) {
                    throw IOException("remote file exceeds size limit")
                }

                var lastPercent = -1
                val actualSha = body.byteStream().use { input ->
                    copyWithSha256(
                        input = input,
                        dest = partFile,
                        maxBytes = spec.maxDownloadBytes,
                    ) { written ->
                        if (currentJob?.isActive == false) throw CancellationException("cancelled")
                        val pct = ((written * 100L) / reportedLength.coerceAtLeast(1L)).toInt().coerceIn(0, 99)
                        if (pct != lastPercent) {
                            lastPercent = pct
                            updateState(
                                spec.id,
                                ModelInstallState.Downloading(pct, written, reportedLength),
                            )
                        }
                    }
                }

                if (!actualSha.equals(spec.sha256, ignoreCase = true)) {
                    runCatching { partFile.delete() }
                    throw IOException("checksum verification failed (file may be corrupted)")
                }

                // Unload existing interpreter for this slot (if reinstalling) before replacing file.
                notifyModelsChanged()
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                updateState(
                    spec.id,
                    ModelInstallState.Installed(finalFile, finalFile.length(), actualSha),
                )
                notifyModelsChanged()
            }
        } catch (ce: CancellationException) {
            call.cancel()
            runCatching { partFile.delete() }
            throw ce
        } catch (t: Throwable) {
            call.cancel()
            runCatching { partFile.delete() }
            Timber.w(t, "AI model download failed for %s", spec.filename)
            val msg = t.message?.takeIf { it.isNotBlank() } ?: "download failed"
            updateState(spec.id, ModelInstallState.Error(msg))
        } finally {
            cancelHandle?.dispose()
        }
    }

    private fun copyWithSha256(
        input: java.io.InputStream,
        dest: File,
        maxBytes: Long,
        onProgress: ((Long) -> Unit)?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        FileOutputStream(dest).use { out ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw IOException("file exceeds maximum allowed size (${maxBytes / (1024 * 1024)} MB)")
                }
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
                onProgress?.invoke(total)
            }
            out.fd.sync()
        }
        return digest.digest().joinToString(separator = "") { b -> "%02x".format(b) }
    }

    private fun scanDiskStates(): Map<AiModelId, ModelInstallState> =
        AiModelId.entries.associateWith { id ->
            val spec = AiModelCatalog.specFor(id)
            val file = File(modelsDir, spec.filename)
            if (file.isFile && file.length() > 0L) {
                ModelInstallState.Installed(file, file.length(), spec.sha256)
            } else {
                ModelInstallState.NotInstalled
            }
        }

    private fun updateState(id: AiModelId, state: ModelInstallState) {
        _states.update { current -> current + (id to state) }
    }

    private fun notifyModelsChanged() {
        changeListeners.forEach { listener ->
            runCatching { listener() }
        }
    }
}
