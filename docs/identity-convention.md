# Identity Convention

Standard Nostr clients show truncated hex `npub1aab442…`. Bony replaces this with **deterministic BIP-39 word phrases** derived from the pubkey, with graduated security:

| Context | Form | Bits |
|---|---|---|
| Feed rows, account switcher | `cinder·walnut·gosling` (3 words) | 33 |
| Profile header fingerprint pill | two rows of 3 words | 66 |
| Verify Identity screen | full 6-word box + full npub | 66 + full |

The 3-word handle is always a strict prefix of the 6-word fingerprint — they never disagree.

## Derivation

Source: `nostr/identity/Phrase.kt`

```
hash     = SHA-256(pubkey_bytes)
word[i]  = BIP39_EN[ bits[i*11 .. i*11+10] ]   for i in 0..5
avatar   = bits[66..81] → 16-cell 4×4 dot grid
```

Wordlist: `app/src/main/assets/bip39_english.txt` (2048 words, canonical BIP-39 English list).
