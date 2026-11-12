package social.tbone.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `older backup note defaults folder pin to false`() {
        val oldNote = """{"id":"n1","kind":"note","text":"hello","title":"","createdAt":1,"updatedAt":2,"pinned":false,"sortOrder":0.0,"folderId":null}"""
        val decoded = json.decodeFromString<BackupNote>(oldNote)
        assertFalse(decoded.folderPinned)
    }

    @Test
    fun `folder pin survives manifest serialization`() {
        val note = BackupNote(
            id = "n1",
            kind = "note",
            text = "hello",
            title = "",
            createdAt = 1,
            updatedAt = 2,
            pinned = false,
            sortOrder = 0.0,
            folderId = "f1",
            folderPinned = true,
        )
        val encoded = json.encodeToString(note)
        assertTrue(json.decodeFromString<BackupNote>(encoded).folderPinned)
    }

    @Test
    fun `a manifest written before home feeds decodes with no layout`() {
        val old = """{"formatVersion":1,"notes":[],"folders":[],"attachments":[],"calendarEvents":[]}"""
        kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }.decodeFromString<BackupManifest>(old).let { manifest ->
            assertNull(manifest.homeLayout)
        }
    }

    @Test
    fun `the home layout rides through the manifest as an opaque document`() {
        // The backup deliberately does not model the layout: it stores the
        // layout codec's own JSON so the two schemas cannot drift.
        val raw = """{"slots":[],"lastActiveSlotId":null}"""
        val manifest = BackupManifest(
            formatVersion = 1,
            notes = emptyList(),
            folders = emptyList(),
            attachments = emptyList(),
            calendarEvents = emptyList(),
            homeLayout = raw,
        )
        assertEquals(raw, json.decodeFromString<BackupManifest>(json.encodeToString(manifest)).homeLayout)
    }

    @Test
    fun `an absent layout is written as an explicit null`() {
        val manifest = BackupManifest(
            formatVersion = 1,
            notes = emptyList(),
            folders = emptyList(),
            attachments = emptyList(),
            calendarEvents = emptyList(),
        )
        // `explicitNulls = true` matters: a backup made without a layout must be
        // readable by a build that expects the field, not silently skipped.
        assertTrue(json.encodeToString(manifest).contains("\"homeLayout\":null"))
    }
}
