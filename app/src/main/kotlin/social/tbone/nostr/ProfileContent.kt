package social.tbone.nostr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parsed content of a kind-0 (metadata) event.
 * All fields are optional — relays and clients populate different subsets.
 */
@Serializable
data class ProfileContent(
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val nip05: String? = null,
    val lud16: String? = null,
    val lud06: String? = null,
    val website: String? = null,
) {
    /** Best available display name, falling back to null if none present. */
    val bestName: String? get() = displayName?.trim()?.takeIf { it.isNotBlank() }
        ?: name?.trim()?.takeIf { it.isNotBlank() }

    /**
     * The NIP-57 recipient address published in this kind-0 profile.
     * Amethyst and other Nostr clients prefer a Lightning Address (lud16), then
     * fall back to an LNURL (lud06). Keep the original fields intact for
     * publishing while exposing one safe value to zap callers.
     */
    val lightningAddress: String?
        get() = lud16?.trim()?.takeIf { it.isNotEmpty() }
            ?: lud06?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        fun parse(content: String): ProfileContent? =
            runCatching { NostrJson.decodeFromString<ProfileContent>(content) }.getOrNull()
    }
}
