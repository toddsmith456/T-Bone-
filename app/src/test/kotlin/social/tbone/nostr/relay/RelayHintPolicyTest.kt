package social.tbone.nostr.relay

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Nip19

class RelayHintPolicyTest {

    @Test
    fun `sanitizeHintUrl permits valid public wss relays, normalizes case, and redirects legacy bridges`() {
        assertEquals(
            "wss://relay.ditto.pub",
            RelayHintPolicy.sanitizeHintUrl("  WSS://Relay.Ditto.Pub/  ", torEnabled = false),
        )
        assertEquals(
            "wss://relay.primal.net",
            RelayHintPolicy.sanitizeHintUrl("wss://relay.primal.net/", torEnabled = false),
        )
        // Legacy Mostr / Ditto bridge URLs (301 redirect / HTML 200) rewrite cleanly to live wss://relay.ditto.pub.
        assertEquals(
            "wss://relay.ditto.pub",
            RelayHintPolicy.sanitizeHintUrl("  wss://relay.mostr.pub/  ", torEnabled = false),
        )
        assertEquals(
            "wss://relay.ditto.pub",
            RelayHintPolicy.sanitizeHintUrl("wss://ditto.pub/relay/", torEnabled = false),
        )
    }

    @Test
    fun `RelayPool normalize lowercases scheme and host while preserving validation`() {
        assertEquals("wss://relay.ditto.pub", RelayPool.normalize("  WSS://Relay.Ditto.Pub/  "))
        assertEquals("wss://example.com:8443/Relay/Path", RelayPool.normalize("WSS://Example.COM:8443/Relay/Path/"))
        assertEquals("ws://[::1]:4869", RelayPool.normalize("WS://[::1]:4869/"))
        // Embedded credentials must stay visible so isValidRelayUrl rejects them.
        assertFalse(RelayPool.isValidRelayUrl(RelayPool.normalize("wss://user:pass@relay.example.com/")))
    }

    @Test
    fun `sanitizeHintUrl blocks plaintext ws, http, credentials, and SSRF private hosts`() {
        val blocked = listOf(
            "ws://relay.example.com",
            "ws://localhost:4869",
            "ws://127.0.0.1:8080",
            "wss://localhost/relay",
            "wss://127.0.0.1/relay",
            "wss://[::1]/relay",
            "wss://10.0.0.1/relay",
            "wss://192.168.1.5/relay",
            "wss://172.16.0.1/relay",
            "wss://169.254.169.254/latest/meta-data",
            "wss://100.64.0.1/relay",
            "wss://[fc00::1]/relay",
            "wss://[fd12::1]/relay",
            "wss://[fe80::1]/relay",
            "wss://intranet/relay",
            "wss://user:pass@relay.example.com",
            "https://relay.example.com",
            "javascript:alert(1)",
            "wss://purplepag.es",
            "wss://purplepag.es/",
        )
        for (url in blocked) {
            assertNull("Expected blocked hint URL: $url", RelayHintPolicy.sanitizeHintUrl(url, torEnabled = false))
            assertNull("Expected blocked hint URL (Tor): $url", RelayHintPolicy.sanitizeHintUrl(url, torEnabled = true))
        }
    }

    @Test
    fun `sanitizeHintUrl allows onion relays only when Tor is enabled`() {
        val onion = "wss://abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion"
        assertNull(RelayHintPolicy.sanitizeHintUrl(onion, torEnabled = false))
        assertEquals(onion, RelayHintPolicy.sanitizeHintUrl("$onion/", torEnabled = true))
    }

    @Test
    fun `metadata-only relays are excluded from note-capable relays`() {
        assertTrue(RelayHintPolicy.isMetadataOnlyRelay("wss://purplepag.es"))
        assertTrue(RelayHintPolicy.isMetadataOnlyRelay("WSS://PURPLEPAG.ES/"))
        assertFalse(RelayHintPolicy.isNoteCapableRelay("wss://purplepag.es/"))
        assertTrue(RelayHintPolicy.isNoteCapableRelay("wss://relay.damus.io"))
        assertTrue(RelayHintPolicy.isNoteCapableRelay("wss://relay.mostr.pub"))
    }

    @Test
    fun `extractReferencedEventHints collects q tags, mention e tags, kind-16 reposts, and nevent TLVs`() {
        val qId = "aa".repeat(32)
        val inlineId = "bb".repeat(32)
        val repostTarget = "cc".repeat(32)
        val nevent = Nip19.hexToNevent(
            idHex = inlineId,
            relayHints = listOf("wss://relay.mostr.pub"),
            kind = EventKind.TEXT_NOTE,
        )
        val note = Event(
            id = "01".repeat(32),
            pubkey = "02".repeat(32),
            createdAt = 1_700_000_000L,
            kind = EventKind.TEXT_NOTE,
            tags = listOf(
                buildJsonArray { add("q"); add(qId); add("wss://ditto.pub/relay") },
            ),
            content = "look at this nostr:$nevent",
            sig = "00".repeat(64),
        )
        val hints = RelayHintPolicy.extractReferencedEventHints(note)
        assertEquals(listOf("wss://ditto.pub/relay"), hints[qId])
        assertEquals(listOf("wss://relay.mostr.pub"), hints[inlineId])

        val genericRepost = Event(
            id = "03".repeat(32),
            pubkey = "02".repeat(32),
            createdAt = 1_700_000_001L,
            kind = EventKind.GENERIC_REPOST,
            tags = listOf(
                buildJsonArray { add("e"); add(repostTarget); add("wss://relay.momostr.pink") },
            ),
            content = "",
            sig = "00".repeat(64),
        )
        val repostHints = RelayHintPolicy.extractReferencedEventHints(genericRepost)
        assertEquals(listOf("wss://relay.momostr.pink"), repostHints[repostTarget])
    }

