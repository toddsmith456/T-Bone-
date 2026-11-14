package social.tbone.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.db.MediaScanCacheDao
import social.tbone.db.MediaScanCacheEntity
import social.tbone.settings.NsfwMode

class OfflineAiNsfwTest {

    @Test
    fun catalogContainsRealVerifiedSha256DigestsForBothModels() {
        val hex64 = Regex("^[0-9a-f]{64}$")
        assertEquals(2, AiModelCatalog.all.size)
        AiModelCatalog.all.forEach { spec ->
            assertTrue("Expected real 64-hex sha256 for ${spec.filename}", hex64.matches(spec.sha256))
            assertTrue("Expected positive byte size for ${spec.filename}", spec.expectedSizeBytes > 1_000_000L)
            assertEquals(spec, AiModelCatalog.findBySha256(spec.sha256))
            assertEquals(spec, AiModelCatalog.findBySha256(spec.sha256.uppercase()))
        }
        assertNull(AiModelCatalog.findBySha256("0".repeat(64)))
    }

    @Test
    fun policyEngineRespectsModeSensitivityAndParentalControls() {
        val explicitScores = RawNsfwScores(explicitScore = 0.80f, suggestiveScore = 0.20f, stageReached = 2)
        val suggestiveScores = RawNsfwScores(explicitScore = 0.10f, suggestiveScore = 0.82f, stageReached = 1)
        val safeScores = RawNsfwScores(explicitScore = 0.05f, suggestiveScore = 0.12f, stageReached = 1)

        // OFF mode always allows
        val offCfg = AiFilterConfig(action = NsfwMode.OFF)
        assertEquals(ScanDecision.ALLOW, AiPolicyEngine.evaluate(explicitScores, offCfg).decision)

        // BALANCED + COVER blocks explicit and strongly suggestive, allows safe
        val balancedCover = AiFilterConfig(action = NsfwMode.COVER, sensitivity = AiSensitivity.BALANCED)
        assertEquals(ScanDecision.COVER, AiPolicyEngine.evaluate(explicitScores, balancedCover).decision)
        assertEquals(ScanDecision.COVER, AiPolicyEngine.evaluate(suggestiveScores, balancedCover).decision)
        assertEquals(ScanDecision.ALLOW, AiPolicyEngine.evaluate(safeScores, balancedCover).decision)

        // EXPLICIT_ONLY allows suggestive-only frames
        val explicitOnly = balancedCover.copy(sensitivity = AiSensitivity.EXPLICIT_ONLY)
        assertEquals(ScanDecision.ALLOW, AiPolicyEngine.evaluate(suggestiveScores, explicitOnly).decision)
        assertEquals(ScanDecision.COVER, AiPolicyEngine.evaluate(explicitScores, explicitOnly).decision)

        // HIDE mode maps flagged frames to HIDE
        val hideCfg = balancedCover.copy(action = NsfwMode.HIDE)
        assertEquals(ScanDecision.HIDE, AiPolicyEngine.evaluate(explicitScores, hideCfg).decision)

        // Parental PIN disables followed-author exemption and persistent allow override
        val parentalCfg = AiFilterConfig(
            action = NsfwMode.COVER,
            exemptFollowedAuthors = true,
            parentalPinActive = true,
            requirePinToReveal = true,
        )
        assertFalse(parentalCfg.effectiveExemptFollows)
        assertTrue(parentalCfg.effectiveRequirePinToReveal)
        assertEquals(
            ScanDecision.COVER,
            AiPolicyEngine.evaluate(explicitScores, parentalCfg, userOverrideAllow = true).decision,
        )
    }

    @Test
    fun cascadeRoutingAndStage2FusionWorkAsDesigned() {
        val clearSafe = RawNsfwScores(explicitScore = 0.03f, suggestiveScore = 0.10f, stageReached = 1)
        val clearExplicit = RawNsfwScores(explicitScore = 0.94f, suggestiveScore = 0.04f, stageReached = 1)
        val borderlineExplicit = RawNsfwScores(explicitScore = 0.42f, suggestiveScore = 0.15f, stageReached = 1)
        val borderlineSuggestive = RawNsfwScores(explicitScore = 0.08f, suggestiveScore = 0.65f, stageReached = 1)

        assertFalse(AiPolicyEngine.shouldEscalateToStage2(clearSafe))
        assertFalse(AiPolicyEngine.shouldEscalateToStage2(clearExplicit))
        assertTrue(AiPolicyEngine.shouldEscalateToStage2(borderlineExplicit))
        assertTrue(AiPolicyEngine.shouldEscalateToStage2(borderlineSuggestive))

        // When Marqo strongly clears a noisy Stage 1 "sexy" false positive, suggestive score is damped
        val cleared = AiPolicyEngine.fuseStage1AndStage2(borderlineSuggestive, marqoNsfwProb = 0.04f)
        assertEquals(2, cleared.stageReached)
        assertEquals(0.04f, cleared.explicitScore, 1e-4f)
        assertTrue(cleared.suggestiveScore < 0.35f)

        // When Marqo confirms NSFW, explicitScore reflects Marqo's high confidence
        val confirmed = AiPolicyEngine.fuseStage1AndStage2(borderlineExplicit, marqoNsfwProb = 0.93f)
        assertEquals(2, confirmed.stageReached)
        assertEquals(0.93f, confirmed.explicitScore, 1e-4f)
    }

