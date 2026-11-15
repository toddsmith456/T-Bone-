package social.tbone.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Offline LiteRT 1.4.2 NSFW classifier loading user-installed `.tflite` add-on
 * weights directly from `filesDir/ai_models/` via read-only `mmap()`.
 *
 * Supports all 3 add-on combinations seamlessly:
 * - Only `FAST_MNV2` installed -> Stage 1 MobileNetV2-140 INT8 (224x224, `[0, 1]`).
 * - Only `ACCURATE_MARQO` installed -> Stage 2 Marqo ViT-Tiny 384 FP16 (384x384, `[-1, 1]`).
 * - Both installed -> Automatic 2-Stage Cascade (`AUTO_CASCADE`), or user-forced
 *   `HIGH_ACCURACY_ONLY` / `FAST_ONLY`.
 */
@Singleton
class LiteRtNsfwEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: AiModelManager,
) : NsfwEngine {

    companion object {
        // Keep 1 worker slot per model to halve native XNNPACK RAM and stay well
        // within Android's memory budget alongside media caches and ExoPlayer.
        private const val POOL_SIZE = 1
        private const val STAGE1_DIM = 224
        private const val STAGE2_DIM = 384
        private const val STAGE1_CLASSES = 5
        private const val STAGE2_CLASSES = 2
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(POOL_SIZE)

    private val poolMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var pool: WorkerPool? = null

    @Volatile
    private var poolDirty: Boolean = true

    private var poolGeneration: Long = 0L

    override val modelVersion: Int = AiModelCatalog.CACHE_MODEL_VERSION

    init {
        modelManager.addOnModelsChangedListener {
            onModelsChanged()
        }
    }

    override fun hasAvailableModel(): Boolean = modelManager.hasAnyInstalledNow()

    override fun hasStage2Model(): Boolean = modelManager.isInstalled(AiModelId.ACCURATE_MARQO)

    override fun onModelsChanged() {
        invalidatePool()
    }

    override fun unloadAll() {
        invalidatePool()
    }

    private fun invalidatePool() {
        val old = synchronized(stateLock) {
            poolGeneration++
            poolDirty = true
            val existing = pool
            pool = null
            existing
        }
        // Drains only idle slots immediately; any active inference slot finishes
        // safely and closes itself via WorkerPool.releaseOrClose(slot).
        old?.closeIdleAndMarkClosed()
    }

    override suspend fun classify(bitmap: Bitmap, mode: AiScanMode): RawNsfwScores? =
        withContext(inferenceDispatcher) {
            val activePool = ensurePool() ?: return@withContext null
            val slot = activePool.acquireOrNull() ?: return@withContext null
            try {
                runOnSlot(slot, bitmap, mode)
            } finally {
                activePool.releaseOrClose(slot)
            }
        }

    private fun runOnSlot(slot: WorkerSlot, bitmap: Bitmap, mode: AiScanMode): RawNsfwScores? {
        val hasStage1 = slot.stage1 != null
        val hasStage2 = slot.stage2 != null
        if (!hasStage1 && !hasStage2) return null

        // Single-model installations run whichever add-on is present regardless of mode preference.
        if (hasStage2 && !hasStage1) {
            val marqoProb = runStage2(slot, bitmap)
            return RawNsfwScores(
                explicitScore = marqoProb,
                suggestiveScore = marqoProb,
                stageReached = 2,
                modelVersion = modelVersion,
            )
        }
        if (hasStage1 && !hasStage2) {
            return runStage1(slot, bitmap)
        }

        // Both models installed: honor AiScanMode + thermal/power-save guard.
        return when (mode) {
            AiScanMode.FAST_ONLY -> runStage1(slot, bitmap)
            AiScanMode.HIGH_ACCURACY_ONLY -> {
                val marqoProb = runStage2(slot, bitmap)
                RawNsfwScores(
                    explicitScore = marqoProb,
                    suggestiveScore = marqoProb,
                    stageReached = 2,
                    modelVersion = modelVersion,
                )
            }
            AiScanMode.AUTO_CASCADE -> {
                val stage1Scores = runStage1(slot, bitmap)
                if (!isThermallyConstrained() && AiPolicyEngine.shouldEscalateToStage2(stage1Scores)) {
                    val marqoProb = runStage2(slot, bitmap)
                    AiPolicyEngine.fuseStage1AndStage2(stage1Scores, marqoProb)
                } else {
                    stage1Scores
                }
            }
        }
    }

    private fun runStage1(slot: WorkerSlot, bitmap: Bitmap): RawNsfwScores {
        val interpreter = requireNotNull(slot.stage1)
        val inputBuf = requireNotNull(slot.stage1Buffer)
        val pixels = requireNotNull(slot.stage1Pixels)
        val output = requireNotNull(slot.stage1Output)

        fillNormalizedRgb(
            bitmap = bitmap,
            dim = STAGE1_DIM,
            pixels = pixels,
            buffer = inputBuf,
            zeroToOne = true,
        )
        interpreter.run(inputBuf, output)

        // Upstream GantMan v1.2.0 class order: [drawings, hentai, neutral, porn, sexy]
        val probs = output[0]
        val hentai = probs[1].coerceIn(0f, 1f)
        val porn = probs[3].coerceIn(0f, 1f)
        val sexy = probs[4].coerceIn(0f, 1f)
        return RawNsfwScores(
            explicitScore = (porn + hentai).coerceIn(0f, 1f),
            suggestiveScore = sexy,
            stageReached = 1,
            modelVersion = modelVersion,
        )
    }

    private fun runStage2(slot: WorkerSlot, bitmap: Bitmap): Float {
        val interpreter = requireNotNull(slot.stage2)
        val inputBuf = requireNotNull(slot.stage2Buffer)
        val pixels = requireNotNull(slot.stage2Pixels)
        val output = requireNotNull(slot.stage2Output)

        fillNormalizedRgb(
            bitmap = bitmap,
            dim = STAGE2_DIM,
            pixels = pixels,
            buffer = inputBuf,
            zeroToOne = false,
        )
        interpreter.run(inputBuf, output)

        // Upstream Marqo ViT-Tiny-384 label order: [NSFW, SFW] logits.
        val logits = output[0]
        return softmaxIndex0(logits[0], logits[1])
    }

    private suspend fun ensurePool(): WorkerPool? {
        synchronized(stateLock) {
            val current = pool
            if (current != null && !poolDirty) return current
        }

        return poolMutex.withLock {
            val buildGen: Long
            val stalePool: WorkerPool?
            synchronized(stateLock) {
                val again = pool
                if (again != null && !poolDirty) return@withLock again
                stalePool = pool
                pool = null
                buildGen = poolGeneration
            }
            stalePool?.closeIdleAndMarkClosed()

            val stage1File = modelManager.installedFile(AiModelId.FAST_MNV2)
            val stage2File = modelManager.installedFile(AiModelId.ACCURATE_MARQO)
            if (stage1File == null && stage2File == null) {
                synchronized(stateLock) {
                    if (poolGeneration == buildGen) poolDirty = false
                }
                return@withLock null
            }

            val threads = computeOptimalThreads()
            val slots = ArrayList<WorkerSlot>(POOL_SIZE)
            try {
                // mmap once per model file; share read-only MappedByteBuffer safely across slots.
                val stage1Mapped = stage1File?.let { mmapReadOnly(it) }
                val stage2Mapped = stage2File?.let { mmapReadOnly(it) }

                repeat(POOL_SIZE) {
                    val s1Interp = stage1Mapped?.let { createInterpreter(it, threads) }
                    val s2Interp = stage2Mapped?.let { createInterpreter(it, threads) }
                    slots += WorkerSlot(
                        stage1 = s1Interp,
                        stage1Buffer = if (s1Interp != null) allocateFloatBuffer(STAGE1_DIM) else null,
                        stage1Pixels = if (s1Interp != null) IntArray(STAGE1_DIM * STAGE1_DIM) else null,
                        stage1Output = if (s1Interp != null) Array(1) { FloatArray(STAGE1_CLASSES) } else null,
                        stage2 = s2Interp,
                        stage2Buffer = if (s2Interp != null) allocateFloatBuffer(STAGE2_DIM) else null,
                        stage2Pixels = if (s2Interp != null) IntArray(STAGE2_DIM * STAGE2_DIM) else null,
                        stage2Output = if (s2Interp != null) Array(1) { FloatArray(STAGE2_CLASSES) } else null,
                    )
                }

                val channel = Channel<WorkerSlot>(POOL_SIZE)
                slots.forEach { channel.trySend(it) }
                val created = WorkerPool(channel)
                val published = synchronized(stateLock) {
                    if (poolGeneration == buildGen) {
                        pool = created
                        poolDirty = false
                        true
                    } else {
                        // unloadAll() or onModelsChanged() fired while loading; discard safely.
                        false
                    }
                }
                if (published) {
                    created
                } else {
                    created.closeIdleAndMarkClosed()
                    null
                }
            } catch (t: Throwable) {
                slots.forEach { it.closeQuietly() }
                Timber.e(t, "Failed to initialize LiteRT NSFW interpreter pool")
                throw t
            }
        }
    }

    private fun createInterpreter(buffer: MappedByteBuffer, threads: Int): Interpreter {
        val options = Interpreter.Options().apply {
            setNumThreads(threads)
            setUseXNNPACK(true)
        }
        // Duplicate gives each Interpreter its own position/limit view over the shared read-only mapping.
        return Interpreter(buffer.duplicate(), options)
    }

    private fun mmapReadOnly(file: File): MappedByteBuffer =
        FileInputStream(file).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0L, file.length())
        }

    private fun allocateFloatBuffer(dim: Int): ByteBuffer =
        ByteBuffer.allocateDirect(dim * dim * 3 * 4).order(ByteOrder.nativeOrder())

    private fun fillNormalizedRgb(
        bitmap: Bitmap,
        dim: Int,
        pixels: IntArray,
        buffer: ByteBuffer,
        zeroToOne: Boolean,
    ) {
        val scaled = if (bitmap.width == dim && bitmap.height == dim) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, dim, dim, true)
        }
        try {
            scaled.getPixels(pixels, 0, dim, 0, 0, dim, dim)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }

        buffer.rewind()
        if (zeroToOne) {
            val inv255 = 1f / 255f
            for (px in pixels) {
                buffer.putFloat(((px ushr 16) and 0xFF) * inv255)
                buffer.putFloat(((px ushr 8) and 0xFF) * inv255)
                buffer.putFloat((px and 0xFF) * inv255)
            }
        } else {
            val inv1275 = 1f / 127.5f
            for (px in pixels) {
                buffer.putFloat((((px ushr 16) and 0xFF) * inv1275) - 1f)
                buffer.putFloat((((px ushr 8) and 0xFF) * inv1275) - 1f)
                buffer.putFloat(((px and 0xFF) * inv1275) - 1f)
            }
        }
        buffer.rewind()
    }

    private fun computeOptimalThreads(): Int {
        if (isThermallyConstrained()) return 1
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores / 2).coerceIn(2, 4)
    }

    private fun isThermallyConstrained(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (pm.isPowerSaveMode) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        } else {
            false
        }
    }.getOrDefault(false)

    private class WorkerSlot(
        val stage1: Interpreter?,
        val stage1Buffer: ByteBuffer?,
        val stage1Pixels: IntArray?,
        val stage1Output: Array<FloatArray>?,
        val stage2: Interpreter?,
        val stage2Buffer: ByteBuffer?,
        val stage2Pixels: IntArray?,
        val stage2Output: Array<FloatArray>?,
    ) {
        fun closeQuietly() {
            runCatching { stage1?.close() }
            runCatching { stage2?.close() }
        }
    }

    private class WorkerPool(
        private val channel: Channel<WorkerSlot>,
    ) {
        private val closeLock = Any()
        private var closed = false

        suspend fun acquireOrNull(): WorkerSlot? = channel.receiveCatching().getOrNull()

        fun releaseOrClose(slot: WorkerSlot) {
            val shouldClose = synchronized(closeLock) {
                if (closed) {
                    true
                } else {
                    !channel.trySend(slot).isSuccess
                }
            }
            if (shouldClose) slot.closeQuietly()
        }

        fun closeIdleAndMarkClosed() {
            val idle = synchronized(closeLock) {
                if (closed) return
                closed = true
                val drained = ArrayList<WorkerSlot>()
                while (true) {
                    val slot = channel.tryReceive().getOrNull() ?: break
                    drained += slot
                }
                channel.close()
                drained
            }
            // Close native interpreters outside the lock; any in-flight slot closes itself in releaseOrClose().
            idle.forEach { it.closeQuietly() }
        }
    }
}

/** Numerically stable 2-class softmax returning `P(class 0)` (Marqo index 0 = NSFW). */
internal fun softmaxIndex0(logit0: Float, logit1: Float): Float {
    val maxLogit = maxOf(logit0, logit1).toDouble()
    val e0 = exp(logit0.toDouble() - maxLogit)
    val e1 = exp(logit1.toDouble() - maxLogit)
    return (e0 / (e0 + e1)).toFloat().coerceIn(0f, 1f)
}
