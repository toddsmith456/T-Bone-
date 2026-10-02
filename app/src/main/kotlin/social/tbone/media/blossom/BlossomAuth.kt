package social.tbone.media.blossom

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import social.tbone.account.signer.NostrSigner
import social.tbone.nostr.Event
import social.tbone.nostr.NostrJson
import social.tbone.nostr.UnsignedEvent
import java.util.Base64

/**
 * Builds Blossom authorization tokens — the BUD-11 (NIP-B7) scheme, and the
 * reason media uploads previously failed on every public server.
 *
 * ### What the servers actually require
 *
 * A Blossom request is authorized by a **kind 24242** event whose tags describe
 * the operation:
 *
 * ```
 * { "kind": 24242,
 *   "tags": [ ["t","upload"], ["x","<sha256>"], ["size","184292"],
 *             ["expiration","1725109521"], ["server","cdn.example.com"] ],
 *   "content": "Uploading cat photo" }
 * ```
 *
 * The previous implementation sent a **kind 27235** NIP-98 event carrying only
 * `u`/`method` tags. Every deployed server rejects that outright — observed
 * live during the rewrite: `400 Wrong event kind` (nostr.download),
 * `400 Auth event must be kind 24242` (blossom.data.haus, blossom.ditto.pub),
 * `401 Invalid Blossom Authorization event kind` (blossom.nostr.build,
 * blossom.band), `401 wrong kind in auth event` (blossom.primal.net).
 *
 * NIP-98 (27235) is a *different* protocol used by NIP-96/NIP-05 style hosts;
 * Blossom only ever accepts 24242.
 *
 * ### Encoding
 *
 * The token is the event JSON encoded as **standard Base64 with padding**
 * (`java.util.Base64`, the same encoder Amethyst uses). It is deliberately NOT
 * Base64url-without-padding as some BUD-11 drafts suggest: deployed
 * khatru-based servers decode with Go's strict `base64.StdEncoding`, which
 * rejects the `-`/`_` alphabet — verified live, url-safe tokens are answered
 * with `400` by nostr.download and blossom.primal.net while standard tokens
 * succeed. Interop beats the draft; do not "fix" this back to URL-safe.
 *
 * ### Scoping
 *
 * `server` tags are optional but are emitted for every candidate host, which
 * lets **one** token authorize the whole enabled server pool. That keeps the
 * failover path to a single signing prompt even for Amber/nsecBunker signers,
 * instead of re-signing for every server we try.
 */
object BlossomAuth {

    /** BUD-11 authorization event kind. */
    const val KIND_AUTHORIZATION = 24242

    /** `t` values — the action a token authorizes. */
    const val TYPE_GET = "get"
    const val TYPE_UPLOAD = "upload"
    const val TYPE_DELETE = "delete"
    const val TYPE_LIST = "list"
    const val TYPE_MEDIA = "media"

    /** `Authorization: Nostr <base64>` — BUD-11 header scheme. */
    const val HEADER_SCHEME = "Nostr "

    /**
     * Token lifetime. Servers reject expired tokens and some cap how far in the
     * future `expiration` may be, so an hour is the sweet spot.
     */
    const val DEFAULT_TTL_SECONDS = 60L * 60L

    private fun tag(vararg values: String) = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    /**
     * Signs a kind 24242 authorization event.
     *
     * @param type one of [TYPE_UPLOAD], [TYPE_GET], [TYPE_DELETE], [TYPE_LIST], [TYPE_MEDIA].
     * @param alt human-readable description, carried as the event content.
     * @param hash blob SHA-256 — required by upload/delete/media, optional for get.
     * @param size byte length of the blob (server-side size policy checks).
     * @param servers base URLs to scope the token to; each contributes a
     *   `server` tag holding its bare lowercase domain.
     */
    suspend fun sign(
        signer: NostrSigner,
        type: String,
        alt: String,
        hash: String? = null,
        size: Long? = null,
        servers: List<String> = emptyList(),
        createdAt: Long = System.currentTimeMillis() / 1000,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    ): Event {
        val tags = buildList {
            add(tag("t", type))
            add(tag("expiration", (createdAt + ttlSeconds).toString()))
            size?.let { add(tag("size", it.toString())) }
            hash?.let { add(tag("x", it.lowercase())) }
            servers
                .map(BlossomServerUrl::domain)
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach { add(tag("server", it)) }
        }

        val unsigned = UnsignedEvent(
            pubkey = signer.pubkey,
            createdAt = createdAt,
            kind = KIND_AUTHORIZATION,
            tags = tags,
            content = alt,
        )
        return signer.signEvent(unsigned).getOrThrow()
    }

    /**
     * The full `Authorization` header value (`Nostr <base64>`) for [event].
     * See the class docs for why the encoder is standard Base64 with padding.
     */
    fun headerValue(event: Event): String = HEADER_SCHEME + rawToken(event)

    /** The base64 token alone (what `Authorization: Nostr …` carries after the scheme). */
    fun rawToken(event: Event): String =
        Base64.getEncoder().encodeToString(
            NostrJson.encodeToString(Event.serializer(), event).toByteArray(Charsets.UTF_8),
        )

    /** Convenience: signs and returns the ready-to-send `Authorization` header value. */
    suspend fun header(
        signer: NostrSigner,
        type: String,
        alt: String,
        hash: String? = null,
        size: Long? = null,
        servers: List<String> = emptyList(),
        createdAt: Long = System.currentTimeMillis() / 1000,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    ): String = headerValue(
        sign(signer, type, alt, hash, size, servers, createdAt, ttlSeconds),
    )
}
