package social.tbone.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Nip19

class ListFileFormatTest {

    private fun pk(n: Int) = n.toString(16).padStart(64, '0')
    private val owner = pk(0xabc)

    @Test fun exportThenImportRoundTripsEverything() {
        val entries = listOf(
            ListEntry(pk(1)),
            ListEntry(pk(2), relay = "wss://relay.example.com"),
            ListEntry(pk(3), relay = "wss://r.example", petname = "alice"),
            ListEntry(pk(4), relay = "", petname = "bob"),
        )
        val text = ListFileFormat.export(ListType.FOLLOWS, owner, entries, createdAtSeconds = 1_700_000_000)
        val parsed = ListFileFormat.parse(text, ListType.FOLLOWS)
        assertNull(parsed.error)
        assertEquals(entries, parsed.entries)
        assertEquals(owner, parsed.ownerPubkey)
        assertEquals(0, parsed.skipped)
    }

    @Test fun exportIsAStandardNostrListEvent() {
        val text = ListFileFormat.export(ListType.FOLLOWS, owner, listOf(ListEntry(pk(1))), 1_700_000_000)
        assertTrue(text.contains("\"kind\": 3"))
        assertTrue(text.contains("\"created_at\": 1700000000"))
        assertTrue(text.contains("\"pubkey\": \"$owner\""))
        assertTrue(text.contains("\"p\""))
        val mute = ListFileFormat.export(ListType.MUTES, owner, listOf(ListEntry(pk(1))), 1_700_000_000)
        assertTrue(mute.contains("\"kind\": 10000"))
    }

    @Test fun emptyListExportsAndReimportsAsNoEntriesError() {
        val text = ListFileFormat.export(ListType.MUTES, owner, emptyList())
        val parsed = ListFileFormat.parse(text, ListType.MUTES)
        assertNotNull(parsed.error)
        assertTrue(parsed.entries.isEmpty())
    }

    @Test fun rejectsAFollowFileImportedAsMuteListAndViceVersa() {
        val follows = ListFileFormat.export(ListType.FOLLOWS, owner, listOf(ListEntry(pk(1))))
        val r1 = ListFileFormat.parse(follows, ListType.MUTES)
        assertNotNull(r1.error)
        assertTrue(r1.entries.isEmpty())
        val mutes = ListFileFormat.export(ListType.MUTES, owner, listOf(ListEntry(pk(1))))
        val r2 = ListFileFormat.parse(mutes, ListType.FOLLOWS)
        assertNotNull(r2.error)
    }

    @Test fun acceptsFullySignedRelayEventWithExtraFields() {
        val json = """{"id":"${pk(9)}","pubkey":"$owner","created_at":1,"kind":3,
            "tags":[["p","${pk(1)}","wss://x.io","carol"],["t","nostr"],["p","${pk(2)}"]],
            "content":"{\"wss://old\":{\"read\":true}}","sig":"${pk(8)}${pk(7)}"}"""
        val r = ListFileFormat.parse(json, ListType.FOLLOWS)
        assertNull(r.error)
        assertEquals(listOf(ListEntry(pk(1), "wss://x.io", "carol"), ListEntry(pk(2))), r.entries)
    }

    @Test fun nip51FollowSetKindIsAcceptedForFollowsButNotMutes() {
        val json = """{"kind":30000,"pubkey":"$owner","created_at":5,"tags":[["d","friends"],["p","${pk(5)}"]],"content":""}"""
        assertEquals(listOf(ListEntry(pk(5))), ListFileFormat.parse(json, ListType.FOLLOWS).entries)
        assertNotNull(ListFileFormat.parse(json, ListType.MUTES).error)
    }

    @Test fun arrayOfEventsPicksNewestMatchingKind() {
        val json = """[
          {"kind":3,"pubkey":"$owner","created_at":10,"tags":[["p","${pk(1)}"]],"content":""},
          {"kind":3,"pubkey":"$owner","created_at":99,"tags":[["p","${pk(2)}"]],"content":""},
          {"kind":10000,"pubkey":"$owner","created_at":200,"tags":[["p","${pk(3)}"]],"content":""}
        ]"""
        assertEquals(listOf(ListEntry(pk(2))), ListFileFormat.parse(json, ListType.FOLLOWS).entries)
        assertEquals(listOf(ListEntry(pk(3))), ListFileFormat.parse(json, ListType.MUTES).entries)
    }

