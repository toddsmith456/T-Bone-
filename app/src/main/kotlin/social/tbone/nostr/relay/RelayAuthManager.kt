package social.tbone.nostr.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import social.tbone.account.AccountRepository
import social.tbone.account.SignerType
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.nostr.EventKind
import social.tbone.nostr.UnsignedEvent
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NIP-42: responds to AUTH challenges from relays.
 *
 * Observes [RelayPool.messages] for [RelayMessage.Auth] and replies with
 * a signed kind-22242 event containing the relay URL and challenge string.
 *
 * AUTH is skipped for Amber accounts — signing requires UI interaction
 * (an Amber dialog per challenge) which would be disruptive. Local key
 * and nsecBunker accounts respond silently.
 *
 * Call [start] once from MainActivity.
 */
@Singleton
class RelayAuthManager @Inject constructor(
    private val pool: RelayPool,
    private val signerFactory: NostrSignerFactory,
    private val accountRepository: AccountRepository,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            pool.messages.collect { (relayUrl, message) ->
                if (message is RelayMessage.Auth) {
                    handleAuth(scope, relayUrl, message.challenge)
                }
            }
        }
    }

    private fun handleAuth(scope: CoroutineScope, relayUrl: String, challenge: String) {
        scope.launch {
            val account = accountRepository.activeAccount.first()
            if (account == null) {
                Timber.w("AUTH from $relayUrl: no active account")
                return@launch
            }
            if (account.signerType == SignerType.AMBER) {
                Timber.d("AUTH from $relayUrl: skipping — Amber requires UI interaction")
                return@launch
            }

            Timber.d("AUTH challenge from $relayUrl: $challenge")
            val signer = signerFactory.forActiveAccount() ?: return@launch

            val unsigned = UnsignedEvent(
                pubkey = signer.pubkey,
                kind = EventKind.AUTH,
                content = "",
                tags = listOf(
                    buildJsonArray { add(JsonPrimitive("relay")); add(JsonPrimitive(relayUrl)) },
                    buildJsonArray { add(JsonPrimitive("challenge")); add(JsonPrimitive(challenge)) },
                ),
            )

            signer.signEvent(unsigned)
                .onSuccess { signed ->
                    pool.send(relayUrl, ClientMessage.Auth(signed))
                    Timber.d("AUTH sent to $relayUrl")
                }
                .onFailure { e ->
                    Timber.w("AUTH signing failed for $relayUrl: ${e.message}")
                }
        }
    }
}
