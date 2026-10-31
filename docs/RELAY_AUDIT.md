# Relay ownership audit

## Problem

The personal-relay screen used `RelayPool.relayStatuses`, which represents every active connection, including searches, threads, geohash channels, and Global—not the saved account configuration. Consequently the displayed list could gain or lose entries even if the saved list had not changed. The home feed also connected to follow-derived outbox relays without releasing them. Global controls and the feed added Global relays to the same pool and did not remove them on exit. Releasing a temporary reference could disconnect a personal connection because bare additions and temporary leases had independent, incompatible lifetimes. A network kind-10002 event could populate an empty saved list without a manual sync. Global settings treated saved empty lists as missing and silently restored defaults.

## Model

- An account's saved personal list is the source for its relay-management rows and the default for regular feed connections. Legacy accounts with no configured list use the existing three-relay fallback; an explicit empty list stays empty. First account on fresh install retains the existing eleven-relay seed; no account's stored list is silently migrated or overwritten.
- Personal pool connections are owned by the active account and reconcile on account switch or edits. Transient feature connections use counted leases. A release or channel cleanup cannot tear down another owner or a configured connection. Transport changes reconnect current connections while keeping ownership.
- Global has a separately injected pool, preferences, subscriptions and lifecycle. Its enabled connections exist only while the Global feed screen is resumed. Global connections cannot receive normal unscoped personal subscriptions or personal publishes. If the same URL is also explicitly in the personal list or is used by another feature, that independent connection may remain active when Global closes.
- Automatic follow/outbox additions and automatic overwrites from published relay lists are disabled. An explicit Sync imports the published kind-10002 list; it checks for intervening edits. Explicit import/export uses the effective personal list, and replace refuses an account/list change while the confirmation was open.
- Global list and enabled subset may intentionally be empty. Defaults return only for uninitialized preferences or an explicit reset.
- Search, thread, follower-count and webxdc leases stay temporary. Geohash chat and pinned monitors acquire and release their own shared leases; NIP-46 bunker requests release their subscriptions and relay lease.

A relay can still be disconnected, reconnecting or unavailable due to its server, network or Tor. Status changes do not change the stored list. Temporary auxiliary connections can still exist while their feature is used, but they are not shown as personal configuration. Tests cover ownership overlap, switching, empty/default states, transport reset and Global-session isolation. Android device and live multi-relay behavior still require device testing.
