# Diagnostics and what they disclose

This page exists so that "share logs" is not a leap of faith. It describes exactly
what leaves the app when a user exports diagnostics, what does not, and where the
gaps are.

## Two independent things sit in one report

`Share logs` produces one text file with two parts:

1. **Crash and log content** — the tail of the in-app log file. Controlled by the
   `crashes only` toggle.
2. **A performance block** — startup and feed timings, relay counts, verification
   counts, and a plain-English verdict. Controlled by its own `performance`
   toggle, which is independent of `crashes only`.

Turn the performance block on with crash content off and you get numbers only.
Turn it off and the report looks exactly as it did before this existed. It is
never folded into the crash section, and the export filename reflects it:
`tbone-diagnostics.txt` (performance included) versus `tbone-crashes.txt` /
`tbone-logs.txt`.

## What the performance block contains

Only counts, durations, and fixed labels:

- process launches, feed loads, first-event successes, timeouts
- relay count, active subscriptions, events seen, events displaced
- full verifications versus cache hits
- per-sample label (`first event`, `timed out`, `metadata EOSE`), elapsed
  milliseconds, and a relative age (`12s ago`)

It contains **no** note content, **no** pubkey, **no** event id, **no** relay URL,
**no** file path, and **no** absolute timestamps. `StartupMetricsTest` fails the
build if any of those appear.

Two consequences worth knowing:

- Everything is in-memory. Nothing is written to disk, so nothing survives a
  restart and there is no history to leak.
- Nothing is transmitted. There is no HTTP client, socket or analytics call in the
  metrics class. The only way it leaves the device is the Android share sheet,
  driven by the user.

A report with performance **on** therefore discloses strictly *less* about the
user than the same report with it **off**.

## What the log section contains — read this before sharing

The log file is written in release builds too (filtered to `INFO` and above).
Relay failures are logged at `WARN`, and those lines carry the relay URL:

```
Failure on wss://relay.example.com/your-private-path: ...
Relay disconnected (wss://...) ...
Relay ... entered 30s cooldown after repeated rapid failures
AUTH from wss://...: no active account
```

A privately hosted relay URL may be effectively a credential — it identifies the
user and can be replayed. Some lines also carry 8-character prefixes of pubkeys or
event ids, which are stable enough to link log lines together.

The app warns about this in the share dialog ("may include relay URLs, short key
prefixes & crash details"). The recommended hardening, not yet applied, is to
redact relay URLs to their host and drop the short key prefixes inside
`FileLoggingTree`, in one place, rather than at every call site.

Until that lands: **read a report before sending it**, and prefer the
performance-only export when the question is "why is startup slow" rather than
"what crashed".

## Other properties

- `android:allowBackup="false"` — no cloud backup of any of this.
- The duress PIN path (`DataWiper`) clears `filesDir` (which includes `logs/` and
  any exported copies) and `cacheDir`, and only restarts into clean onboarding if
  every wipe step succeeded.
- No new permissions and no new dependencies are involved in any of the above,
  and `scripts/privacy-check.sh` runs on every build to keep it that way.
