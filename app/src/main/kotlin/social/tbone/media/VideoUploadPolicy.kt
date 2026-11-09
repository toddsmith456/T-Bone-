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
 *    standardized H.264/MP4. A successful transcode is always used: it is
 *    the compressed, metadata-stripped artifact (the fresh muxer output
 *    carries none of the source's location/device tags), so it must never
 *    be discarded for not being smaller than the original.
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

    /** True when a finished transcode should be uploaded instead of the original bytes.
     *  When compression is on the transcode is the upload — it is the
     *  metadata-stripped artifact — so it is kept even when an encoder quirk
     *  makes it slightly larger than the source. Only sanity failures are
     *  rejected: an empty result, or output absurdly larger than the source
     *  (>40x), which indicates a broken encode. */
    fun preferTranscoded(transcodedBytes: Long, originalBytes: Long): Boolean =
        transcodedBytes > 0L &&
            (originalBytes <= 0L || transcodedBytes <= originalBytes * 40L)

    /** True when a picked file is small enough to upload at all. */
    fun isWithinCeiling(bytes: Long): Boolean = bytes <= MAX_BYTES
}
