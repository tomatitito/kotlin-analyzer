---
id: ka-rd01
status: closed
deps: [ka-p0fi]
links: [ka-kyvv, ka-hexa]
created: 2026-05-05T20:32:00Z
type: task
priority: 0
assignee: Jens Kouros
parent: ka-36lk
tags: [docs, perf, research, architecture]
---
# Document language-server performance research and selected direction

Record the research findings from JetBrains Kotlin LSP and rust-analyzer so the performance epic has a durable technical basis. The goal is to make the next architectural tickets understandable without requiring contributors to rediscover how other language servers avoid blocking interactive requests behind project rebuilds.

## Scope

- Summarize the current kotlin-analyzer bottleneck after `ka-kyvv`: scheduling around rebuild/background work improved, but ordinary edits still interact with one mutable standalone Analysis API session and a single sidecar request lane.
- Summarize how JetBrains Kotlin LSP handles:
  - PSI/FIR state using IntelliJ platform project infrastructure;
  - LL FIR session cache storage and copied invalidation state;
  - multiple concurrent request snapshots that can keep using older cache storage safely;
  - dangling PSI for completion/edit simulation;
  - per-document diagnostics through Analysis API reads.
- Summarize how rust-analyzer handles:
  - VFS change collection;
  - incremental Salsa database updates;
  - immutable `GlobalStateSnapshot` request handling;
  - cancellation and retry for content changes;
  - latency-sensitive request scheduling;
  - background diagnostics/flycheck as separate, lower-priority work.
- Explain why kotlin-analyzer should first pursue a Kotlin-LSP-style approach:
  - Kotlin Analysis API/FIR already owns the semantic caches;
  - kotlin-analyzer does not own a Salsa-like compiler database;
  - a rust-analyzer-style database rewrite would be a larger long-term architecture project;
  - dangling/in-memory PSI plus stable module/session context fits Kotlin tooling better as the next step.
- Identify which ideas are directly applicable now, which need a spike, and which are long-term inspiration only.

## Source Pointers

Include stable links or commit-pinned references for the specific upstream code inspected, especially:

- JetBrains Kotlin LSP completion helper using `withAnalysisContextAndFileSettings`, `readAction`, `createCompletionProcess`, and `performCompletion`.
- JetBrains Kotlin LSP Kotlin completion provider using `KtPsiFactory.createFile`, `originalFile`, and `KaDanglingFileResolutionMode.IGNORE_SELF`.
- JetBrains Kotlin LSP LL FIR session cache storage and file invalidation code.
- rust-analyzer VFS crate documentation and `GlobalState::process_changes`.
- rust-analyzer request dispatcher for snapshots, latency-sensitive work, cancellation, and retry.
- rust-analyzer main-loop request classification for completion, semantic tokens, diagnostics, and hover.

## Recommended Conclusion

The doc should explicitly state the proposed near-term strategy:

```text
ordinary edit
    -> keep stable project/session context
    -> answer interactive request from current in-memory/dangling PSI
    -> avoid making FIR see duplicate real files
    -> defer/cancel background diagnostics when needed
    -> rebuild session only for structural/project-model changes
```

Also state the open question for `ka-rd04`: whether standalone Analysis API exposes enough FIR/session invalidation to update diagnostics and non-interactive semantic state without rebuilding.

## Acceptance Criteria

- A doc section or new doc captures the Kotlin LSP and rust-analyzer comparison with source links.
- The doc states the selected near-term direction: stable project/session context, in-memory/dangling PSI for interactive requests, narrow invalidation where possible, and full rebuilds only for structural/project changes.
- The doc distinguishes "directly applicable now" from "requires spike" and "long-term inspiration".
- The doc explains why rust-analyzer's snapshot/Salsa model is not the immediate implementation target for kotlin-analyzer.
- The doc identifies the remaining research question for FIR/session cache invalidation in standalone Analysis API.
- `docs/INDEX.md` is updated if a new document is created.
- Follow-up tickets in `ka-36lk` can link to this doc as the rationale for the file/session model redesign.
