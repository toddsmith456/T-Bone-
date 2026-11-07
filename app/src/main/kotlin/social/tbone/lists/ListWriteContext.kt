package social.tbone.lists

import social.tbone.account.Account
import social.tbone.nostr.relay.RelayPool

/** Replacement events must use the same account and relay coverage that was read. */
internal fun sameListWriteContext(expected: Account, current: Account?): Boolean =
    current != null && current.pubkey == expected.pubkey &&
        current.effectiveRelays().map(RelayPool::normalize).toSet() ==
        expected.effectiveRelays().map(RelayPool::normalize).toSet()
