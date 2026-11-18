# Intermittent note-loading investigation

**Scope:** investigation and recommendations only. No note-loading code was changed as part of this report.  
**Evidence reviewed:** current `ThreadViewModel`, `ThreadTree`, `FeedViewModel`, and `EventRepository` source, plus the tests and relevant Git history. This checkout contains no device logs, relay traces, or reproducible failing event IDs, so conclusions below are code-path findings and ranked hypotheses—not a confirmed incident root cause.

## Summary

The symptoms fit a distributed retrieval problem more than one universal rendering defect: a note can be present on one relay and absent from others, while the app does direct-ID fetches only against currently connected relays first and then a capped fallback set. A reply thread adds serial parent/ancestor lookups, each bounded by timeouts; any missing hop can leave an incomplete but still renderable tree. The main feed has a separate limitation: it intentionally filters reply notes out, so a reply is not expected to appear as a top-level Home/Global item; replies are fetched by the thread screen.

The code contains resiliency measures—local memory/Room cache, signature verification, relay hints behind a setting, optional bridge relays, default-relay fallback, EOSE handling, and a load watchdog—but the UI does not expose enough per-request diagnostics to distinguish: (1) event genuinely unavailable on the selected relays, (2) relay connection/timeout/EOSE race, (3) unsupported or absent relay hint, (4) verification/tag parsing rejection, or (5) a display/filtering issue.

## Findings by path

### Reply threads

- Thread loading first asks `EventRepository.getById` (RAM cache, then Room) and otherwise issues a `Filter(ids=[id])` lookup.
- The initial lookup waits for the event or a count of EOSE responses, with a 6-second cap. The expected EOSE count is calculated from the relay-status snapshot when the wait predicate runs; it is not a frozen per-request relay set. Missing initial results trigger an optional fallback over sanitized event hints / in-memory hints, optional bridge relays, and default relays, capped at six. Fallback has another bounded timeout (longer when Tor is enabled).
- Ancestors are resolved **serially**, one parent at a time, up to 32 hops. Each missing ancestor can consume a first-stage timeout and a fallback timeout before the next ancestor is attempted. This is safe from indefinite hangs, but a slow chain can take a long time and any unavailable parent terminates the walk (with a root retry where possible).
- Replies are subscribed for the resolved root/path IDs. Events from that subscription are signature-verified and then admitted only if their parsed parent/root IDs match a known thread event/root. A valid event that has unusual/missing relationship tags, is not parsed as related, or has not yet arrived as the thread ancestry expands can be omitted from the visible tree.
- `ThreadTree` deliberately reparents replies whose parent is absent to the root, and shows highest available notes even without the declared root. Therefore the tree model itself has a fallback against silently dropping otherwise-known replies; it cannot recover an event never delivered to the app.
- There is a final warning if the focused note is not found, but the source reviewed does not provide structured per-relay outcomes for each lookup (which relays were queried, connected, answered EOSE/CLOSED, delivered an event, or timed out). That makes intermittent production failures hard to attribute.

### Main feed

- Home feed content is sourced from followed-author filters (or an own-note bootstrap while the follow list is unknown), with cached notes loaded separately. The shared load watchdog eventually ends the spinner, but it does not prove every configured relay responded or that the relay set contained the author’s event.
- `FeedViewModel.isReplyEvent` removes text notes with multiple `e` tags or `root`/`reply` markers from Home/Global feed insertion. This is intentional top-level-feed behavior; replies should appear after opening the conversation, not as standalone feed rows. It can explain the “main feed” symptom if the missing item is specifically a reply.
- Main-feed event handling verifies signatures and accepts only supported feed kinds. Events not matching the active author/kind filters, replies intentionally filtered out, invalid signatures, muted/filtered items, or events arriving only from non-selected/offline relays will not show as ordinary rows.
- New Home events are write-behind persisted. They are also placed in memory immediately, so batching is not a likely cause of an in-session miss; a process termination before the flush could affect a later cold start, while Room/cache errors are logged separately.

## Most plausible causes (ranked, not yet proven)

