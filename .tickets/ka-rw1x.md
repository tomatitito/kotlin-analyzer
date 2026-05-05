---
id: ka-rw1x
status: closed
deps: []
links: []
created: 2026-05-05T19:51:54Z
type: task
priority: 0
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion, metrics, observability]
---
# Add granular completion latency metrics across Rust and sidecar

Completions are still slow after ka-kyvv, and the current benchmark only reports total request latency. Add low-overhead metrics/timing spans at the main completion hot-path boundaries so we can see where time is spent for real editor and benchmark runs.

Instrument both sides of the bridge:
- Rust LSP: request receipt, document lookup/snapshot, bridge request enqueue/send, bridge response wait, response conversion/return, and whether background analysis was deferred by interactive activity.
- Sidecar request loop: queue wait if observable, dispatch start/end, completion total.
- Completion implementation: update/session freshness checks, PSI/file lookup, receiver detection, Analysis API analyze block, member candidate collection, scope candidate collection, auto-import candidate lookup, keyword/PSI fallback collection, detail/type rendering, import edit construction, ranking/scoring, JSON item assembly.

Metrics should be easy to enable during development without drowning normal logs. Prefer structured timing output with a correlation/request id so Rust and sidecar entries can be matched for one completion request.

## Acceptance Criteria

- Completion requests carry or generate a correlation id visible in Rust and sidecar timing logs.
- A single completion request produces a stage-by-stage timing breakdown for the Rust path and sidecar path when metrics are enabled.
- Metrics distinguish queue/wait time from actual sidecar computation time.
- Metrics include candidate counts for member/scope/auto-import/keyword stages and final item count.
- Metrics can be enabled via a documented flag/env var/log level and are quiet by default.
- The benchmark can optionally print or persist per-stage timing summaries, not only total latency.
- Instrumentation overhead is negligible when disabled and does not change completion results.


## Notes

**2026-05-05T21:44:42Z**

Refinement: keep this ticket fixture/local-project runnable, but design the benchmark output so ka-j4dc can point it at a real Kotlin project later without rewriting the metrics pipeline. The benchmark should support multiple completion scenarios across multiple files/features and produce a nice terminal table summary with min/avg/max timings, sample count, candidate counts, and final item counts per scenario/stage. Prefer structured raw output (JSONL or similar) plus a TUI/table renderer so before/after comparisons remain machine-readable. This ticket should not require running against a large real project, but it should make that easy.

**2026-05-05T21:46:50Z**

Terminology refinement: call this benchmark/observability surface a sensor. The user-facing goal is a future `sensors run` command with a nice terminal table/TUI experience. For this ticket, implement the completion latency work as the first sensor: fixture/local-project runnable, table summary with min/avg/max and preferably p95 over multiple scenarios/files/features, plus structured raw output for later real-project runs. See docs/sensors.md.
