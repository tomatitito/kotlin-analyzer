---
id: ser-leba
status: in_progress
deps: []
links: [ka-rr3u, ka-hexa, ka-aa4k, ka-t7gp]
created: 2026-05-07T14:53:42Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion, sidecar, scheduling]
---
# Sidecar-side completion supersession in the dispatcher

After ka-hexa landed, real-Zed metrics on outfit-genie show the user-visible completion latency dropped from ~1.7 s peak / ~1.4 s median to ~0.83 s peak / ~400 ms median. The bottleneck moved: it is no longer background `analyze` blocking completion (ka-hexa fixed that), it is now **completion blocking completion** in the sidecar's serial dispatcher.

The metrics make this unambiguous (see `.logs/kotlin-analyzer-2026-05-07T14-34-35Z.log`):

- `analyze_pending_cancelled_total` only fired **1 time** across 18 completions — Layer 2 of ka-hexa is rarely needed because Layer 1's cooldown keeps the bridge channel mostly free of analyze.
- `superseded_in_flight_total` reached **75** across the same 18 completions. Most completions report `cancelled=0` but `in_flight` growing by 2-9 each — meaning the older completions all reached the sidecar, were processed by the Analysis API, and only had their results discarded by Rust after the fact.
- High `sidecar_queue_wait` values (e.g. `completion-21`: queue=385 ms, `sc_total`=14 ms) consistently coincide with high `superseded_in_flight_total` and zero analyze cancellations. The thing the latest completion is queueing behind is *another completion* that the sidecar is processing serially.

ka-rr3u's writer-task `BeforeSend` supersession is correctly implemented but rarely catches anything because the writer drains the bridge channel immediately on each push. By the time a second completion arrives in the channel, the first one is already past the writer checkpoint and sitting on the sidecar's stdin. No amount of writer-side coalescing can recall a message that has already crossed the pipe — and slowing the writer would add latency to the latest completion (the one the user sees) for no benefit to it. The ka-rr3u notes acknowledged this explicitly:

> Cutting actual sidecar CPU on stale completions would require a separate sidecar-side check (correlation-id + latest tracking inside AnalysisServer dispatcher); kept out of scope here per the ticket boundary.

This ticket adds that sidecar-side check.

## Approach

Inside the sidecar's `AnalysisServer` dispatch loop, before invoking the heavy Analysis API call for a `completion` request, look up the request's supersession seq against an in-sidecar map of "latest seen seq per bucket". If the seq is already stale, respond immediately with a `superseded`-shaped result and skip the Analysis API call entirely.

This is *not* mid-flight cancellation. We never interrupt an in-progress `KaSession` call — that remains out of scope per the Kotlin Analysis API single-thread constraint. We only short-circuit completions whose results would be discarded *anyway* by the time the dispatcher would invoke the analysis. The check happens at a safe point (between requests), so the single-threaded contract is preserved.

The wire protocol does not need to change. Rust already includes the bridge supersession seq in every completion request (the field added in ka-rr3u). The sidecar starts reading and tracking it.

## Settled decisions

- **Where the check runs**: in `AnalysisServer.handleRequest`, immediately before the existing `BridgeCompletion.completion(...)` invocation. Same pattern can later extend to other request types (hover/definition) if metrics justify it; start with completion only.
- **Bucket convention**: `"<uri>"`, matching what Rust uses for completion buckets in `request_with_supersession`. Disjoint from analyze (`"analyze:<uri>"`). The sidecar's local map mirrors this convention.
- **What the sidecar does on supersession**: respond with the same JSON shape the Rust handler currently returns on `BridgeError::Superseded` (essentially "no items, supersession-marked"), so the existing Rust-side InFlight check still works without a wire change. The sidecar does not need to know what Rust will do with the response.
- **Local map maintenance**: every incoming completion request bumps the sidecar's local `latestSeenSeq[bucket]` to `max(current, incoming.seq)`. Strictly monotonic per bucket. No reset needed (same reasoning as `interactive_epoch`).
- **Composes with ka-rr3u**: Rust's `InFlight` check still runs and still reports `superseded_in_flight_total` correctly. The win from this ticket shows up as `superseded_in_flight_total` *not climbing as fast* (because the sidecar is no longer doing the wasted compute), and as a new sidecar-emitted counter (`sidecar.counts.supersededAtDispatch` or similar) inside the metrics line.
- **Composes with ka-hexa**: the analyze cancellation is unaffected. Different bucket namespace, different mechanism.

