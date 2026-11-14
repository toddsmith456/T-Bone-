package social.tbone.ai

import android.graphics.Bitmap

/**
 * Abstraction for the offline on-device NSFW classifier so [NsfwCoilInterceptor]
 * can be unit-tested on the plain JVM with a fake engine.
 */
interface NsfwEngine {
    val modelVersion: Int

    /** True if at least one verified add-on model is installed on disk. */
    fun hasAvailableModel(): Boolean

    /** True when the installed high-accuracy Stage 2 model is available. */
    fun hasStage2Model(): Boolean

    /**
     * Classify a software [Bitmap] on-device.
     * Returns `null` when no add-on model is installed.
     */
    suspend fun classify(bitmap: Bitmap, mode: AiScanMode): RawNsfwScores?

    /** Reload or unload interpreters after an add-on model is installed or removed. */
    fun onModelsChanged()

    /** Release native interpreter memory under OS memory pressure or duress wipe. */
    fun unloadAll()
}