1. **Relay coverage / replication variance.** The event or an ancestor is not replicated to the connected relays queried first; the relevant relay hint is absent, disabled, rejected by hint policy, or outside the six-relay fallback cap.
2. **Bounded wait / slow relay behavior.** A relay that is connecting, slow, silent, or reconnecting misses the finite lookup window. EOSE is the current completion signal, but the code does not retain a clear user-visible “timed out after partial responses” state.
3. **Long serial ancestor chain.** A thread with several unavailable/slow parents compounds the request waits. It can feel like notes failed to load even if some pieces eventually resolve.
4. **Reply classification or tag-shape mismatch.** Main feed intentionally suppresses replies; thread membership uses parsed `replyEventId` / `threadRootEventId`. Nonstandard or malformed tags may not be recognized consistently.
5. **Content filtering / UI interpretation.** Mute, NSFW, hide-word, or reference-graph rules can hide a note; main-feed replies are excluded by design. These are distinguishable from network absence only if the UI/report records the reason.
6. **Cache/persistence edge case.** Lower likelihood from source inspection: in-memory cache is populated immediately; Room writes are batched and failures logged. Could contribute on abrupt process exit or storage errors, but it does not explain a note missing on first retrieval.

## Recommended ways to address it

### Option A — Instrument first (recommended, no behavior change)

Add privacy-safe, sampled diagnostics for single-note and feed requests: opaque/truncated event ID, request stage, filter type, selected relay count, connection state snapshot, event received/verified/rejected, EOSE/CLOSED per relay, timeout duration, whether cache/Room hit, hint/fallback stage, and final outcome. Record parsed rejection/filter reason, never note content or secret material. Add a user-triggered “copy loading diagnostics” action for a failed thread. This will turn the hypotheses above into an attributable failure rate before changing timeouts or dialing more relays.

### Option B — Improve bounded retrieval after instrumentation

Freeze the expected relay set at subscription creation; track EOSE against that exact set; treat CLOSED/disconnect as a terminal response for that relay; and report partial results distinctly from full completion. Use a modest, configurable grace period / retry for timed-out lookups, with cancelation and deduplication. For ancestor chains, batch known parent IDs where possible or use bounded concurrency while preserving root-to-focused presentation. Keep retries capped and relay-hint policy unchanged unless the user opts in.

### Option C — Improve explanation and recovery

Keep successfully fetched notes visible, mark only the missing parent/reply as “not found on queried relays,” and add a per-ID retry that reuses existing safe hint/fallback policy. Differentiate “still loading,” “not available from queried relays,” and “hidden by your filters.” This improves user trust without promising that a relay network can supply events it does not have.

### Option D — Broaden relay coverage

Consider additional trusted/default relays or opt-in hint/bridge behavior only after measuring coverage, latency, Tor impact, privacy implications, and relay-policy compliance. Broadening every lookup indiscriminately increases network disclosure and connection load; avoid an unbounded “try all relays” fix.

## Suggested validation

1. Reproduce with a known event ID and preserve its tag structure (including `e`, `E`, `q`, and relay hints) plus timestamps; capture the selected relay list and app version.
2. Test cache hit, connected relay hit, EOSE-with-no-event, silent relay timeout, CLOSED, disconnect/reconnect, invalid signature, hint-only hit, fallback-only hit, and missing-parent cases.
3. Test a multi-hop reply with each ancestor on a different relay, and a valid reply whose parent/root is not yet cached.
4. Confirm a reply omitted from Home is accessible in its thread; separately validate ordinary top-level notes and polls in Home/Global.
5. Add unit tests for reply-tag classification and integration tests with deterministic fake relays before changing production timeout or fallback policy.

## Bottom line

Source review identifies plausible relay-availability and bounded-lookup causes, plus an intentional main-feed rule that hides replies. It does **not** establish a single confirmed root cause. The best next step is privacy-safe per-relay telemetry and a reproducible failing event capture; then choose between targeted retry/timeout improvements, clearer unavailable-state UX, or relay-coverage changes based on measured evidence.