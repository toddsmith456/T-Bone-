package social.tbone.ai

import social.tbone.settings.NsfwMode

/**
 * Sensitivity presets mapping raw model probabilities to allow / veil / hide decisions.
 *
 * Raw scores are persisted in `media_scan_cache`, so changing presets re-evaluates
 * cached media immediately without re-running neural inference.
 */
enum class AiSensitivity(
    val key: String,
    val label: String,
    val explicitThreshold: Float,
    val suggestiveThreshold: Float,
) {
    /** Nudity & explicit pornography only (fewest false positives). */
    EXPLICIT_ONLY("explicit", "explicit only", explicitThreshold = 0.72f, suggestiveThreshold = 0.99f),

    /** Recommended default: explicit nudity plus strongly suggestive imagery. */
    BALANCED("balanced", "balanced", explicitThreshold = 0.55f, suggestiveThreshold = 0.78f),

    /** Strict / family mode: lower threshold for explicit and suggestive content. */
    STRICT("strict", "strict", explicitThreshold = 0.35f, suggestiveThreshold = 0.55f);

    companion object {
        fun fromKey(key: String?): AiSensitivity =
            entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

/**
 * How the engine uses installed add-on models.
 *
 * When only one model is installed, the engine runs whichever model is present.
 * When both models are installed, the user can pick automatic 2-stage cascade,
 * always high-accuracy, or always fast.
 */
enum class AiScanMode(val key: String, val label: String) {
    /** Stage 1 MobileNetV2 resolves obvious safe/explicit images; Stage 2 Marqo verifies gray-zone frames. */
    AUTO_CASCADE("cascade", "cascade (fast + accurate)"),

    /** Always run Marqo ViT-384 (maximum accuracy on photos, anime & AI art). */
    HIGH_ACCURACY_ONLY("accurate", "high-accuracy only"),

    /** Always run MobileNetV2 (lowest latency & battery). */
    FAST_ONLY("fast", "fast only");

    companion object {
        fun fromKey(key: String?): AiScanMode =
            entries.firstOrNull { it.key == key } ?: AUTO_CASCADE
    }
}

/** Media slot classification used by [NsfwCoilInterceptor]. */
enum class ScanSurface {
    FEED_MEDIA,
    AVATAR,
    BANNER,
}

/** Snapshot of all user settings governing offline AI NSFW scanning. */
data class AiFilterConfig(
    val action: NsfwMode = NsfwMode.OFF,
    val sensitivity: AiSensitivity = AiSensitivity.BALANCED,
    val scanMode: AiScanMode = AiScanMode.AUTO_CASCADE,
    val scanAvatars: Boolean = false,
    val scanBanners: Boolean = true,
    val exemptFollowedAuthors: Boolean = false,
    val parentalPinActive: Boolean = false,
    val requirePinToReveal: Boolean = true,
) {
    /** True when the user enabled AI scanning in COVER or HIDE mode. */
    val active: Boolean get() = action != NsfwMode.OFF

    /**
     * Effective followed-author exemption. Automatically disabled while a
     * Parental PIN is active so a child cannot follow an account to bypass filters.
     */
    val effectiveExemptFollows: Boolean get() = exemptFollowedAuthors && !parentalPinActive

    /** True when tapping to reveal a veiled image requires verifying the Parental PIN. */
    val effectiveRequirePinToReveal: Boolean get() = parentalPinActive && requirePinToReveal
}

/**
 * Raw neural-network output probabilities in `[0, 1]`.
 *
 * - [explicitScore]: `porn + hentai` (Stage 1) or `p(NSFW)` (Stage 2 Marqo / fused).
 * - [suggestiveScore]: `sexy` probability from Stage 1 (or scaled Stage 2 signal when Stage 2 ran alone).
 * - [stageReached]: `1` = Stage 1 only, `2` = Stage 2 Marqo (standalone or cascade verifier).
 */
data class RawNsfwScores(
    val explicitScore: Float,
    val suggestiveScore: Float,
    val stageReached: Int,
    val modelVersion: Int = AiModelCatalog.CACHE_MODEL_VERSION,
) {
    val maxScore: Float get() = maxOf(explicitScore, suggestiveScore)
}

enum class ScanDecision {
    ALLOW,
    COVER,
    HIDE,
}

data class ScanVerdict(
    val decision: ScanDecision,
    val scores: RawNsfwScores,
    val isExplicit: Boolean,
    val reason: String,
    val fromCache: Boolean = false,
)

/**
 * Thrown inside Coil's [coil.request.ErrorResult] when the offline AI scanner
 * intercepts an image that must not be displayed directly.
 *
 * Because the interceptor returns an `ErrorResult` BEFORE Coil delivers any
 * drawable to `SubcomposeAsyncImage`, the image pixels never reach the screen
 * (zero flash of unblurred content).
 */
class NsfwBlockedException(
    val mediaUrl: String,
    val verdict: ScanVerdict,
) : RuntimeException("Media blocked by offline AI filter (${verdict.decision}: ${verdict.reason})")

/** Pure, stateless decision logic mapping [RawNsfwScores] + [AiFilterConfig] -> [ScanVerdict]. */
object AiPolicyEngine {
    fun evaluate(
        scores: RawNsfwScores,
        config: AiFilterConfig,
        userOverrideAllow: Boolean = false,
        fromCache: Boolean = false,
    ): ScanVerdict {
        if (!config.active) {
            return ScanVerdict(ScanDecision.ALLOW, scores, isExplicit = false, reason = "ai filter off", fromCache = fromCache)
        }
        // Persistent allow override is honored only when Parental PIN does not require per-reveal verification.
        if (userOverrideAllow && !config.effectiveRequirePinToReveal) {
            return ScanVerdict(ScanDecision.ALLOW, scores, isExplicit = false, reason = "user revealed", fromCache = fromCache)
        }

        val explicitHit = scores.explicitScore >= config.sensitivity.explicitThreshold
        val suggestiveHit = scores.suggestiveScore >= config.sensitivity.suggestiveThreshold
        if (!explicitHit && !suggestiveHit) {
            return ScanVerdict(ScanDecision.ALLOW, scores, isExplicit = false, reason = "below threshold", fromCache = fromCache)
        }

        val decision = when (config.action) {
            NsfwMode.OFF -> ScanDecision.ALLOW
            NsfwMode.COVER -> ScanDecision.COVER
            NsfwMode.HIDE -> ScanDecision.HIDE
        }
        val reason = if (explicitHit) "explicit content detected" else "suggestive content detected"
        return ScanVerdict(
            decision = decision,
            scores = scores,
            isExplicit = explicitHit,
            reason = reason,
            fromCache = fromCache,
        )
    }

    /**
     * Cascade routing rule when BOTH Stage 1 (MobileNetV2) and Stage 2 (Marqo ViT-384)
     * are installed and `AiScanMode.AUTO_CASCADE` is selected.
     *
     * Fast Stage 1 resolves clear safe (`explicit < 0.12 && suggestive < 0.35`) and
     * clear explicit (`explicit > 0.85`) images immediately in ~15 ms; anything in the
     * ambiguous middle band escalates to Stage 2 Marqo for high-accuracy verification.
     */
    fun shouldEscalateToStage2(stage1: RawNsfwScores): Boolean =
        (stage1.explicitScore in 0.12f..0.85f) || (stage1.suggestiveScore >= 0.35f && stage1.explicitScore <= 0.85f)

    /**
     * Fuse Stage 1 (5-class MobileNetV2) and Stage 2 (2-class Marqo ViT-384) scores
     * into a single calibrated [RawNsfwScores].
     *
     * Marqo ViT-384 is dramatically more accurate on modern photos, anime/hentai,
     * and AI-generated content, so its `p(NSFW)` drives `explicitScore` while Stage 1's
     * `sexy` signal is damped when Marqo is confident the frame is SFW.
     */
    fun fuseStage1AndStage2(stage1: RawNsfwScores, marqoNsfwProb: Float): RawNsfwScores {
        val clampedMarqo = marqoNsfwProb.coerceIn(0f, 1f)
        // If Marqo is strongly confident the image is SFW (< 0.15), damp Stage 1's noisy "sexy" false positives
        // (e.g. swimwear, portraits, warm lighting).
        val dampedSuggestive = if (clampedMarqo < 0.15f) {
            (stage1.suggestiveScore * 0.45f).coerceIn(0f, 1f)
        } else {
            maxOf(stage1.suggestiveScore, clampedMarqo * 0.9f).coerceIn(0f, 1f)
        }
        return RawNsfwScores(
            explicitScore = clampedMarqo,
            suggestiveScore = dampedSuggestive,
            stageReached = 2,
            modelVersion = AiModelCatalog.CACHE_MODEL_VERSION,
        )
    }
}
