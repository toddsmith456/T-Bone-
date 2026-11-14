package social.tbone.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip19Test {

    @Test
    fun `encodes a nevent reference with author and kind hints`() {
        val id = "ab".repeat(32)
        val author = "cd".repeat(32)
        val nevent = Nip19.hexToNevent(id, authorPubkeyHex = author, kind = 1)

        assertTrue(nevent.startsWith("nevent1"))
        assertTrue(Nip19.isBech32(nevent))
        assertEquals(id, Nip19.neventToHex(nevent))
        assertEquals(id, Nip19.nostrUriToEventId("nostr:$nevent"))
    }

    @Test
    fun `encodes a plain nevent with just the event id`() {
        val id = "12".repeat(32)
        val nevent = Nip19.hexToNevent(id)
        assertEquals(id, Nip19.neventToHex(nevent))
    }
    @Test
    fun `address references round trip and reject malformed input`() {
        val address = Nip19.Address("article-é", "ab".repeat(32), 30023)
        val naddr = Nip19.addressToNaddr(address)
        assertTrue(Nip19.isBech32(naddr))
        assertEquals(address, Nip19.naddrToAddress("nostr:$naddr"))
        assertEquals(null, Nip19.naddrToAddress(naddr.dropLast(1)))
        assertEquals(null, Nip19.nostrUriToEventId(naddr))
    }

    @Test
    fun `neventToReference extracts relay hints, author pubkey, and kind`() {
        val id = "ef".repeat(32)
        val author = "11".repeat(32)
        val nevent = Nip19.hexToNevent(
            idHex = id,
            authorPubkeyHex = author,
            kind = 1,
            relayHints = listOf("wss://relay.mostr.pub", "wss://ditto.pub/relay"),
        )
        val ref = Nip19.neventToReference("nostr:$nevent")
        assertEquals(id, ref?.idHex)
        assertEquals(author, ref?.authorPubkey)
        assertEquals(1, ref?.kind)
        assertEquals(listOf("wss://relay.mostr.pub", "wss://ditto.pub/relay"), ref?.relayHints)
    }

    @Test
    fun `naddrToAddress preserves embedded TLV relay hints`() {
        val address = Nip19.Address(
            identifier = "fediverse-post",
            pubkey = "22".repeat(32),
            kind = 30023,
            relayHints = listOf("wss://relay.mostr.pub", "wss://relay.momostr.pink"),
        )
        val naddr = Nip19.addressToNaddr(address)
        assertEquals(address, Nip19.naddrToAddress("nostr:$naddr"))
    }
}
