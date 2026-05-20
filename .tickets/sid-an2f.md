---
id: sid-an2f
status: open
deps: []
links: [ka-p741, sid-colf, ka-d4n8, ka-hexa]
created: 2026-05-20T13:40:18Z
type: bug
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion, sidecar, scheduling, cancellation]
---
# Cancel in-flight analyze when a completion arrives at dispatch

ka-p741 added sidecar-side analyze supersession at dispatch: when a completion is read in the dispatcher's lookahead window, any analyze still queued on the inbound channel is short-circuited with a synthetic response (`analyzeSupersededByCompletion`). That covers the "queued behind in-pipe analyze" case ka-p741 set out to fix and is working as designed.

It does not cover the **in-flight** case. The sidecar dispatcher is single-threaded (`AnalysisServer.kt:79-110`); once it has called `bridge.analyze(uri, version)` (`AnalysisServer.kt:331`), control does not return to `nextPendingRequest()` until the Analysis API call completes 500-950 ms later. The dispatcher cannot peek at the inbound queue during that window. There is no `ProgressManager.checkCanceled()` polling anywhere in the sidecar (grep is empty), so the Analysis API has no mechanism to yield even if a newer completion lands while it is mid-pass.

The post-sid-colf log (`.logs/kotlin-analyzer-2026-05-20T13-29-02Z.log`) shows the symptom plainly. `sid-colf`'s `receiver_detection` + `member_candidate_collection` cost is negligible (< 8 ms); the wall time the user perceives is `bridge_response_wait_ms`, which decomposes as `sidecar_queue_wait + sidecar_dispatch_total`. During typing bursts, `sidecar_queue_wait` is the dominant term:

| completion | dispatch_total | queue_wait | response_wait |
|---|---|---|---|
| completion-3  |  58 ms |  17 ms |  75 ms |
| completion-5  |  54 ms | 100 ms | 155 ms |
| completion-7  |  24 ms | 468 ms | 493 ms |
| completion-15 |  29 ms | 544 ms | 574 ms |
| completion-17 |  14 ms | 534 ms | 549 ms |

Each 500+ ms queue wait corresponds to a single in-flight analyze the dispatcher cannot interrupt. ka-p741's existing `analyzeSupersededByCompletion` counter ticks for queued analyzes (≈ 9-10 cumulative in the same trace) but cannot fire for the one in flight.

## Approach

### Phase 1 — cooperative mid-analyze cancellation in the sidecar (the real fix)

Make `bridge.analyze` cancellable mid-pass by wrapping each call in IntelliJ's `ProgressManager` infrastructure. K2 Analysis API honors `ProgressManager.checkCanceled()` at internal yield points (the same mechanism the JetBrains Kotlin K2 plugin uses to cancel FIR analysis when the editor focus changes), so a thrown `ProcessCanceledException` will unwind the analyze cleanly without leaving session state in a partial state.

- Run each analyze on a dedicated worker thread (`Executors.newSingleThreadExecutor("sidecar-analyze")` or similar) under `ProgressManager.getInstance().runProcess(body, indicator)` with a per-request `EmptyProgressIndicator`.
- Track `currentAnalyze: AtomicReference<InFlightAnalyze?>` in `AnalysisServer` holding `(uri, sidecarSeq, indicator, future)`.
- After `nextPendingRequest()` reads a new request, if it is a completion and an in-flight analyze exists, call `indicator.cancel()` and await `future` (with a tight upper bound). The analyze unwinds via `ProcessCanceledException`; the worker catches it and returns a synthetic `{"diagnostics": [], "cancelled": true}` shape Rust already tolerates (`debounced analyze superseded` branch in `server.rs:929-937`).
- The dispatcher remains single-threaded and continues to *issue* analyze and completion calls in order; only the analyze body is offloaded. Completions still run on the dispatcher thread (their cost is < 30 ms and offloading them would complicate the seq/supersession bookkeeping).

Add metric `counts.analyzeCancelledInFlight` (cumulative, mirrors `analyzeSupersededByCompletion`) emitted on every completion-metrics record.

### Phase 2 — Rust-side debouncer guard against shipping analyze behind in-flight completions (complement)

In `server.rs:837-940`, after the post-interactive quiet window expires, also defer analyze if there is a completion currently in `bridge.completion(...)`. This is symmetric to the existing `last_interactive_at` check but watches the completion bridge channel rather than the keystroke epoch. Prevents analyze from entering the pipe at all while the user is actively triggering completions; reduces the cases where Phase 1 cancellation must fire.