    @Test
    fun `RelayHintCache merges hints per id and deduplicates`() {
        RelayHintCache.clear()
        val id = "dd".repeat(32)
        RelayHintCache.put(id, listOf("wss://relay.mostr.pub"))
        RelayHintCache.put(id, listOf("wss://relay.mostr.pub", "wss://ditto.pub/relay"))
        assertEquals(listOf("wss://relay.mostr.pub", "wss://ditto.pub/relay"), RelayHintCache.get(id))
        RelayHintCache.clear()
    }

    @Test
    fun `real-world Ditto kind-6 empty-content repost extracts relay hint and survives 25-note batch`() {
        val targetId = "0000005398df899f2ceeaf5a4c706d40fd0bcc4c065d4dc7379b17eb3fdcdf0a"
        val repost = Event(
            id = "f3f80df65baee91b2667c42f965528a52b275345b37194546176aa690e38cd9d",
            pubkey = "86184109eae937d8d6f980b4a0b46da4ef0d983eade403ee1b4c0b6bde238b47",
            createdAt = 1_794_667_424L,
            kind = EventKind.REPOST,
            tags = listOf(
                buildJsonArray { add("e"); add(targetId); add("wss://relay.ditto.pub/") },
                buildJsonArray { add("p"); add("05972259ec683e9843ce12fae6cedda06e65062cfb014799e2abd52b2932bae6") },
                buildJsonArray {
                    add("client")
                    add("Ditto")
                    add("31990:781a1527055f74c1f70230f10384609b34548f8ab6a0a6caa74025827f9fdae5:ditto")
                },
            ),
            content = "",
            sig = "504c3c8f914ba2025d2492befa4404d3eb98b7b2a6246807c03c61a0ba6853c09e50b6b3db6a041a81de2095919cb21729b6f4a34b9a58bd2460d6fc5198445d",
        )

        val extracted = RelayHintPolicy.extractReferencedEventHints(repost)
        assertEquals(listOf("wss://relay.ditto.pub/"), extracted[targetId])

        // Bury the Ditto repost at position #25 behind 24 other missing notes with 12 distinct hints.
        val missingIds = (1..24).map { i -> "%064x".format(i) } + targetId
        val hintsById = LinkedHashMap<String, List<String>>()
        for (i in 1..24) {
            val relayIdx = ((i - 1) % 12) + 1
            hintsById["%064x".format(i)] = listOf("wss://hint$relayIdx.example.com/")
        }
        hintsById[targetId] = extracted[targetId].orEmpty()

        val waves = RelayHintPolicy.planFallbackWaves(
            missingIds = missingIds,
            hintsById = hintsById,
            answeredStage1Relays = setOf("wss://nostr.wine"),
            allowHints = true,
            allowBridges = true,
            torEnabled = false,
            maxPerWave = 6,
            maxHintWaves = 3,
        )

        assertTrue("Expected bounded waves of <= 6 relays", waves.all { it.size <= 6 })
        val allPlanned = waves.flatten().toSet()
        assertTrue(
            "wss://relay.ditto.pub must NEVER be starved by earlier hints in the same batch",
            "wss://relay.ditto.pub" in allPlanned,
        )
    }

    @Test
    fun `planFallbackWaves guarantees Fediverse bridge relay even when hint waves are full`() {
        val missingIds = (1..20).map { i -> "%064x".format(i) }
        val hintsById = missingIds.associateWith { id ->
            val idx = id.takeLast(2).toInt(16)
            listOf("wss://relay$idx.example.com/")
        }

        val waves = RelayHintPolicy.planFallbackWaves(
            missingIds = missingIds,
            hintsById = hintsById,
            answeredStage1Relays = emptySet(),
            allowHints = true,
            allowBridges = true,
            torEnabled = false,
            maxPerWave = 6,
            maxHintWaves = 3,
        )

        // 18 hint relays fill 3 full waves of 6, plus a dedicated bridge wave for wss://relay.ditto.pub.
        assertEquals(4, waves.size)
        assertTrue(waves.last().contains("wss://relay.ditto.pub"))
        // If Stage 1 already got an answer from wss://relay.ditto.pub, do not re-query it.
        val wavesAfterAnswered = RelayHintPolicy.planFallbackWaves(
            missingIds = missingIds,
            hintsById = hintsById,
            answeredStage1Relays = setOf("wss://relay.ditto.pub/"),
            allowHints = true,
            allowBridges = true,
            torEnabled = false,
            maxPerWave = 6,
            maxHintWaves = 3,
        )
        assertEquals(3, wavesAfterAnswered.size)
        assertFalse(wavesAfterAnswered.flatten().contains("wss://relay.ditto.pub"))
    }
}
