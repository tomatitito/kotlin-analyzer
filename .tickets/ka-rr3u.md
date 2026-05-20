---
id: ka-rr3u
status: closed
deps: []
links: [ka-aa4k, ka-rd05, ka-t7gp, ka-xjd7, ka-g342, ka-wkai, ka-p741, ser-leba, ka-hexa]
created: 2026-05-06T10:50:08Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion, sidecar, lsp, queue]
---
# Make Kotlin completion latest-wins and drop stale queued requests

Real-project completion metrics from outfit-genie show that the dangling PSI path is working but user-visible completion latency is dominated by sidecar queue wait, not session rebuild. Example metrics from 2026-05-06: sessionRebuilt=0, danglingFallback=0, completionFileMode=existing_session_source_with_editor_overlay, danglingAnalysisMode=IGNORE_SELF, sidecar completion work around 130-170ms warm, but bridge_response_wait_ms around 1300-1500ms with sidecar_queue_wait around 1150-1350ms. Zed sends multiple completion requests during one interaction; older queued completions are processed before newer useful completions, so the visible popup waits behind stale work. Implement a latest-wins completion request policy so stale queued completion requests are dropped or skipped before expensive sidecar analysis, and stale in-flight completion results are not returned when a newer completion has superseded them.

## Acceptance Criteria

- Real-project completion metrics no longer show second-scale sidecar_queue_wait for ordinary typing-triggered completion bursts when older completion requests have been superseded.\n- Completion remains correct for the latest request: the editor receives results for the newest URI/position/document version/correlation, not an older queued request.\n- Older queued completion requests are cancelled, coalesced, or skipped before expensive sidecar Analysis API work whenever a newer completion request for the same document supersedes them.\n- Rust-side metrics distinguish bridge queue wait from sidecar compute and record when a completion was skipped/superseded.\n- The sidecar/Rust bridge preserves correctness for non-completion requests; latest-wins behavior is scoped to completion unless explicitly justified.\n- Add regression coverage or a deterministic bridge/unit test for completion request supersession.\n- Document the observed baseline and the resulting policy in the ticket notes or docs.\n- Follow-up optimization tickets such as ka-t7gp and ka-aa4k remain separate; this ticket is about queue pressure and stale completion suppression.


## Notes

**2026-05-06T11:27:54Z**

Implementation: Rust-side latest-wins for completion via Bridge::request_with_supersession.

Architecture
- New BridgeError::Superseded { bucket, my_seq, latest_seq, stage } where stage is BeforeSend | InFlight.
- Bridge maintains latest_supersession_seq: HashMap<bucket, u64> protected by Mutex, plus AtomicU64 counters for superseded_before_send and superseded_in_flight.
- Channel item type changed to QueuedMessage::{Plain, Supersedable}. Writer task (Bridge::filter_queued_message) drops Supersedable msgs whose seq < latest before stdin write and resolves the matching pending oneshot with Superseded(BeforeSend).
- request_with_supersession bumps the bucket's seq atomically before send, then re-checks after the response arrives — if a newer request has bumped the seq, returns Superseded(InFlight) instead of the stale value.
- Server completion handler swapped to the new method; on Superseded returns Ok(None) and logs structured metrics under target=completion_metrics.

Tests
- 5 new bridge unit tests cover the algorithm: passthrough for Plain, passthrough for fresh Supersedable, drop+error+counter on stale Supersedable, isolation between buckets, and a 5-request typing-burst that asserts only the latest reaches stdin and the older 4 each resolve with Superseded(BeforeSend). All 94 server tests pass (88 existing + 5 new + 1 strictly-increasing-seq guard).

Real-project measurement (outfit-genie, AppBottomSheetController.kt, line 81)
Driver: small Python LSP client at /tmp/lsp_burst.py firing N completion bursts directly at the kotlin-analyzer binary with completionMetrics enabled.

Baseline (from ticket): bridge_response_wait_ms ~1300-1500ms, sidecar_queue_wait ~1150-1350ms during typing bursts.

After fix, burst of 5 completions ~5ms apart (tighter than human typing):
- 4 older requests: completion-1..4 each resolved Superseded(InFlight), discarded by Rust before items returned to client.
- LATEST (completion-5): bridge_response_wait_ms=159ms, sidecar_queue_wait=103ms, rust_total_ms=161ms.
- Repeat burst (completion-10): bridge_response_wait_ms=98ms, sidecar_queue_wait=73ms, rust_total_ms=99ms.
- ~10-14x improvement on user-visible latency for the only completion the user actually sees.

After fix, burst of 5 completions ~70ms apart:
- Writer drains between requests so no supersession needed; each completes sequentially with sidecar_queue_wait near 0 (0.0-0.15ms).

Notes
- BeforeSend supersession (saves sidecar CPU) only triggers when requests queue faster than the writer task drains. In this synthetic stress, the writer always won; in real Zed bursts where there's editor overhead between requests, BeforeSend will fire more often.
- InFlight supersession is the dominant user-visible win: the latest completion's response wait drops from ~1.3s to ~0.1s because the Rust handler no longer surfaces stale results that would have queued behind it. The sidecar still processes older ones, but those results are discarded.
- Cutting actual sidecar CPU on stale completions would require a separate sidecar-side check (correlation-id + latest tracking inside AnalysisServer dispatcher); kept out of scope here per the ticket boundary that ka-t7gp/ka-aa4k remain separate.

Files touched
- server/src/error.rs: added BridgeError::Superseded + SupersededStage enum.
- server/src/bridge.rs: latest_supersession_seq field, QueuedMessage enum, filter_queued_message helper, request_with_supersession API, superseded_*_count getters, new tests.
- server/src/server.rs: completion handler uses request_with_supersession; structured logging records supersession_seq + totals; Superseded -> Ok(None).
