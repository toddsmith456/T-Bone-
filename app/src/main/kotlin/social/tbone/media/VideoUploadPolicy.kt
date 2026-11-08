package social.tbone.media

/**
 * The size/quality decisions taken before a video is uploaded.
 *
 * This lives on its own, free of Android types, so upload behaviour is covered
 * by unit tests instead of only by trying it on a phone.
 *
 * Rules:
 *  - [MAX_BYTES] is a safety ceiling for absurd files, not a product limit.
 *    Blossom servers enforce their own limit and report it as a 413.
 *  - When compression is enabled every clip is transcoded, so uploads are
 *    standardized H.264/MP4. A transcode is only used when it is genuinely
 *    smaller than the original, never discarded for exceeding an arbitrary
 *    client cap.
 *  - [shouldDownscale] limits the transcode scaling to downscaling: a clip at
 *    or below [TARGET_HEIGHT] is re-encoded at its original resolution rather
 *    than being stretched taller.
 */
internal object VideoUploadPolicy {

    /** Safety ceiling for a picked video: 500 MB. */
    const val MAX_BYTES: Long = 500L * 1024L * 1024L

    /** Downscale target for tall sources (1080p/4K phone video). */
    const val TARGET_HEIGHT: Int = 1280

    /** True when the source is taller than the upload profile and should be downscaled (unknown height = keep size). */
    fun shouldDownscale(displayHeight: Int): Boolean = displayHeight > TARGET_HEIGHT

    /** True when a finished transcode should be uploaded instead of the original bytes. */
    fun preferTranscoded(transcodedBytes: Long, originalBytes: Long): Boolean =
        transcodedBytes > 0L && (originalBytes <= 0L || transcodedBytes < originalBytes)

    /** True when a picked file is small enough to upload at all. */
    fun isWithinCeiling(bytes: Long): Boolean = bytes <= MAX_BYTES
}
