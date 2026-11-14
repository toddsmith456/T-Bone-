package social.tbone.ai

/**
 * Identifiers for installable offline NSFW model add-ons.
 *
 * Neither model is bundled in the APK. Users download them on demand (routed
 * through T-Bone's Tor-aware ImageClientProvider and verified against pinned
 * SHA-256 digests) or import a matching `.tflite` file from local storage.
 */
enum class AiModelId(val storageKey: String) {
    /** Fast Stage 1 model: GantMan/nsfw_model v1.2.0 MobileNetV2-140 dynamic-range INT8. */
    FAST_MNV2("fast_mnv2_v1"),

    /** High-accuracy Stage 2 model: Marqo/nsfw-image-detection-384 ViT-Tiny 384 FP16. */
    ACCURATE_MARQO("accurate_marqo384_v1"),
}

data class AiModelSpec(
    val id: AiModelId,
    val filename: String,
    val displayName: String,
    val badge: String,
    val description: String,
    val license: String,
    val upstreamUrl: String,
    val downloadUrl: String,
    val sha256: String,
    val expectedSizeBytes: Long,
    val maxDownloadBytes: Long,
    val inputSize: Int,
)

/**
 * Pinned catalog of verified offline AI add-on models published under the
 * `ai-models-v1` GitHub prerelease tag of `toddsmith456/T-Bone-`.
 */
object AiModelCatalog {
    const val RELEASE_TAG = "ai-models-v1"
    const val BASE_DOWNLOAD_URL =
        "https://github.com/toddsmith456/T-Bone-/releases/download/$RELEASE_TAG"

    /**
     * Schema/model version embedded in Room `media_scan_cache` rows.
     * Increment whenever model weights or preprocessing change so stale scores
     * are pruned automatically.
     */
    const val CACHE_MODEL_VERSION = 1

    val ACCURATE_MARQO = AiModelSpec(
        id = AiModelId.ACCURATE_MARQO,
        filename = "nsfw_stage2_marqo384_fp16.tflite",
        displayName = "high-accuracy scanner (marqo vit-384)",
        badge = "recommended · photos, anime & ai art",
        description = "vision transformer trained on 220k modern photos, illustrations & ai images. runs standalone or as stage 2 verifier.",
        license = "Apache-2.0",
        upstreamUrl = "https://huggingface.co/Marqo/nsfw-image-detection-384",
        downloadUrl = "$BASE_DOWNLOAD_URL/nsfw_stage2_marqo384_fp16.tflite",
        sha256 = "fe429105d496f34e5df4ccddcb410fb93a53fa3426a3dc0dbe2175d941f873c7",
        expectedSizeBytes = 11_263_080L,
        maxDownloadBytes = 24_000_000L,
        inputSize = 384,
    )

    val FAST_MNV2 = AiModelSpec(
        id = AiModelId.FAST_MNV2,
        filename = "nsfw_stage1_mnv2_fp16.tflite",
        displayName = "fast scanner (mobilenetv2)",
        badge = "~15 ms fast gate · lightweight",
        description = "ultra-fast fp16 scanner for smooth scrolling. install alone for speed or pair with marqo for automatic 2-stage cascade.",
        license = "MIT",
        upstreamUrl = "https://github.com/GantMan/nsfw_model/releases/tag/1.2.0",
        downloadUrl = "$BASE_DOWNLOAD_URL/nsfw_stage1_mnv2_fp16.tflite",
        sha256 = "38b2af7b44514bc8a126ad5dd749a8788c87cf84968ae8ba63d0eb4ada4f33c9",
        expectedSizeBytes = 8_652_084L,
        maxDownloadBytes = 16_000_000L,
        inputSize = 224,
    )

    /** Display order in Settings: High-Accuracy first (recommended), then Fast Gate. */
    val all: List<AiModelSpec> = listOf(ACCURATE_MARQO, FAST_MNV2)

    fun specFor(id: AiModelId): AiModelSpec = when (id) {
        AiModelId.FAST_MNV2 -> FAST_MNV2
        AiModelId.ACCURATE_MARQO -> ACCURATE_MARQO
    }

    /** Match a downloaded or locally imported `.tflite` by its exact SHA-256 hex digest. */
    fun findBySha256(sha256Hex: String): AiModelSpec? =
        all.firstOrNull { it.sha256.equals(sha256Hex.trim(), ignoreCase = true) }
}
