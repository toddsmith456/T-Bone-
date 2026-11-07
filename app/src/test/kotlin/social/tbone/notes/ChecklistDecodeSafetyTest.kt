package social.tbone.notes

import org.junit.Assert.*
import org.junit.Test

class ChecklistDecodeSafetyTest {
    @Test fun malformedChecklistCannotBeMistakenForAnEmptyEditableChecklist() {
        assertNull(ChecklistCodec.decodeOrNull("{not valid json"))
        assertEquals(ChecklistData(), ChecklistCodec.decodeOrNull(ChecklistCodec.encode(ChecklistData())))
    }
}