Track `completion_in_flight: AtomicUsize` on the bridge, incremented on `request_with_supersession` entry and decremented on exit. The debouncer reads it after the quiet window and re-schedules if non-zero (bounded by `MAX_DEFER = 5 s`, matching existing semantics).

### Why not Thread.interrupt() / process kill

`Thread.interrupt()` is unsafe — K2 FIR holds mutable session state across analysis passes and does not handle `InterruptedException` mid-pass. Killing the sidecar process per cancelled analyze is too expensive for interactive latency. `ProgressManager.checkCanceled()` is the only safe in-process cancellation mechanism and is the one K2 is built around.

## Settled decisions

- Cancellation lives in the sidecar (Phase 1). The Rust side cannot recall stdin bytes once written; ka-p741 / ka-hexa already proved this.
- Analyze runs on a dedicated worker, not on the dispatcher thread. The dispatcher must remain responsive to read the next request and decide whether to cancel.
- Cancellation triggers on the next completion read regardless of URI. Any active completion implies the user is interacting; queued analyze diagnostics are stale (same reasoning as ka-p741).
- Synthetic response shape on cancellation matches the empty-diagnostics shape Rust already handles in `debounced analyze superseded`. No protocol change.
- Phase 1 is the load-bearing fix and ships standalone. Phase 2 is additive and can land separately if Phase 1 measurements still show in-flight cancellations firing on every keystroke burst (i.e., proves the debouncer is enqueueing analyze too aggressively).

## Acceptance criteria

- Real-Zed session shows `bridge_response_wait_ms` p95 < 150 ms during a 5-second typing burst on `OutfitForFarbIdService.kt` (or any file with non-trivial completion fanout). Target: no `sidecar_queue_wait` > 200 ms in the same record set.
- New counter `counts.analyzeCancelledInFlight` is emitted on every completion-metrics record and is > 0 across sessions where the burst pattern was historically present.
- `analyzeSupersededByCompletion` (ka-p741) continues to fire for queued analyze — that path is unchanged; this ticket adds the in-flight path next to it.
- Sidecar Kotest covers: (1) analyze in flight + completion arrives → analyze yields with `ProcessCanceledException`, completion dispatches without head-of-line wait, counter increments. (2) analyze in flight, no competing completion → analyze runs to completion normally, no spurious cancellation, diagnostics published. (3) Multiple completions arrive while analyze is running → analyze is cancelled once; subsequent completions follow ka-p741's dispatch-time supersession (no double cancellation).
- No regression in `analyzeSupersededByCompletion` test coverage from ka-p741.
- Docs: `docs/lsp-protocol.md` §4.x updated with the in-flight cancellation contract; cross-link from `architecture.md`, ka-p741, ka-hexa.

## Out of scope

- Cancelling in-flight completions. Completions are short (< 30 ms after sid-colf) and already covered by Rust's `InFlight` supersession discarding the stale result. Cancelling them mid-flight would add complexity without measurable benefit.
- Multi-threaded analysis dispatcher (Analysis API single-thread constraint stands).
- Removing ka-p741's queued-analyze supersession. It catches the cheap case before any Analysis API work begins; keep as the fast path.
- Replacing the existing dispatcher event-loop architecture. Phase 1 is a localized change: one worker thread, one `AtomicReference`, one cancellation call.

## Evidence

- `.logs/kotlin-analyzer-2026-05-20T13-29-02Z.log` records `completion-7`, `completion-15`, `completion-17`: `bridge_response_wait_ms` 493 / 574 / 549 ms with `sidecar_queue_wait` 468 / 544 / 534 ms and `sidecar_dispatch_total` 24 / 29 / 14 ms. `analyzeSupersededByCompletion` cumulative 9-10 in the same window, confirming ka-p741 is firing but cannot reach the in-flight analyze.
- Code references: `sidecar/src/main/kotlin/dev/kouros/sidecar/AnalysisServer.kt:79-110` (single-threaded dispatch loop), `:325-332` (handleAnalyze blocking call), `:55-67` (existing supersession counters); `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt:42-43` (`analyze` delegate); `server/src/server.rs:837-940` (debouncer), `:2110-2185` (completion timing emission).
- Cancellation pattern reference: JetBrains Kotlin K2 plugin completion contributor uses `ProgressManager.checkCanceled()` polling inside FIR sessions for the same purpose.

## Linked

- ka-p741 [open] Sidecar-side analyze supersession when completion arrives at dispatch — caught the queued case; this ticket extends to the in-flight case
- ka-hexa [in_progress] Prioritize interactive requests over background analysis — Rust-side cancellation, complementary
- sid-colf [in_progress] Member completion on class-reference receivers — investigation that surfaced this latency
- ka-d4n8 [closed] Dangling overlay PREFER_SELF flip
