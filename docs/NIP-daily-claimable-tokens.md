# NIP-XX: Daily Claimable Tokens

`draft` `optional`

## Abstract

This NIP defines lightweight, text-only, peer-to-peer **Daily Claimable Tokens** on Nostr (for example, `steak`).

- Any pubkey may send **one** token of a given type every **24 hours** (rolling cooldown) to any other pubkey.
- The recipient must **claim** the token within **24 hours** of when it was sent, or it expires unclaimed and does not count.
- Accepted tokens are listed on the recipient's **Profile Token Ledger** (analogous to NIP-58 Profile Badges) so clients can display verified token counts on profiles and build decentralized leaderboards.
- Tokens are **text-only** (`content` is empty; no hosted image URLs). All visual artwork is rendered client-side.
- Lightweight **NIP-13 Proof-of-Work** (`16` bits) on Offer and Claim events deters automated Sybil farming without requiring any central server.

## Event Kinds

| Kind    | Type        | Name                 | Author    | Purpose |
| ------- | ----------- | -------------------- | --------- | ------- |
| `8701`  | Regular     | Token Offer          | Sender    | Immutable proof that Sender offered a token to Recipient at `created_at` |
| `8702`  | Regular     | Token Claim          | Recipient | Immutable proof that Recipient accepted the Offer within 24 hours |
| `38701` | Addressable | Profile Token Ledger | Recipient | Replaceable profile showcase & fast index of accepted `(claim, offer)` pairs |

> **Why three kinds?**
> Because `38701` is replaceable, its `created_at` updates every time a new token is added. Storing the acceptance as an immutable `8702` event preserves the frozen acceptance timestamp for every historical token while keeping profile and leaderboard discovery to a single relay query against `38701`.

## Token Identifiers (`slug`)

A token type is identified by a lowercase ASCII slug matching:

```
^[a-z0-9][a-z0-9-]{0,31}$
```

Examples: `steak` (reference token), `coffee`, `crown`.

- Token identifiers carry **no image URLs** and **no on-relay media metadata**.
- Clients render their own local artwork (vector icon, custom illustration, or theme-adapted badge) for known token slugs.
- Clients MAY ignore unrecognized token slugs or display a generic text fallback.

---

## Event Specifications

### 1. Token Offer (`kind: 8701`)

A regular (immutable) event published by the sender to offer one token to a recipient.

**Required fields & tags:**
- `content`: MUST be `""` (empty string).
- `["t", "<slug>"]`: Token identifier (single-letter tag for NIP-01 relay indexing).
- `["p", "<recipient_pubkey>", "<optional_relay_hint>"]`: Recipient's 64-character lowercase hex pubkey. MUST NOT equal `event.pubkey`.
- `["nonce", "<nonce>", "<target_difficulty>"]`: NIP-13 Proof-of-Work with committed target difficulty `>= 16` and actual `event.id` leading zero bits `>= 16`.

**Forbidden tags:**
- MUST NOT include a NIP-40 `expiration` tag. Relays must be allowed to retain the Offer as permanent cryptographic proof once claimed.

**Example:**
```json
{
  "id": "00008a3f...<64-char hex with >=16 leading zero bits>",
  "pubkey": "<alice_hex_pubkey>",
  "created_at": 1794800000,
  "kind": 8701,
  "tags": [
    ["t", "steak"],
    ["p", "<bob_hex_pubkey>", "wss://relay.example.com"],
    ["nonce", "48219", "16"]
  ],
  "content": "",
  "sig": "<schnorr_sig>"
}
```

---

### 2. Token Claim (`kind: 8702`)

A regular (immutable) event published by the recipient to accept a specific Token Offer within 24 hours.

**Required fields & tags:**
- `content`: MUST be `""` (empty string).
- `["t", "<slug>"]`: MUST match the referenced Offer's `t` tag.
- `["e", "<offer_event_id>", "<optional_relay_hint>"]`: Event ID of the `kind: 8701` Offer being claimed.
- `["p", "<sender_pubkey>", "<optional_relay_hint>"]`: MUST match `offer.pubkey`.
- `["nonce", "<nonce>", "<target_difficulty>"]`: NIP-13 Proof-of-Work with committed target difficulty `>= 16` and actual `event.id` leading zero bits `>= 16`.

**Timestamp rule:**
- When constructing a Claim, clients MUST set:
  ```
  claim.created_at = max(current_unix_time, offer.created_at)
  ```
  so minor device clock skew never causes an instant claim to precede `offer.created_at`.
- Clients MUST refuse to create a Claim if `current_unix_time > offer.created_at + 86400`.

