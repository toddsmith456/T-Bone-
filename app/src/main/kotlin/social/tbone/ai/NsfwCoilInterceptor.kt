package social.tbone.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import coil.annotation.ExperimentalCoilApi
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Coil `ImageRequest.parameters` keys understood by [NsfwCoilInterceptor]. */
object AiCoilParams {
    /** Boolean: user explicitly revealed this media item (or opened an already-revealed image). */
    const val BYPASS = "tbone_ai_bypass"

    /** String matching [ScanSurface.name]: FEED_MEDIA (default), AVATAR, or BANNER. */
    const val SURFACE = "tbone_ai_surface"

    /** Optional 64-hex NIP-92 `imeta` `x` SHA-256 hash for mirror-independent caching. */
    const val IMETA_SHA256 = "tbone_ai_imeta_sha256"

    /** Optional hex pubkey of the author (for optional followed-author exemption). */
    const val AUTHOR_PUBKEY = "tbone_ai_author_pubkey"
}

/**
 * Zero-FOUC (no Flash of Unblurred Content) Coil [Interceptor] that scans images
 * on-device BEFORE Coil delivers any drawable to UI composables.
 *
 * Registered on BOTH `Coil.setImageLoader` builders inside `BonyApp`.
 */
@Singleton
class NsfwCoilInterceptor @Inject constructor(
    private val engine: NsfwEngine,
    private val cache: MediaScanCache,
    private val stateProvider: AiRuntimeStateProvider,
) : Interceptor {

    companion object {
        private const val MAX_DRAWABLE_RASTER_DIM = 384
    }

    // Coalesces identical concurrent URL/hash scans so feed duplicates only run LiteRT once.
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<RawNsfwScores?>>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed(request)

        // Fast pass-through when AI filter is OFF or no add-on model is installed.
        val config = stateProvider.currentConfig()
        if (!config.active || !engine.hasAvailableModel()) {
            return chain.proceed(request)
        }

        // Surface scope check (avatars & banners are independently configurable).
        val surface = parseSurface(request)
        if (surface == ScanSurface.AVATAR && !config.scanAvatars) {
            return chain.proceed(request)
        }
        if (surface == ScanSurface.BANNER && !config.scanBanners) {
            return chain.proceed(request)
        }

        // Explicit user reveal bypass (for COVER mode after tap or PIN verification).
        val isBypassed = request.parameters.value<Boolean>(AiCoilParams.BYPASS) == true
        if (isBypassed) {
            return chain.proceed(request)
        }

        // Optional followed-author exemption (automatically disabled while Parental PIN is active).
        val authorPubkey = request.parameters.value<String>(AiCoilParams.AUTHOR_PUBKEY)
        if (config.effectiveExemptFollows && !authorPubkey.isNullOrBlank()) {
            if (authorPubkey in stateProvider.followedPubkeys()) {
                return chain.proceed(request)
            }
        }

        val imetaSha = request.parameters.value<String>(AiCoilParams.IMETA_SHA256)
        val requireStage2Cache = config.scanMode == AiScanMode.HIGH_ACCURACY_ONLY && engine.hasStage2Model()

        // 1. Check local HMAC scan cache BEFORE downloading/decoding when already known.
        val cached = cache.get(
            url = url,
            imetaSha256 = imetaSha,
            requiredModelVersion = engine.modelVersion,
            requireStage2 = requireStage2Cache,
        )
        if (cached != null) {
            val verdict = AiPolicyEngine.evaluate(
                scores = cached.scores,
                config = config,
                userOverrideAllow = cached.userOverrideAllow,
                fromCache = true,
            )
            return when (verdict.decision) {
                ScanDecision.ALLOW -> chain.proceed(request)
                ScanDecision.COVER, ScanDecision.HIDE -> {
                    evictIfNeeded(request, verdict, config)
                    ErrorResult(
                        drawable = null,
                        request = request,
                        throwable = NsfwBlockedException(url, verdict),
                    )
                }
            }
        }

        // 2. Unscanned media: force software bitmap so API 26+ HARDWARE bitmaps never crash getPixels().
        val scanRequest = request.newBuilder()
            .allowHardware(false)
            .build()

        val result = chain.proceed(scanRequest)
        if (result !is SuccessResult) {
            return result
        }

        // 3. Classify the decoded image (with perceptual dHash dedup + in-flight coalescing).
        val canonicalKey = cache.canonicalKey(url, imetaSha)
        val scores = try {
            classifyWithCoalescing(
                canonicalKey = canonicalKey,
                url = url,
                imetaSha = imetaSha,
                drawable = result.drawable,
                config = config,
                requireStage2Cache = requireStage2Cache,
            )
        } catch (t: Throwable) {
            Timber.w(t, "Offline AI scan failed for media")
            if (config.parentalPinActive) {
                // Fail-closed under Parental PIN protection so errors never bypass the lock.
                val failScores = RawNsfwScores(1f, 1f, stageReached = 0, modelVersion = engine.modelVersion)
                val failVerdict = ScanVerdict(
                    decision = if (config.action == social.tbone.settings.NsfwMode.HIDE) ScanDecision.HIDE else ScanDecision.COVER,
                    scores = failScores,
                    isExplicit = true,
                    reason = "scan error (parental fail-closed)",
                )
                evictIfNeeded(request, failVerdict, config)
                return ErrorResult(
                    drawable = null,
                    request = request,
                    throwable = NsfwBlockedException(url, failVerdict),
                )
            }
            return result
        }

        if (scores == null) {
            // Model was uninstalled concurrently mid-load.
            return result
        }

        val verdict = AiPolicyEngine.evaluate(
            scores = scores,
            config = config,
            userOverrideAllow = false,
            fromCache = false,
        )
        return when (verdict.decision) {
            ScanDecision.ALLOW -> result
            ScanDecision.COVER, ScanDecision.HIDE -> {
                evictIfNeeded(request, verdict, config, result.memoryCacheKey)
                ErrorResult(
                    drawable = null,
                    request = request,
                    throwable = NsfwBlockedException(url, verdict),
                )
            }
        }
    }

    private suspend fun classifyWithCoalescing(
        canonicalKey: String,
        url: String,
        imetaSha: String?,
        drawable: Drawable,
        config: AiFilterConfig,
        requireStage2Cache: Boolean,
    ): RawNsfwScores? {
        val existing = inFlight[canonicalKey]
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<RawNsfwScores?>()
        val raced = inFlight.putIfAbsent(canonicalKey, deferred)
        if (raced != null) {
            return raced.await()
        }

        return try {
            val softwareBitmap = extractSoftwareBitmap(drawable)
                ?: throw IllegalStateException("could not extract software bitmap from drawable")
            val ownedCopy = softwareBitmap !== (drawable as? BitmapDrawable)?.bitmap
            try {
                // Fast RAM-only perceptual dHash check catches reposts/re-encodes on other Blossom URLs.
                val dHash = PerceptualHash.computeDHash(softwareBitmap)
                val perceptualHit = cache.lookupByPerceptualHash(
                    dHash = dHash,
                    requiredModelVersion = engine.modelVersion,
                    requireStage2 = requireStage2Cache,
                )
                val scores = perceptualHit ?: engine.classify(softwareBitmap, config.scanMode)
                if (scores != null) {
                    cache.put(
                        url = url,
                        imetaSha256 = imetaSha,
                        scores = scores,
                        dHash = dHash,
                    )
                }
                deferred.complete(scores)
                scores
            } finally {
                if (ownedCopy && !softwareBitmap.isRecycled) {
                    softwareBitmap.recycle()
                }
            }
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(canonicalKey, deferred)
        }
    }

    private fun extractSoftwareBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap
            if (bmp != null && !bmp.isRecycled) {
                if (bmp.config != Bitmap.Config.HARDWARE) {
                    return bmp
                }
                return bmp.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
        // Animated GIF / MovieDrawable / VectorDrawable: rasterize first frame onto a bounded software bitmap.
        val rawW = drawable.intrinsicWidth.takeIf { it > 0 } ?: MAX_DRAWABLE_RASTER_DIM
        val rawH = drawable.intrinsicHeight.takeIf { it > 0 } ?: MAX_DRAWABLE_RASTER_DIM
        val scale = minOf(1f, MAX_DRAWABLE_RASTER_DIM.toFloat() / maxOf(rawW, rawH).toFloat())
        val w = (rawW * scale).toInt().coerceAtLeast(1)
        val h = (rawH * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val oldBounds = drawable.copyBounds()
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        drawable.bounds = oldBounds
        return out
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun evictIfNeeded(
        request: ImageRequest,
        verdict: ScanVerdict,
        config: AiFilterConfig,
        resultMemoryKey: coil.memory.MemoryCache.Key? = null,
    ) {
        val loader = runCatching { coil.Coil.imageLoader(request.context) }.getOrNull() ?: return
        request.memoryCacheKey?.let { memKey ->
            runCatching { loader.memoryCache?.remove(memKey) }
        }
        resultMemoryKey?.let { memKey ->
            runCatching { loader.memoryCache?.remove(memKey) }
        }
        // In HIDE mode or Parental PIN mode, avoid keeping blocked images in Coil's disk cache.
        if (verdict.decision == ScanDecision.HIDE || config.parentalPinActive) {
            val diskKey = request.diskCacheKey ?: (request.data as? String)
            if (!diskKey.isNullOrBlank()) {
                runCatching { loader.diskCache?.remove(diskKey) }
            }
        }
    }

    private fun parseSurface(request: ImageRequest): ScanSurface {
        val raw = request.parameters.value<String>(AiCoilParams.SURFACE) ?: return ScanSurface.FEED_MEDIA
        return runCatching { ScanSurface.valueOf(raw) }.getOrDefault(ScanSurface.FEED_MEDIA)
    }
}
