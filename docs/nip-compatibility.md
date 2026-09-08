# NIP Compatibility

Legend: ✅ Supported &nbsp;|&nbsp; 🚧 Partial &nbsp;|&nbsp; 🔌 Plugin &nbsp;|&nbsp; planned &nbsp;|&nbsp; — N/A

## Event Kind Support

| Kind | Name | Handling |
|------|------|----------|
| 0 | Metadata | Parsed into `ProfileContent`; cached in `ProfileRepository`; drives avatar + display name in feed, threads, profile pages, and @mention resolution |
| 1 | Text Note | Stored in Room DB; displayed in feed and thread view; inline images and video; `nostr:` URI handling; reply and quote-note composing |
| 3 | Follow List | Drives home feed subscription; persisted to account; follow/unfollow publishes updated kind-3 |
| 6 | Repost | Rendered as embedded card with original author and content; publishing (boost) supported |
| 7 | Reaction | NIP-25 `+` reactions tracked per event; optimistic publish with rollback; like count displayed |
| 10002 | Relay List | Read relays extracted and connected on login; list persisted to account |
| 22242 | Auth | Signed automatically in response to NIP-42 relay challenges |
| 24133 | Nostr Connect | Used internally by `NsecBunkerSigner` for NIP-46 request/response |

## Built into the App