    @Test
    fun softmaxIndex0IsNumericallyStable() {
        assertEquals(0.5f, softmaxIndex0(0f, 0f), 1e-5f)
        assertEquals(1.0f, softmaxIndex0(1000f, -1000f), 1e-5f)
        assertEquals(0.0f, softmaxIndex0(-1000f, 1000f), 1e-5f)
        assertTrue(softmaxIndex0(2f, 0f) > 0.85f)
    }

    @Test
    fun perceptualDHashDetectsIdenticalAndInvertedGradients() {
        val leftBright = IntArray(PerceptualHash.WIDTH * PerceptualHash.HEIGHT) { idx ->
            val x = idx % PerceptualHash.WIDTH
            val v = (255 - x * 28).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val rightBright = IntArray(PerceptualHash.WIDTH * PerceptualHash.HEIGHT) { idx ->
            val x = idx % PerceptualHash.WIDTH
            val v = (x * 28).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val h1 = PerceptualHash.computeDHashFromArgb(leftBright)
        val h2 = PerceptualHash.computeDHashFromArgb(leftBright.clone())
        val hInv = PerceptualHash.computeDHashFromArgb(rightBright)

        assertEquals(0, PerceptualHash.hammingDistance(h1, h2))
        assertEquals(64, PerceptualHash.hammingDistance(h1, hInv))
    }

    @Test
    fun mediaScanCacheStoresHmacKeysPrefersImetaHashAndSupportsPerceptualLookup() = runTest {
        val fakeDao = InMemoryScanCacheDao()
        val hasher = ScanKeyHasher()
        val cache = MediaScanCache(fakeDao, hasher)

        val sha = "a".repeat(64)
        val urlA = "https://mirror-a.example/img.jpg"
        val urlB = "https://mirror-b.example/img.jpg"
        val scoresStage1 = RawNsfwScores(explicitScore = 0.88f, suggestiveScore = 0.05f, stageReached = 1)

        cache.put(url = urlA, imetaSha256 = sha, scores = scoresStage1, dHash = 0x12345678L)

        // Plaintext URLs and raw sha256 must never appear as SQLite primary keys
        assertEquals(1, fakeDao.rows.size)
        val storedKey = fakeDao.rows.keys.single()
        assertFalse(storedKey.contains("mirror-a"))
        assertFalse(storedKey.contains(sha))
        assertEquals(64, storedKey.length)

        // Second mirror with the same NIP-92 imeta SHA-256 hits the same cache entry
        val hit = cache.get(url = urlB, imetaSha256 = sha, requiredModelVersion = AiModelCatalog.CACHE_MODEL_VERSION)
        assertNotNull(hit)
        assertEquals(0.88f, hit!!.scores.explicitScore, 1e-4f)

        // Requiring Stage 2 misses a Stage-1-only cached row
        val stage2Miss = cache.get(
            url = urlB,
            imetaSha256 = sha,
            requiredModelVersion = AiModelCatalog.CACHE_MODEL_VERSION,
            requireStage2 = true,
        )
        assertNull(stage2Miss)

        // Perceptual dHash within Hamming <= 4 hits
        val nearHash = 0x12345678L xor 0b101L // Hamming distance 2
        assertNotNull(cache.lookupByPerceptualHash(nearHash, AiModelCatalog.CACHE_MODEL_VERSION))

        // Clearing memory removes perceptual hits and rotates ephemeral key
        val oldKey = cache.keyHmac(urlA, sha)
        cache.clearAll()
        assertEquals(0, fakeDao.rows.size)
        assertNull(cache.lookupByPerceptualHash(nearHash, AiModelCatalog.CACHE_MODEL_VERSION))
        val newKey = cache.keyHmac(urlA, sha)
        assertNotEquals(oldKey, newKey)
    }

    private class InMemoryScanCacheDao : MediaScanCacheDao {
        val rows = LinkedHashMap<String, MediaScanCacheEntity>()

        override suspend fun get(keyHmac: String): MediaScanCacheEntity? = rows[keyHmac]

        override suspend fun upsert(entry: MediaScanCacheEntity) {
            rows[entry.keyHmac] = entry
        }

        override suspend fun setUserOverride(keyHmac: String, allow: Boolean) {
            rows[keyHmac]?.let { rows[keyHmac] = it.copy(userOverrideAllow = allow) }
        }

        override suspend fun pruneExpiredOrStale(cutoffMs: Long, currentModelVersion: Int) {
            rows.entries.removeIf { (_, v) -> v.scannedAt < cutoffMs || v.modelVersion != currentModelVersion }
        }

        override suspend fun trimToLimit(maxRows: Int) {
            while (rows.size > maxRows) {
                val oldest = rows.entries.minByOrNull { it.value.scannedAt }?.key ?: break
                rows.remove(oldest)
            }
        }

        override suspend fun clearAll() {
            rows.clear()
        }

        override suspend fun count(): Int = rows.size
    }
}
