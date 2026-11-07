package social.tbone.di

import okhttp3.OkHttpClient
import social.tbone.nostr.relay.RelayConnection

/**
 * The single OkHttp client used for fetching media (images) over plain HTTP(S).
 *
 * Updated by [social.tbone.BonyApp] after settings load and on Tor changes.
 * Calls from the old client are cancelled and old client generations are
 * rejected; this is not a global network kill switch.
 */
object ImageClientProvider {
    // The persisted setting is initially unknown. A proxy-only client prevents
    // early media requests from going direct before application settings load.
    @Volatile
    var client: OkHttpClient = guardedMediaClient(RelayConnection.buildClient(useTor = true), true, TorCallPolicy.guard)
        private set

    fun update(newClient: OkHttpClient, useTor: Boolean) {
        client = guardedMediaClient(newClient, useTor, TorCallPolicy.guard)
    }
}
