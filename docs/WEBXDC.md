# webxdc tool

T-Bone can run [webxdc](https://webxdc.org/) mini apps — `.xdc` files, which are
ordinary ZIP archives containing `index.html`, an optional `manifest.toml` and
an optional `icon.png`/`icon.jpg`.

Unlike a messenger, T-Bone is deliberately **single-player**: there are no
peers, no chat rooms and no relay-published state. You download an app, it runs
locally, and whatever it writes stays on the device.

## The four ways to get an app

| Source | Where it comes from |
|---|---|
| **Local file** | The `+` button in the tool header opens Android's document picker. Any `.xdc` file works. |
| **Store** | The official [webxdc.org app store](https://webxdc.org/apps). T-Bone reads its catalogue (`https://apps.testrun.org/xdcget-lock.json`) and downloads the `.xdc` from the same host. |
| **T-Bone feed** | Nostr events authored by the T-Bone npub `npub1zx2tlzz35z44lcs47g3mgjts769wmxdjyzugc7jpfha7xrm72puq09ftjq`. |
| **Nostr feed** | Every webxdc app on the connected relays, any author. |

Both Nostr feeds use the same query and differ only by author:

```
{ "kinds": [1063], "#m": ["application/x-webxdc"], "limit": 100 }
```

`#m` is a normal NIP-01 tag filter on the NIP-94 `m` tag, which is what makes
these events discoverable from relays. Clients that cannot filter on `#m` would
have to download every kind-1063 event; T-Bone also re-checks the MIME locally
because a relay is free to ignore an unknown filter key.

## The NIP: NIP-DC ("Nostr Webxdc")

This is the draft Ditto implements (`NOSTR_WEBXDC.md` in Ditto's repository):

* **Attachment** — a `.xdc` URL lives in the event `content` and in an `imeta`
  tag with `m application/x-webxdc` and a `webxdc <uuid>` coordination id.
* **Kind 1063** — the same thing as NIP-94 file metadata:
  `url`, `m`, `x`, `alt` ("Webxdc app: Name"), `webxdc`, `thumb`, `size`.
* **Kind 4932** — a state update: `i` = the webxdc uuid, `alt`, optional
  `info`, `document`, `summary`, `content` = JSON payload.
* **Kind 20932** — ephemeral realtime data (`i` tag, base64 content).

T-Bone reads **kind 1063** for discovery. It does not publish or subscribe to
kind 4932/20932: with a single user there is nobody to sync with, and the
webxdc API is still fully implemented against a local journal, so apps that call
`sendUpdate()` / `setUpdateListener()` work exactly as they do in a chat.

## Running an app

Each saved app is extracted into app-private storage:

```
filesDir/webxdc/apps/<id>/files/…     the archive, extracted
filesDir/webxdc/apps/<id>/meta.json   name, origin, icon, size, uuid
filesDir/webxdc/updates/<id>.log      append-only update journal
```

The player serves the files from a private origin,
`https://<id>.xdc.local/`, through `WebViewClient.shouldInterceptRequest`:

* every request to any **other** host gets a 403 and never reaches the network;
* HTML responses carry
  `Content-Security-Policy: default-src 'self' 'unsafe-inline' 'unsafe-eval' 'wasm-unsafe-eval' data: blob:`;
* `allowFileAccess` and `allowContentAccess` are off, so `file://` and
  `content://` are unreachable;
* paths are canonicalised and must stay inside the app's own directory;
* links the app opens to the outside world are handed to the system browser.

`webxdc.js` is provided by T-Bone, never by the archive (as the spec requires).
It is injected as the first `<head>` child of every HTML response, so apps work
whether or not they included `<script src="webxdc.js">` themselves.

### The single-player API

| API | Behaviour |
|---|---|
| `sendUpdate(update)` | Appends to the local journal, then delivers to the app's listener with the next serial. |
| `setUpdateListener(cb, serial)` | Replays every journaled update with `serial > serial`, then resolves. |
| `getAllUpdates()` | Returns the whole journal. |
| `importFiles(filter)` | Opens Android's document picker and resolves with `File` objects. |
| `sendToChat(message)` | Writes the file to the cache and opens Android's share sheet. |
| `joinRealtimeChannel()` | A local no-op channel: `setListener`/`send`/`leave` exist so feature-detecting apps do not crash. |
| `selfAddr` / `selfName` | The active account's npub and profile name (`tbone:local` / `you` when logged out). |

Nothing is ever published to a relay or sent to another device.

## Adding an app as a tool

Saved apps can be promoted to first-class toolbox tools:

1. open **webxdc → saved**,
2. tap `⋯` on an app → **add as tool**,
3. pick a name and one of **20 generic icons** (game, poll, checklist, notes,
   calculator, calendar, timer, map, music, camera, chat, puzzle, dice, wallet,
   weather, quiz, piano, document, favourite, art),
4. the tool appears in the toolbox list, reorderable like every other tool and
   opening straight into the player.

Tools live in DataStore (`xdc_custom_tools`); deleting the app removes its tool.

## Limits and validation

* archive ≤ 64 MB; uncompressed ≤ 96 MB; ≤ 4096 entries;
* only Store/Deflate compression (RFC 1950) is accepted;
* `index.html` is required; `manifest.toml` names are used when present,
  otherwise the file name;
* zip-slip paths (`..`, absolute drive paths) are rejected;
* 20 MB of import files / 8 MB per file are handed to mini apps.

## Verified against

`XdcRealFilesTest` (opt-in, `-DxdcFixtures=<dir>`) parses real packages from the
store and Blossom/Nostr: Calculator, 2048, Arkanoid, Audio Dash, Scramble,
XDC-Chess, Shell, Gomoku, Heretic (10.6 MB) and Oregon Trail (9.5 MB). All ten
pass validation, manifest naming, icon discovery and bridge injection.

## References

* <https://webxdc.org/docs/>
* <https://webxdc.org/docs/spec/format.html>
* <https://webxdc.org/docs/spec/api.html>
* Ditto's `NOSTR_WEBXDC.md` (NIP-DC draft)
