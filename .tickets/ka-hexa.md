---
id: ka-hexa
status: in_progress
deps: [ka-kyvv]
links: [ka-rr3u, ka-aa4k, ka-t7gp, ka-rd05, ka-p741, ser-leba]
created: 2026-05-05T15:36:27Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion, scheduling]
---
# Prioritize interactive requests over background analysis

The sidecar processes requests sequentially on a single-threaded event loop (AnalysisServer.kt:22-38). When a debounced `analyze` request (server.rs:833) is in-flight and a completion request arrives, the completion blocks behind the analyze — adding 100-500ms+ of head-of-line delay.

The Kotlin Analysis API requires single-threaded access, so parallelizing isn't viable. Instead, the Rust side should defer or cancel background analysis when an interactive request is pending:

Option A: When the completion handler fires, signal the debounce loop to skip/defer its next analyze cycle.
Option B: Have the sidecar detect that a completion/hover/definition request is queued and short-circuit the current analyze early.
Option C: Make `$/cancelRequest` functional (currently a no-op at AnalysisServer.kt:78-80) so the Rust side can cancel an in-progress analyze when a completion arrives.

This is a secondary concern after ka-kyvv (session rebuild removal) — once that's fixed, head-of-line blocking becomes noticeable but not crippling.

## Acceptance Criteria

- Interactive requests (completion, hover, definition) are not blocked behind in-flight background analysis
- Diagnostics still arrive after edits, just potentially delayed by interactive requests
- No data races or concurrent Analysis API access

## Notes

**2026-05-07T13:00Z** — Bumped P2 → P1. Real-Zed metrics (`.logs/kotlin-analyzer-2026-05-07T12-54-07Z.log`) on outfit-genie show this is the dominant remaining latency source after ka-rr3u: two captured completions on `ComplementaryItemsService.kt` line 40 sat for 1495 ms and 1120 ms in `sidecar_queue_wait`, while the actual `sidecar_dispatch_total` was only 177 ms cold / 28 ms warm. With latest-wins (ka-rr3u) firing correctly (`superseded_before_send_total=1` on both), the queueing must be from non-completion requests (background diagnostics is the prime suspect). Until interactive requests can jump that queue, the candidate-side perf wins (ka-aa4k, ka-t7gp) shave time off a number that's already small relative to the queue wait. ka-hexa attacks the user-visible bottleneck directly.
