---
id: ka-d4n8
status: closed
deps: []
links: [ka-rd03, ka-slua, ka-p0fi, sid-colf]
created: 2026-05-20T10:43:13Z
type: bug
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [completion, sidecar, analysis-api, dangling-file]
---
# Member completion returns only `Any` members because dangling overlay uses `IGNORE_SELF`

Editor-overlay completion runs `analyzeCopy(ktFile, KaDanglingFileResolutionMode.IGNORE_SELF)` on every keystroke after the buffer diverges from the saved file (`sidecar/.../BridgeCompletion.kt:202-206` invoking the mode from `BridgeContext.kt:36-38`). The mode is wrong for full-file editor overlays: per the Analysis API contract, `IGNORE_SELF` reuses the original (on-disk) file's resolution and discards declarations contributed by the dangling copy itself. Any local variable, parameter, or top-level declaration added since the last save is invisible to reference resolution inside the dangling file.

The downstream symptom is that member-access receivers (`myVar.|`, `someFun().|`) whose definition was typed since the last save fail to resolve, the receiver expression's `expressionType` collapses to `Any`/`Any?`, and `receiverType.scope` then exposes only the three universal `Any` members (`toString`, `equals`, `hashCode`).

`PREFER_SELF` is the documented mode for "you make changes to the declaration signatures in the copied file" — which is exactly what live editing does on every keystroke. ka-rd03 explicitly left `IGNORE_SELF` behind the `COMPLETION_DANGLING_FILE_RESOLUTION_MODE` constant so the switch would be cheap once this scenario was observed in the field (ka-rd03 §"Settled decisions", `.tickets/ka-rd03.md:39, 117`).

## Evidence

`.logs/kotlin-analyzer-2026-05-20T07-33-18Z.log` — every member-completion record on a real-Zed session against `MongoOutfitLikeRepository.kt` line 74:

```
counts:{"memberCandidates":3,"finalItems":3,"sessionRebuilt":0,...}
fields:{"completionFileMode":"existing_session_source_with_editor_overlay",
        "danglingAnalysisMode":"IGNORE_SELF",
        "psiValidationBypass":"true"}
```

Across today's session the histogram of member-candidate counts is `memberCandidates:3` for every overlay record. Yesterday's `.logs/kotlin-analyzer-2026-05-19T09-22-48Z.log` shows the same pattern (`memberCandidates: 0, 1, 3` — never the dozens or hundreds you would see from a fully-resolved domain type). The pattern predates the ka-t7gp prefix-filter pushdown — with an empty prefix the new filter is `{ true }`, semantically identical to the no-arg `KaScope.callables` getter it replaced.

User-reported repro: open a Kotlin file, declare `val r = SomeRepo()`, do not save, type `r.` — completion shows only `toString`/`equals`/`hashCode`.

## Approach

Phase 1 — switch the constant.
- `sidecar/.../BridgeContext.kt:36-38`: `COMPLETION_DANGLING_FILE_RESOLUTION_MODE` → `KaDanglingFileResolutionMode.PREFER_SELF`; `COMPLETION_DANGLING_FILE_RESOLUTION_MODE_NAME` → `"PREFER_SELF"`.
- Audit every test that pins `danglingAnalysisMode` to `"IGNORE_SELF"` (e.g. ka-rd03/ka-slua coverage notes in `.tickets/ka-rd03.md:150,154,158` and `.tickets/ka-slua.md:30`) and update the expectation, or generalize to "any non-null mode."
- Add an integration test that mirrors the real-Zed scenario: open a session file, mutate the overlay text to introduce a new local declaration that does not exist on disk, run completion at a dot-member-access against that declaration, assert the candidate set contains at least one declared member beyond the three `Any` members.
- Update `docs/sidecar-file-session-lifecycle.md:226` and `docs/compiler-integration.md:103` to record the mode change and the reasoning (PREFER_SELF for full-file editor overlay; IGNORE_SELF kept only for body-only diffs, which the current code never produces).

Phase 2 (optional) — diagnostics.
- Emit a metrics counter (`metrics.counts.unresolvedReceiverFallback` or similar) when `receiverExpr.expressionType` resolves to `Any`/`Any?`/error type. Distinguishes a genuinely typed-`Any` receiver from a resolution failure; lets us catch a future regression of the same shape from metrics alone.

## Settled decisions

- Default mode flips to `PREFER_SELF` repository-wide; no env toggle. The "isolate behind a constant so we can flip cheaply" affordance from ka-rd03 was the toggle.
- Existing `psiValidationBypass=true` default is unchanged (ka-slua scope).
- Out-of-scope-types ("some types from my domain I don't get at all") is a separate symptom traced to the `symbolIndex.searchSymbols(prefix, limit = 20)` cap in `BridgeCompletion.kt:815`; file a follow-up ticket if the dangling fix alone does not resolve it for the reporter.

## Acceptance criteria

- After the change, the new integration test (overlay-only local declaration + dot completion) returns the declared member, not just the three `Any` members.
- A fresh real-Zed session against the same `MongoOutfitLikeRepository.kt` location produces a completion-metrics record with `memberCandidates >= 4` (specifically, includes the receiver type's own declared members) and `fields.danglingAnalysisMode="PREFER_SELF"`.
- No regression in the existing dangling-overlay test suite (ka-rd03/ka-slua coverage continues to pass with expectations updated for the new mode).
- No new session rebuilds, no new dangling-fallback transitions (`counts.sessionRebuilt=0`, `counts.danglingFallback=0` continue to hold).

## Out of scope

- Auto-import-limit-20 symptom in `collectUnimportedCompletionCandidates` (separate ticket, mentioned for context only).
- ka-t7gp prefix-filter pushdown is unaffected; this bug predates it.
- The legacy PSI validation bypass remains as ka-slua / ka-rd05 follow-up territory.
- LightVirtualFile fallback path keeps its current behavior.

## Evidence references

- `.logs/kotlin-analyzer-2026-05-20T07-33-18Z.log` — `completion_metrics` records 32, 33 (and every overlay record in the file): `memberCandidates=3`, `danglingAnalysisMode=IGNORE_SELF`.
- `.logs/kotlin-analyzer-2026-05-19T09-22-48Z.log` — same pattern across 21 overlay records.
- Code references: `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeContext.kt:36-38` (mode constant); `BridgeContext.kt:445-462` (dangling-file selection); `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeCompletion.kt:202-211` (analyzeCopy call site); `BridgeCompletion.kt:454-571` (`collectMemberCompletions`, where `receiverType.scope` is consulted).
- Analysis API contract: https://kotlin.github.io/analysis-api/in-memory-file-analysis.html — "PREFER_SELF" recommended when changes affect declaration signatures; "IGNORE_SELF" is an optimization for body-only diffs.
- Original mode-selection rationale: `.tickets/ka-rd03.md:38-40,116-117,127,138,142,146-158` (ka-rd03 chose `IGNORE_SELF` to mirror JetBrains' Kotlin LSP and left `PREFER_SELF` as a one-line flip for exactly this scenario).
