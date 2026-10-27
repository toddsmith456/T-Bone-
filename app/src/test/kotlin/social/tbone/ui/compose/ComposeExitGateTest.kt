package social.tbone.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeExitGateTest {
    @Test
    fun `one successful publish triggers exactly one navigation`() {
        val gate = ComposeExitGate()
        var exits = 0

        gate.runOnce { exits++ }
        gate.runOnce { exits++ }
        gate.runOnce { exits++ }

        assertEquals(1, exits)
    }

    @Test
    fun `close and publish cannot both pop the graph`() {
        val gate = ComposeExitGate()
        var exits = 0

        gate.runOnce { exits++ } // ×
        gate.runOnce { exits++ } // late SEND effect

        assertEquals(1, exits)
    }
}
