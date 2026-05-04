---
id: ka-ftb5
status: open
deps: []
links: []
created: 2026-04-14T11:52:52Z
type: epic
priority: 1
assignee: Jens Kouros
tags: [refactoring, sidecar]
---
# CompilerBridge decomposition

Break up the 6,204-line CompilerBridge.kt monolith into BridgeContext + feature-specific extension function files.

## Design

### Approach: BridgeContext + Extension Functions

Extract shared state (17 mutable fields) into a `BridgeContext` class. Feature implementations become `internal` extension functions on `BridgeContext` in separate files. `CompilerBridge` remains as a thin ~150-line facade preserving the public API.

### Target Architecture

```
CompilerBridge.kt (~150 lines, facade + companion object)
  └── BridgeContext.kt (~1,050 lines, shared state + core utilities)
        ├── BridgeCodeActions.kt      (~1,276 lines)
        ├── BridgeHover.kt            (~617 lines)
        ├── BridgeCompletion.kt       (~376 lines)
        ├── BridgeNavigation.kt       (~440 lines)
        ├── BridgeNavigationHelpers.kt (shared interface navigation)
        ├── BridgeDiagnostics.kt      (~274 lines)
        ├── BridgeSemanticTokens.kt   (~167 lines)
        ├── BridgeInlayHints.kt       (~175 lines)
        ├── BridgeCodeLens.kt         (~112 lines)
        ├── BridgeReferences.kt       (~147 lines)
        ├── BridgeRename.kt           (~126 lines)
        ├── BridgeSignatureHelp.kt    (~116 lines)
        ├── BridgeCallHierarchy.kt    (~180 lines)
        ├── BridgeTypeHierarchy.kt    (~155 lines)
        ├── BridgePebble.kt           (~100 lines)
        └── BridgeSymbols.kt          (~30 lines)

CompilerBridgeRuntime.kt (neutral runtime/helper owner)
  ↑ used by BridgeContext and CompilerBridge.Companion wrappers
```

### Execution Chain

1. ka-e6eo — Extract BridgeContext (foundation, all others depend on this)
2. ka-wvv8 — Extract BridgeNavigationHelpers (shared interface navigation helpers)
3. ka-4dsl — Extract BridgeCodeActions
4. ka-b4j7 — Extract BridgeHover
5. ka-zbgz — Extract BridgeCompletion
6. ka-8vwe — Extract BridgeNavigation
7. ka-vu27 — Extract BridgeDiagnostics
8. ka-lws3 — Extract BridgeSemanticTokens, InlayHints, CodeLens
9. ka-9oze — Extract BridgeReferences, Rename, SignatureHelp
10. ka-d994 — Extract BridgeCallHierarchy, TypeHierarchy, Pebble, Symbols
11. ka-xwq9 — Finalize CompilerBridge facade (depends on all above)

### Constraints

- Public API of `CompilerBridge` must remain unchanged (AnalysisServer.kt should need zero changes)
- Companion object wrappers stay on CompilerBridge (tests reference `CompilerBridge.mapCompilerFlag()`)
- Runtime/helper implementations live in `CompilerBridgeRuntime` so `BridgeContext` does not depend on the facade
- Implementation is sequential — each extraction depends on BridgeContext existing first
- All new files in same package: `dev.kouros.sidecar`

### Verification

```bash
cd sidecar && ./gradlew test    # Kotlin unit + integration tests
cargo test                       # Rust unit tests
```

### Test Strategy

Do not restructure tests during this extraction sequence. Existing tests intentionally remain
API-level regression coverage through `CompilerBridge`, because the facade preserves the public
constructor, instance methods, and companion object methods. If test organization should mirror the
new `Bridge*` files later, create a separate follow-up ticket after the facade is complete.

## Acceptance Criteria

- CompilerBridge.kt reduced from 6,204 to a ~150-line facade
- All existing tests pass without modification
- AnalysisServer.kt unchanged
- Each feature file is self-contained with clear boundaries
- No circular dependencies between feature files
