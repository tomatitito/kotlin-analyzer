---
id: ka-rd07
status: open
deps: [ka-rd03, ka-rd04, ka-rd05, ka-xjd7]
links: []
created: 2026-05-05T20:32:00Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion, decision, benchmark]
---
# Decide next architecture step after prototype and benchmark results

Use the real-project benchmark baseline and the file/session prototypes to decide whether the Kotlin-LSP-style path is sufficient for completion responsiveness or whether the project needs a larger incremental-analysis redesign.

## Acceptance Criteria

- Compare before/after completion latency for the prototype work against the real-project baseline.
- Record which latency contributors remain dominant.
- Decide whether to continue with:
  - Kotlin-LSP-style file/session lifecycle improvements;
  - targeted completion algorithm optimizations only;
  - a larger rust-analyzer-inspired incremental database design;
  - or a hybrid plan.
- Update `ka-36lk` child ticket priorities based on the decision.
- Create any missing follow-up tickets needed for the selected path.
