# Plugin System

Bony is intentionally minimal. Extended functionality is delivered via plugins — separate APKs that implement a defined AIDL interface. The host app binds to plugin services; Android enforces process isolation.

Some features that are candidates for plugins rather than core:
- Emoji reactions beyond `+` (NIP-25 extended), zaps (NIP-57)
- DMs — NIP-04 legacy and NIP-17 private DMs
- Long-form content (NIP-23)
- Hashtag feeds and custom NIP-51 feed lists
- I2P / custom proxy transports (core exposes a transport abstraction; Tor/Orbot is the built-in implementation)
- Image/video upload (NIP-96)

## Plugin Permissions

Plugins declare only what they need:

| Permission | Description |
|---|---|
| `READ_EVENTS` | Read cached events from the feed |
| `READ_PROFILE` | Access contact/profile metadata |
| `INJECT_UI` | Render UI into defined host slots |
| `PUBLISH_EVENT` | Request to publish an event (proxied through the app's signer — plugin never sees keys) |
| `READ_DMs` | Access decrypted DMs (high-trust, explicit user grant required) |

## Plugin Registry

Plugins are discovered through an in-app registry. Registry design is in progress.

## Security Model

- Plugins **never** receive private keys or signed events
- `PUBLISH_EVENT` submits an unsigned template → app signer signs → app broadcasts
- Plugin identity is verified by package name + signing certificate
- Unverified (sideloaded) plugins trigger a prominent warning
- UI-injecting (WebView) plugins run with strict Content-Security-Policy, no shared storage