## Acceptance criteria

- A real-Zed session in outfit-genie shows `sidecar_queue_wait` p95 dropping below 300 ms (warm) for typical typing bursts. Captured into `perf/manual-evidence/2026-05-07-post-ser-leba.log` for comparison.
- The sidecar emits a new counter (e.g. `sidecar.counts.supersededAtDispatch`) on each completion-metrics record. The cumulative count is visible alongside `superseded_in_flight_total`.
- `superseded_in_flight_total` per session no longer climbs into the dozens — the wasted dispatches it counted should now be caught at the sidecar dispatcher instead. (It will not drop to zero — late races are still possible — but the ratio of `supersededAtDispatch / superseded_in_flight_total` should be > 5x.)
- Rust-side bridge tests around `request_with_supersession` continue to pass — Rust contract is unchanged.
- New sidecar Kotest covering: completion arrives with stale seq → returns superseded shape without invoking BridgeCompletion. Completion arrives with fresh seq → dispatches normally.
- `docs/lsp-protocol.md` §4.1 updated to document the sidecar-side dispatcher check as the second checkpoint after the writer-task `BeforeSend`. Cross-link from architecture.md §8.

## Out of scope

- Mid-flight Analysis API cancellation (still impossible without JetBrains exposing checkpoint hooks).
- Extending sidecar-side supersession to non-completion buckets (hover, definition, etc.). Add only if metrics justify.
- Per-bucket queue depths or backpressure changes in the bridge channel. The bridge stays as-is.
- Client-driven `$/cancelRequest` from the editor. Tracked separately.

## Linked

- ka-rr3u [closed] Make Kotlin completion latest-wins and drop stale queued requests
- ka-hexa [in_progress] Prioritize interactive requests over background analysis
- ka-aa4k [open] Implement completionItem/resolve for lazy detail computation
- ka-t7gp [open] Pass name predicates to Analysis API scope queries

## Notes

**2026-05-07T15:18:43Z**

Implementation landed. Code-side acceptance criteria met:

- Sidecar's AnalysisServer dispatch loop now runs Checkpoint 2 before
  invoking BridgeCompletion. Per-bucket latestSeenSeq is bumped at read
  time during a lookahead-drain pass, so older requests in a typing burst
  are short-circuited with reason="superseded-at-dispatch" + superseded=true.
- New cumulative counter sidecar.metrics.counts.supersededAtDispatch is
  emitted on every completion-metrics record (alongside Rust's
  superseded_in_flight_total inside the sidecar=... log field).
- Wire protocol unchanged for inbound; superseded responses add only
  additive fields (reason, superseded, metrics.counts/fields) — Rust
  ignores unknown fields and the InFlight check still composes correctly.
- 3 new JUnit5 tests in AnalysisServerTest cover stale → superseded path
  (BridgeCompletion not invoked, distinguished via no-active-session
  reason on the latest request), single-request fresh-dispatch path,
  and per-URI bucket isolation.
- Rust bridge tests (18 tests under bridge::tests) all still pass —
  Rust contract unchanged.
- docs/lsp-protocol.md §4.1 updated with Checkpoint 2 +
  three-checkpoint summary table; architecture.md §8.3 cross-links the
  new sidecar-side check.

Remaining acceptance work that requires a real Zed session against
outfit-genie (cannot be done from inside a coding session):
- Capture real-Zed sidecar_queue_wait p95 < 300 ms warm into
  perf/manual-evidence/2026-05-07-post-ser-leba.log.
- Confirm supersededAtDispatch / superseded_in_flight_total ratio > 5x.
