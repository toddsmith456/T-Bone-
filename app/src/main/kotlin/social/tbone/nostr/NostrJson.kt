package social.tbone.nostr

import kotlinx.serialization.json.Json

/** Shared Json instance for all Nostr serialization. Single configuration, no per-file copies. */
val NostrJson: Json = Json { ignoreUnknownKeys = true }
