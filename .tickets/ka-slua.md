---
id: ka-slua
status: closed
deps: [ka-rd03]
links: [ka-rd05, ka-rd06]
created: 2026-05-06T05:56:35Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion, sidecar, psi, validation]
---
# Spike PSI validation bypass removal for dangling completion

Determine whether the ka-rd03 dangling completion path can run with Kotlin Analysis API PSI input validation enabled, and whether the existing global PSI validation bypass can be removed or narrowed without regressing editor-text semantic behavior.


## Notes

**2026-05-06T05:56:46Z**

Scope proposal: add a controlled sidecar mode that leaves Kotlin Analysis API PSI input validation enabled, then run dangling completion and existing editor-text semantic tests to determine which paths still depend on the bypass. Acceptance: document whether dangling completion works without the bypass; identify fallback/hover/signature/diagnostics dependencies if any; either remove/narrow the bypass or leave a documented follow-up if non-completion paths still require it; include direct regression coverage for completion with validation enabled.

**2026-05-06T07:21:13Z**

Rationale to evaluate: disabling PSI validation is intentionally a temporary/legacy workaround, not a desired steady state. Keep it only if a path still needs useful-but-not-fully-legal standalone Analysis API PSI: LightVirtualFile fallback after dangling failure, non-completion features that still analyze in-memory PSI, new/virtual files without a stable original session file, or standalone-sidecar gaps compared with IntelliJ VFS/module infrastructure. The spike should distinguish these cases from real stale/duplicate PSI mixing bugs. Preferred outcome is not a permanent global bypass: either prove dangling completion works with validation enabled and remove/narrow the bypass, or explicitly scope it to fallback/legacy paths with metrics/logging that show when it is used.

**2026-05-06T07:32:37Z**

Implemented controlled PSI validation bypass mode via kotlin.analyzer.psi.validation.bypass / KOTLIN_ANALYZER_PSI_VALIDATION_BYPASS. Dangling completion for existing session-file editor overlays passes with the bypass disabled, uses analyzeCopy(IGNORE_SELF), does not rebuild, and does not fall back. Legacy bypass remains enabled by default for non-completion and LightVirtualFile fallback paths pending ka-rd05/ka-rd06 lifecycle work. Verified with sidecar ./gradlew test.

**2026-05-06T08:04:19Z**

Clarification after running spike/analysis-api in tmux: that spike is the original standalone Analysis API viability check, not a direct ka-slua PSI-validation/dangling-completion test. It builds StandaloneAnalysisAPISession instances and verifies: Basic.kt reports expected type mismatch diagnostics; ContextParams.kt has no diagnostics when ContextParameters/-Xcontext-parameters is enabled; Semantic.kt hover and definition probes resolve successfully. The tmux run printed complete successful output (total execution time 1642ms, used memory 270MB) but the old spike app kept JVM threads alive after printing results, so the session was interrupted after output capture. Meaning for ka-slua: standalone Analysis API remains viable, but the actionable ka-slua result comes from sidecar integration coverage: existing-session-file dangling completion works with Kotlin PSI validation enabled, using analyzeCopy(IGNORE_SELF), without session rebuild or LightVirtualFile fallback. The global bypass remains enabled by default only because non-completion/fallback paths such as hover/signature/diagnostics/new virtual files may still depend on legacy LightVirtualFile/in-memory PSI behavior and need follow-up lifecycle work.
