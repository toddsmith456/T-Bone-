package social.tbone.wallet

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LightningProfileTest {
    @Test fun updatePreservesOtherMetadataAndClearsStaleFallback() {
        val input = """{"name":"Alice","future":{"keep":true},"lud16":"old@example.org","lud06":"old"}"""
        val obj = Json.parseToJsonElement(updateLightningProfileMetadata(input, "new@example.org")).jsonObject
        assertEquals("Alice", obj["name"]?.jsonPrimitive?.content)
        assertEquals("true", obj["future"]?.jsonObject?.get("keep")?.jsonPrimitive?.content)
        assertEquals("new@example.org", obj["lud16"]?.jsonPrimitive?.content)
        assertFalse(obj.containsKey("lud06"))
        val removed = Json.parseToJsonElement(updateLightningProfileMetadata(input, null)).jsonObject
        assertFalse(removed.containsKey("lud16")); assertFalse(removed.containsKey("lud06"))
        assertTrue(removed.containsKey("future"))
    }
    @Test fun validatesAddressShapes() {
        assertTrue(isLightningAddressShape("alice@example.org"))
        assertFalse(isLightningAddressShape("alice@bad"))
        assertFalse(isLightningAddressShape("alice@example.org/path"))
        assertFalse(isLightningAddressShape("a b@example.org"))
    }
}