**Example:**
```json
{
  "id": "00003c91...<64-char hex with >=16 leading zero bits>",
  "pubkey": "<bob_hex_pubkey>",
  "created_at": 1794803600,
  "kind": 8702,
  "tags": [
    ["t", "steak"],
    ["e", "<offer_event_id>", "wss://relay.example.com"],
    ["p", "<alice_hex_pubkey>"],
    ["nonce", "71904", "16"]
  ],
  "content": "",
  "sig": "<schnorr_sig>"
}
```

---

### 3. Profile Token Ledger (`kind: 38701`)

An addressable replaceable event published by the recipient listing the tokens they choose to display on their profile.

**Required fields & tags:**
- `content`: MUST be `""` (empty string).
- `["d", "<slug>"]`: Token identifier (e.g., `["d", "steak"]`).
- Zero or more `["claim", "<claim_event_id>", "<offer_event_id>", "<optional_relay_hint>"]` tags, one per accepted token.
- Optional overflow shard pointers (see [Scaling Beyond 500 Tokens](#scaling-beyond-500-tokens)):
  `["a", "38701:<recipient_pubkey>:<slug>:shard:<N>", "<optional_relay_hint>"]`

**Notes:**
- NIP-13 Proof-of-Work is **not** required on `kind: 38701`. The ledger is purely an opt-in showcase and index; verifying clients MUST derive a user's token count solely from valid referenced `(8701, 8702)` pairs, never from an unverified count tag or tag length alone.
- Just like NIP-58 Profile Badges, a user has full control over their profile: ignoring an Offer leaves it unclaimed, and removing a `claim` tag from `38701` hides it from their profile ledger.

**Example:**
```json
{
  "id": "<event_id>",
  "pubkey": "<bob_hex_pubkey>",
  "created_at": 1794803602,
  "kind": 38701,
  "tags": [
    ["d", "steak"],
    ["claim", "<claim_event_id_1>", "<offer_event_id_1>", "wss://relay.example.com"],
    ["claim", "<claim_event_id_2>", "<offer_event_id_2>", "wss://relay.example.com"]
  ],
  "content": "",
  "sig": "<schnorr_sig>"
}
```

---

## Verification Rules

Clients MUST NOT trust raw `claim` tag counts on a `kind: 38701` event without verifying the underlying `kind: 8701` Offer and `kind: 8702` Claim events. Once an `(offer_id, claim_id)` pair passes verification, clients MAY cache the verified result locally.

### Structural & Cryptographic Checks
For a `(claim, offer)` pair belonging to recipient `R` and token `<slug>` to be valid:

1. Both `offer` (`kind: 8701`) and `claim` (`kind: 8702`) have valid NIP-01 `id` hashes and valid Schnorr signatures.
2. Both `offer.content` and `claim.content` are `""`.
3. Both events carry `["t", "<slug>"]` matching the ledger's `<slug>`.
4. `offer` has a single `p` tag equal to `R`, and `offer.pubkey != R` (self-sending is invalid).
5. `claim.pubkey == R`, `claim` has `["e", offer.id]` and `["p", offer.pubkey]`.
6. Both `offer` and `claim` pass NIP-13 PoW verification:
   - `nonce` tag present with integer target `>= 16`.
   - Leading zero bits of `id` `>= 16`.
7. **Future timestamp sanity check:** Ignore offers with `offer.created_at > now + 300` (5-minute future clock skew cap).

### 24-Hour Claim Window
8. `offer.created_at <= claim.created_at <= offer.created_at + 86400`.

### One Claim Per Offer
9. Each `offer.id` may be counted **at most once**. If multiple valid `8702` Claims exist for the same `offer.id`, only the earliest `(created_at ASC, id ASC)` is canonical. Duplicate `claim` or `offer` references in a `38701` ledger MUST be deduplicated by `offer.id`.

### Rolling 24-Hour Sender Cooldown (86,400 seconds)
10. A sender `S` may only issue **one** valid Offer of token `<slug>` per rolling 24-hour window (to any recipient; sending to the same recipient again after 24 hours is allowed).
11. To resolve cooldown deterministically across clients:
    - Collect all structurally valid `kind: 8701` Offers by `(S, <slug>)` and sort ascending by `(created_at ASC, id ASC)`.
    - Initialize `last_valid_at = null`.
    - For each offer in order:
      - If `last_valid_at == null` OR `offer.created_at - last_valid_at >= 86400`, mark the offer **cooldown-valid** and set `last_valid_at = offer.created_at`.
      - Otherwise, mark the offer **invalid** (cooldown violation).
    - **Unclaimed offers still consume the sender's cooldown.** This prevents senders from broadcasting multiple offers hoping one is accepted.

> **Verification Depth (Fast vs. Full):**
> At minimum, clients MUST enforce rule 11 across all offers observed in a profile or leaderboard verification batch (preventing a sender from awarding multiple tokens to the same recipient within 24 hours). For higher assurance on profile detail views and top leaderboard rows, clients SHOULD additionally query each sender's recent `kind: 8701` offers around `[offer.created_at - 86400, offer.created_at]` on connected relays.

---

## Standard Relay Queries

All primary queries use single-letter NIP-01 indexed tags (`#p`, `#t`, `#e`, `#d`):

- **Check incoming claimable offers (last 24h) for `bob`:**
  ```json
  ["REQ", "sub", {"kinds": [8701], "#p": ["<bob>"], "#t": ["steak"], "since": "<now - 86400>"}]
  ```
- **Check whether `alice` is on cooldown:**
  ```json
  ["REQ", "sub", {"kinds": [8701], "authors": ["<alice>"], "#t": ["steak"], "since": "<now - 86400>"}]
  ```
- **Check whether `bob` already claimed a set of offers:**
  ```json
  ["REQ", "sub", {"kinds": [8702], "authors": ["<bob>"], "#e": ["<offer_id_1>", "<offer_id_2>"]}]
  ```
- **Fetch `bob`'s Profile Token Ledger:**
  ```json
  ["REQ", "sub", {"kinds": [38701], "authors": ["<bob>"], "#d": ["steak"]}]
  ```
- **Batch-verify all referenced Offers and Claims in one request:**
  ```json
  ["REQ", "sub", {"ids": ["<claim_id_1>", "<offer_id_1>", "<claim_id_2>", "<offer_id_2>"]}]
  ```
- **Build a Follows Leaderboard:**
  ```json
  ["REQ", "sub", {"kinds": [38701], "authors": ["<follow_1>", "<follow_2>", "..."], "#d": ["steak"]}]
  ```
- **Build a Global Leaderboard (from connected relays):**
  ```json
  ["REQ", "sub", {"kinds": [38701], "#d": ["steak"], "limit": 500}]
  ```

---

## Scaling Beyond 500 Tokens

A single `kind: 38701` event comfortably stores up to **500** `claim` tags (~75 KB, comparable to a standard kind-3 follow list).

When a user's primary ledger (`d = "<slug>"`) exceeds 500 verified claims, clients SHOULD archive the oldest 500 `claim` tags into an overflow shard event:
- `kind: 38701`
- `["d", "<slug>:shard:<N>"]` where `N` starts at `1` and increments sequentially (`1`, `2`, ...).
- The primary ledger (`d = "<slug>"`) keeps `["a", "38701:<pubkey>:<slug>:shard:<N>"]` pointers to every shard plus the newest claims (`1..500`).

Verifying clients follow `a` tags matching `^38701:<ledger.pubkey>:<slug>:shard:[1-9][0-9]*$` and union all verified claims across primary + shards.

---

## Client Interoperability & Signer Notes

- **NIP-13 PoW with External Signers (NIP-55 Amber / NIP-46 Bunker):**
  Because `event.id` does not depend on `sig`, clients mine the `nonce` locally before sending the unsigned event to the signer. Clients that append optional tags (such as NIP-89 `["client", ...]`) MUST finalize all tags **before** mining the nonce (or omit client tags on PoW-mined events) so the final `id` retains its leading zero bits.
- **Accept Flow Resilience:**
  Accepting a token publishes `kind: 8702` first, then updates `kind: 38701`. If publishing `kind: 38701` fails or is canceled, the immutable `kind: 8702` Claim is already preserved on relays, and the client can safely sync it into `kind: 38701` on the next refresh.

---

## Security & Privacy Considerations

- **Public by Design:** Offers, Claims, and Profile Ledgers are public Nostr events visible on relays.
- **Sybil Resistance:** Requiring 16-bit NIP-13 PoW raises the cost of automated bulk farming without requiring central servers, and defaulting leaderboards to the user's **Follows / Web-of-Trust** naturally excludes unfollowed burner accounts.
- **Timestamp Honesty:** In pure peer-to-peer Nostr without a trusted timestamp oracle, a modified client could attempt to backdate `claim.created_at` on relays that accept historical timestamps. Relays wishing to enforce strict real-time claim windows MAY reject `kind: 8701` and `kind: 8702` events whose `created_at` deviates from relay wall-clock time by more than ±5 minutes.
