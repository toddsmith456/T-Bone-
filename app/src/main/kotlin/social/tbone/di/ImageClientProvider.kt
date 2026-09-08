package social.tbone.di

import okhttp3.OkHttpClient
import social.tbone.nostr.relay.RelayConnection

/**
 * The single OkHttp client used for fetching media (images) over plain HTTP(S).
 *
 * Kept in sync with the Tor toggle by [social.tbone.BonyApp] so image fetches
 * are routed through the same proxy as relay WebSockets — never bypassing the
 * user's Tor choice. Coil is configured with this client at app start, and the
 * image viewer reuses it for downloads so a download goes through the exact
 * same path as the on-screen fetch.
 */
object ImageClientProvider {
    @Volatile
    var client: OkHttpClient = RelayConnection.buildClient(useTor = false)
        private set

    fun update(newClient: OkHttpClient) {
        client = newClient
    }
}
