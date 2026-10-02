# Blossom uploads — how they work in this fork

Media (images, video, profile pictures/banners) is uploaded to
[Blossom](https://github.com/hzrd149/blossom) servers. This document describes
the implementation as rebuilt in 0.3.39 and how to verify it.

Nothing about the screens changed: the compose screen still shows the server
chips ("random" + one per enabled server) and Settings → Blossom still lists,
adds, defaults and disables servers.

---

## 1. What was broken

The old uploader authorized its requests with a **NIP-98 `kind 27235`** event
carrying only `u` (URL) and `method` tags, and it ignored everything the server
answered with.

Blossom does not use NIP-98. It uses **BUD-11**: a **`kind 24242`** event whose
tags describe the operation (`t`, `x`, `size`, `expiration`, optional
`server`). Every deployed server rejects 27235 outright — measured live while
rebuilding:

| Server | Response to the old `kind 27235` token |
|---|---|
| `nostr.download` | `400 Wrong event kind` |
| `blossom.data.haus` | `400 Auth event must be kind 24242` |
| `blossom.ditto.pub` | `400 Auth event must be kind 24242` |
| `blossom.nostr.build` | `401 Invalid Blossom Authorization event kind` |
| `blossom.band` | `401 Invalid Blossom Authorization event kind` |
| `blossom.primal.net` | `401 wrong kind in auth event` |

So **every** upload failed on **every** server, whatever the user picked.

Four more defects were fixed in the same pass:

1. **Wrong MIME type.** Images are re-encoded to JPEG by `MediaProcessor`, but
   the uploader sent the *picker's* MIME (e.g. `image/png`), describing bytes
   that no longer existed. Servers validate this and store the value as the
   blob's `Content-Type` forever after.
2. **Descriptor ignored.** The server's real `url` (with extension) was thrown
   away and `<server>/<sha256>` was rebuilt by hand.
3. **No failover for real.** Only *one* random fallback server was tried after
   the preferred/default failed, so a single dead server could kill a post.
4. **Infinite timeouts.** Uploads reused the WebSocket client
   (`readTimeout(0)` = never time out), so a stalled server left the compose
   screen spinning on "…uploading" forever.

---

## 2. How it works now

```
ComposeScreen / ProfileEditScreen
        │  Uri
        ▼
MediaProcessor            → PreparedMedia(file, mime, extension)
        │                    (real MIME of the bytes on disk)
        ▼
BlossomUploader           → picks servers, signs ONE token, tries each in turn
        │
        ▼
BlossomClient             → PUT <server>/upload (streamed, exact Content-Length)
        │
        ▼
BlossomUploadResult       → the server's own url, or <server>/<sha256>.<ext>
```

| File | Responsibility |
|---|---|
| `media/blossom/BlossomAuth.kt` | Signs the `kind 24242` token, base64-encodes it, builds `Authorization: Nostr …` |
| `media/blossom/BlossomServerUrl.kt` | Endpoint paths, `X-Reason`/`X-SHA-256` header names, URL normalisation and domain extraction |
| `media/blossom/BlossomClient.kt` | OkHttp transport: streaming PUT, HEAD probe, DELETE, `sha256Hex`, typed `BlossomException` |
| `media/blossom/BlossomUploader.kt` | Orchestration: server selection, single signing pass, failover, error aggregation |
| `media/blossom/BlossomServerPool.kt` | Pure candidate-ordering policy (unit-tested) |
| `media/blossom/BlossomUploadResult.kt` | Lenient parser for the BUD-02 blob descriptor |
| `media/MediaProcessor.kt` | Prepares media and reports its true MIME type + extension |
| `media/MimeTypes.kt` | MIME → extension mapping |

### The authorization token

```json
{
  "kind": 24242,
  "tags": [
    ["t", "upload"],
    ["expiration", "1725109521"],
    ["size", "184292"],
    ["x", "<sha256 of the file>"],
    ["server", "nostr.download"]
  ],
  "content": "Uploading image"
}
```

Sent as `Authorization: Nostr <base64>`.

Two details are load-bearing and should not be "modernised":

* **Kind 24242, not 27235.** NIP-98 is a different protocol (NIP-96 hosts);
  Blossom servers reject it.
* **Standard Base64 *with* padding, not Base64url.** Some BUD-11 drafts say
  URL-safe-without-padding; deployed khatru servers decode with Go's strict
  `base64.StdEncoding`, which rejects the `-`/`_` alphabet. Verified live:
  url-safe tokens are answered `400` by `nostr.download` and
  `blossom.primal.net`, while standard tokens succeed everywhere.
  This is why `BlossomAuth` uses `java.util.Base64` (available since API 26,
  which is this app's `minSdk`) rather than the URL-safe variant.

### One token for the whole pool

Every candidate server gets a `server` tag, so a single token authorizes the
whole enabled pool. Failover therefore costs exactly **one signing prompt**,
even with Amber/nsecBunker. Verified live: a multi-`server` token is accepted
by all three default servers.

### Server selection

1. the server chosen on the compose screen,
2. the user's default (Settings → Blossom),
3. every remaining enabled server, in random order.

The first two are unchanged behaviour; the difference is that step 3 now keeps
failing over through the *whole* pool.

---

## 3. Testing

**Unit tests** (`./gradlew :app:testDebugUnitTest`, also run by the release
workflow before building APKs) cover the token contents, the base64 encoding,
the exact HTTP request (method, path, headers, body), descriptor parsing,
server selection and the sha256 formatting (against a known reference vector).

**Live test** — opt-in, skipped by default so CI stays hermetic:

```bash
./gradlew :app:testDebugUnitTest --tests '*BlossomLiveTest*' -DblossomLive=1
```

It signs a real token, uploads to `nostr.download`, `blossom.data.haus` and
`blossom.ditto.pub` using the production `BlossomAuth` + `BlossomClient` code,
then re-downloads the blob and asserts it is byte-identical with a matching
SHA-256. Run it after any change to the upload path.

### Adding a server to the defaults

Verify it first with the live test (add it to that list) — server behaviour
varies more than the spec suggests. Two known quirks:

* `blossom.nostr.build` and `blossom.band` inspect the body and reject
  mismatched `Content-Type`s (which is exactly bug #1 above).
* `blossom.primal.net` serves blobs back only with read authorization, so it is
  a poor default for a public feed.
