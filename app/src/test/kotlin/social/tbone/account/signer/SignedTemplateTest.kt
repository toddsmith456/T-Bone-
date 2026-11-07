package social.tbone.account.signer

import org.junit.Assert.*
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.UnsignedEvent

class SignedTemplateTest {
    @Test fun substitutedFieldsAreRejected() {
        val template = UnsignedEvent(pubkey = "owner", createdAt = 123, kind = 3, content = "original")
        val signed = Event("id", "owner", 123, 3, emptyList(), "original", "signature")
        assertTrue(matchesSignedTemplate(template, signed))
        assertFalse(matchesSignedTemplate(template, signed.copy(pubkey = "other")))
        assertFalse(matchesSignedTemplate(template, signed.copy(createdAt = 124)))
        assertFalse(matchesSignedTemplate(template, signed.copy(kind = 1)))
        assertFalse(matchesSignedTemplate(template, signed.copy(content = "modified")))
        assertFalse(matchesSignedTemplate(template, signed.copy(tags = listOf(kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("p")) }))))
    }
}
