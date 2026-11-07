package social.tbone.lists

import org.junit.Assert.*
import org.junit.Test
import social.tbone.account.Account
import social.tbone.account.SignerType

class ListWriteContextTest {
    @Test fun relayChangesOrAccountSwitchInvalidateAReplacement() {
        val initial = Account(pubkey = "a", signerType = SignerType.AMBER,
            relays = listOf("wss://one.example/", "wss://two.example"))
        assertTrue(sameListWriteContext(initial, initial.copy(relays = initial.relays.reversed())))
        assertTrue(sameListWriteContext(initial, initial.copy(relays = listOf("wss://one.example", "wss://two.example/"))))
        assertFalse(sameListWriteContext(initial, initial.copy(relays = listOf("wss://one.example"))))
        assertFalse(sameListWriteContext(initial, initial.copy(pubkey = "b")))
        assertFalse(sameListWriteContext(initial, null))
    }
}