    @Test fun acceptsJsonArrayOfNpubHexAndNprofileStrings() {
        val npub = Nip19.hexToNpub(pk(11))
        val json = """["$npub","${pk(12)}","nostr:$npub","not-a-key",""]"""
        val r = ListFileFormat.parse(json, ListType.FOLLOWS)
        assertNull(r.error)
        assertEquals(listOf(pk(11), pk(12)), r.entries.map { it.pubkey })
        assertEquals(2, r.skipped) // "not-a-key" and the empty string
    }

    @Test fun acceptsArrayOfBareTags() {
        val json = """[["p","${pk(21)}"],["p","${pk(22)}","wss://a.b"],["e","${pk(23)}"]]"""
        val r = ListFileFormat.parse(json, ListType.MUTES)
        assertEquals(listOf(pk(21), pk(22)), r.entries.map { it.pubkey })
        assertEquals("wss://a.b", r.entries[1].relay)
    }

    @Test fun acceptsContainerObjects() {
        val json = """{"follows":["${Nip19.hexToNpub(pk(31))}","${pk(32)}"]}"""
        assertEquals(listOf(pk(31), pk(32)), ListFileFormat.parse(json, ListType.FOLLOWS).entries.map { it.pubkey })
        val json2 = """{"pubkeys":[{"pubkey":"${pk(33)}","petname":"dan"}]}"""
        assertEquals(ListEntry(pk(33), "", "dan"), ListFileFormat.parse(json2, ListType.FOLLOWS).entries.single())
    }

    @Test fun acceptsPlainTextListsFromOtherTools() {
        val a = Nip19.hexToNpub(pk(41))
        val b = Nip19.hexToNpub(pk(42))
        val text = "\uFEFF# my follows\n$a\n  $b , ${pk(43)};nostr:$a\n\nhello\n"
        val r = ListFileFormat.parse(text, ListType.FOLLOWS)
        assertNull(r.error)
        assertEquals(listOf(pk(41), pk(42), pk(43)), r.entries.map { it.pubkey })
        assertEquals(1, r.skipped)
    }

    @Test fun uppercaseHexAndNpubAreNormalised() {
        assertEquals(pk(0xabcdef), ListFileFormat.normalise(pk(0xabcdef).uppercase()))
        assertEquals(pk(7), ListFileFormat.normalise(Nip19.hexToNpub(pk(7)).uppercase()))
        assertNull(ListFileFormat.normalise("npub1invalid"))
        assertNull(ListFileFormat.normalise(pk(1).dropLast(1)))
        assertNull(ListFileFormat.normalise(""))
        assertNull(ListFileFormat.normalise(null))
        assertNull(ListFileFormat.normalise("zz".repeat(32)))
    }

    @Test fun duplicatesAreCollapsedKeepingFirst() {
        val json = """["${pk(1)}","${pk(1)}","${Nip19.hexToNpub(pk(1))}"]"""
        assertEquals(1, ListFileFormat.parse(json, ListType.FOLLOWS).entries.size)
    }

    @Test fun badRelayHintsAreDropped() {
        val json = """[["p","${pk(1)}","javascript:alert(1)","x"]]"""
        assertEquals("", ListFileFormat.parse(json, ListType.FOLLOWS).entries.single().relay)
    }

    @Test fun garbageIsRejectedWithAMessage() {
        assertNotNull(ListFileFormat.parse("", ListType.FOLLOWS).error)
        assertNotNull(ListFileFormat.parse("   \n", ListType.FOLLOWS).error)
        assertNotNull(ListFileFormat.parse("{not json", ListType.FOLLOWS).error)
        assertNotNull(ListFileFormat.parse("{\"hello\":1}", ListType.FOLLOWS).error)
        assertNotNull(ListFileFormat.parse("just some words here", ListType.FOLLOWS).error)
        assertNotNull(ListFileFormat.parse("[]", ListType.FOLLOWS).error)
    }

    @Test fun suggestedFileNameIsStable() {
        val name = ListFileFormat.suggestedFileName(ListType.MUTES, pk(5), "20261001")
        assertTrue(name.startsWith("tbone-mutes-npub1"))
        assertTrue(name.endsWith("-20261001.json"))
    }
}
