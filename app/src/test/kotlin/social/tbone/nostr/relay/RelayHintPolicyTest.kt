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
    fun `sanitizeHintUrl permits valid public wss relays and strips trailing slash`() {
        assertEquals(
            "wss://relay.mostr.pub",
            RelayHintPolicy.sanitizeHintUrl("  wss://relay.mostr.pub/  ", torEnabled = false),
        )
        assertEquals(
            "wss://ditto.pub/relay",
            RelayHintPolicy.sanitizeHintUrl("wss://ditto.pub/relay/", torEnabled = false),
        )
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
}