| NIP | Name | Status | Notes |
|---|---|---|---|
| [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Basic protocol & event model | ✅ | Event, Filter, relay WebSocket pool, kind-0 profile metadata |
| [NIP-02](https://github.com/nostr-protocol/nips/blob/master/02.md) | Contact lists | ✅ | Follow list drives home feed; follow/unfollow publishes updated kind-3 |
| [NIP-10](https://github.com/nostr-protocol/nips/blob/master/10.md) | Text note references & replies | ✅ | Reply composing with root/reply markers; thread view; reply indicator in feed |
| [NIP-18](https://github.com/nostr-protocol/nips/blob/master/18.md) | Reposts | ✅ | Render kind-6 reposts and `q`-tag quote-notes as embedded cards; boost and quote-note composing |
| [NIP-19](https://github.com/nostr-protocol/nips/blob/master/19.md) | bech32-encoded entities | ✅ | npub, note, nevent, nprofile encode/decode; nostr: URI parsing in note content |
| [NIP-27](https://github.com/nostr-protocol/nips/blob/master/27.md) | Text note references | ✅ | `nostr:note1`/`nevent1` rendered as embedded quote cards; `nostr:npub1`/`nprofile1` resolved to display names |
| [NIP-42](https://github.com/nostr-protocol/nips/blob/master/42.md) | Relay authentication | ✅ | Automatic kind-22242 response to AUTH challenges; skipped for Amber (requires UI interaction) |
| [NIP-44](https://github.com/nostr-protocol/nips/blob/master/44.md) | Versioned encryption | ✅ | ChaCha20 + HMAC-SHA256, HKDF |
| [NIP-46](https://github.com/nostr-protocol/nips/blob/master/46.md) | Nostr Connect (nsecBunker) | 🚧 | Signer and onboarding UI implemented; end-to-end testing against real bunkers pending |
| [NIP-55](https://github.com/nostr-protocol/nips/blob/master/55.md) | Android signer (Amber) | ✅ | Sign, encrypt, decrypt via intent |
| [NIP-65](https://github.com/nostr-protocol/nips/blob/master/65.md) | Relay list metadata | ✅ | Read relays fetched on login; persisted to account; relay management UI with live status |

## NIPs with Plugins Available

*Community plugins will be listed here as they are published.*

## Common NIPs

| NIP | Name | Status | Notes |
|---|---|---|---|
| [NIP-04](https://github.com/nostr-protocol/nips/blob/master/04.md) | Encrypted direct messages (legacy) | 🔌 | Deprecated; for DMs consider [Pokey](https://github.com/KoalaSat/pokey) (notifications) or [0xchat](https://0xchat.com) (full client) |
| [NIP-05](https://github.com/nostr-protocol/nips/blob/master/05.md) | DNS-based identifiers | planned | Verification badge on profiles |
| [NIP-09](https://github.com/nostr-protocol/nips/blob/master/09.md) | Event deletion | planned | |
| [NIP-11](https://github.com/nostr-protocol/nips/blob/master/11.md) | Relay information document | planned | Relay metadata / limits |
| [NIP-17](https://github.com/nostr-protocol/nips/blob/master/17.md) | Private direct messages | 🔌 | For DMs, [0xchat](https://0xchat.com) is the recommended companion app |
| [NIP-21](https://github.com/nostr-protocol/nips/blob/master/21.md) | `nostr:` URI scheme | ✅ | Deep links from other apps; `nostr:npub1…` / `nostr:note1…` / `nostr:nevent1…` / `nostr:nprofile1…` handled |
| [NIP-25](https://github.com/nostr-protocol/nips/blob/master/25.md) | Reactions | ✅ | `+` like reactions with count; optimistic update + rollback; emoji reactions are plugin territory |
| [NIP-36](https://github.com/nostr-protocol/nips/blob/master/36.md) | Sensitive content | planned | Content warnings |
| [NIP-51](https://github.com/nostr-protocol/nips/blob/master/51.md) | Lists | 🔌 | Mute lists, pin lists, bookmarks; hashtag/custom feed lists |
| [NIP-57](https://github.com/nostr-protocol/nips/blob/master/57.md) | Lightning zaps | 🔌 | |

## Uncommon NIPs

| NIP | Name | Status | Notes |
|---|---|---|---|
| [NIP-13](https://github.com/nostr-protocol/nips/blob/master/13.md) | Proof of work | — | Anti-spam; relay-dependent |
| [NIP-23](https://github.com/nostr-protocol/nips/blob/master/23.md) | Long-form content | 🔌 | Articles / blogs |
| [NIP-40](https://github.com/nostr-protocol/nips/blob/master/40.md) | Expiration timestamp | planned | |
| [NIP-47](https://github.com/nostr-protocol/nips/blob/master/47.md) | Wallet Connect | 🔌 | NWC; pay invoices in-app |
| [NIP-50](https://github.com/nostr-protocol/nips/blob/master/50.md) | Search | 🔌 | Relay-dependent full-text search |
| [NIP-52](https://github.com/nostr-protocol/nips/blob/master/52.md) | Calendar events | 🔌 | |
| [NIP-53](https://github.com/nostr-protocol/nips/blob/master/53.md) | Live activities | 🔌 | Streams, live audio |
| [NIP-58](https://github.com/nostr-protocol/nips/blob/master/58.md) | Badges | 🔌 | |
| [NIP-59](https://github.com/nostr-protocol/nips/blob/master/59.md) | Gift wrap | planned | Used by NIP-17 DMs |
| [NIP-72](https://github.com/nostr-protocol/nips/blob/master/72.md) | Moderated communities | 🔌 | Reddit-style communities |
| [NIP-84](https://github.com/nostr-protocol/nips/blob/master/84.md) | Highlights | 🔌 | |
| [NIP-89](https://github.com/nostr-protocol/nips/blob/master/89.md) | Recommended app handlers | 🔌 | |
| [NIP-90](https://github.com/nostr-protocol/nips/blob/master/90.md) | Data vending machines | 🔌 | AI/compute marketplace |
| [NIP-94](https://github.com/nostr-protocol/nips/blob/master/94.md) | File metadata | 🔌 | |
| [NIP-96](https://github.com/nostr-protocol/nips/blob/master/96.md) | HTTP file storage | 🔌 | Image/file uploads |
| [NIP-99](https://github.com/nostr-protocol/nips/blob/master/99.md) | Classifieds | 🔌 | |
