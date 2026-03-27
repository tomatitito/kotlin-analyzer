# Kotlin Version Selection Plan

## Goal

Make `kotlin-analyzer` work reliably across projects that use different Kotlin versions, without requiring the entire server release to exactly match the project's Kotlin line.

This plan covers:

1. The immediate ticket fix for false diagnostics caused by the current `2.1.20` pin.
2. A runtime/package design that allows the Rust frontend to select a compatible Kotlin compiler + Analysis API payload per project.

## Problem Summary

The current architecture extracts the project's Kotlin version correctly, but cannot act on it:

- Rust stores `ProjectModel.kotlin_version` from Gradle extraction.
- Before this work, the JVM sidecar was built as a single `sidecar-all.jar` with one embedded Kotlin compiler / Analysis API line.
- Before this work, the Rust server found and launched exactly one sidecar JAR.
- The sidecar reports a hard-coded Kotlin version and has runtime logic that still prefers `2.1.20`.

This means version skew is structural, not just a missing conditional.

## Constraints

### Existing constraints

- `server` may orchestrate project replay and sidecar restart; `bridge` must stay independent of `state` and `project`.
- The standalone Analysis API is sensitive to classpath mismatches.
- The sidecar currently starts as a separate JVM process, which is already the correct isolation boundary.

### New constraints for this work

- We must not try to load multiple Kotlin compiler lines into one JVM classpath at the same time.
- Version choice must happen before the sidecar process starts, not after `initialize`.
- Offline use should remain possible once a version has been fetched or bundled.
- Restarts must preserve the selected version for the project.

## Recommended Architecture

Use a two-layer sidecar distribution:

1. **Launcher layer**
   - Small bootstrap JAR with the JSON-RPC entrypoint and version-selection logic.
   - Does not embed the Kotlin compiler or Analysis API.

2. **Versioned compiler payloads**
   - One payload per Kotlin version line.
   - Contains `kotlin-compiler`, `analysis-api-*-for-ide`, and sidecar implementation code compiled against that line.
   - Loaded by launching the JVM with an explicit classpath for the chosen payload.

Rust remains responsible for:

- resolving the project model
- selecting the desired Kotlin version
- locating or provisioning the payload
- launching the sidecar process with the correct classpath

This keeps version selection outside the Analysis API process and avoids classloader complexity.

## Version Selection Policy

For a project with extracted `kotlin_version`:

1. Prefer an exact version match if available locally.
2. Otherwise prefer the same minor line with the newest compatible patch.
3. Otherwise fall back to the newest bundled version.
4. Surface a warning to the user when the selected runtime is not an exact match.

For projects without a known Kotlin version:

1. Use the default bundled version.
2. Report the actual sidecar runtime version in logs and initialize response.

## Delivery Phases

## Phase 0: Immediate Ticket Fix

Status: Completed on 2026-03-27.

### Outcome

Stop the current false diagnostics caused by the outdated `2.1.20` pin.

### Changes

- Upgrade the sidecar build from `2.1.20` to the latest validated project-compatible line from the ticket reproduction.
- Remove hard-coded `2.1.20` assumptions in:
  - sidecar build metadata
  - initialize response version reporting
  - stdlib fallback lookup in `CompilerBridge`
- Add a regression test around `kotlin.test.Test` resolution in custom test source sets if the fixture can be reproduced locally.

### Acceptance

- The `ka-wlod` reproduction no longer reports false `Unresolved reference 'Test'`.
- The sidecar reports the actual bundled Kotlin version, not a stale constant.

### Notes

This phase is intentionally tactical. It reduces user pain but does not solve mixed-version support.

## Phase 1: Make Version Selection a First-Class Runtime Concern

Status: Completed on 2026-03-27.

### Outcome

The Rust server chooses a sidecar Kotlin runtime version before process launch.

### Changes

- Extend the project startup path to carry `ProjectModel.kotlin_version` into sidecar startup decisions.
- Replace `find_sidecar_jar()` with a resolver that returns a sidecar runtime descriptor:
  - launcher entrypoint
  - selected Kotlin version
  - payload classpath entries
- Update `Bridge::new` / `Bridge::start` inputs so the selected runtime survives restart.
- Make the sidecar initialize response include the actual loaded runtime version.

### Tasks

- Add a Rust type such as `SidecarRuntime { kotlin_version, classpath, main_class }`.
- Thread that through `server.rs` and `bridge.rs`.
- Log selected version and mismatch decisions clearly.

### Acceptance

- Logs show which Kotlin runtime was selected and why.
- Bridge restarts preserve the same selected runtime.

## Phase 2: Split Packaging Into Launcher + Versioned Payloads

Status: Completed on 2026-03-27.

### Outcome

The sidecar is no longer a single monolithic shaded JAR tied to one compiler line.

### Changes

- Refactor Gradle packaging so the bootstrap launcher is separate from compiler payload artifacts.
- Produce payload artifacts under a versioned layout, for example:
  - `sidecar/build/runtime/2.3.20/...`
  - `sidecar/build/runtime/2.2.21/...`
- Ensure each payload contains:
  - Kotlin compiler
  - Analysis API `-for-ide` artifacts
  - sidecar implementation compiled for that line

### Tasks

- Split shared protocol code from version-bound Analysis API implementation if needed.
- Decide whether to build payloads from:
  - one multi-version Gradle build
  - one subproject per Kotlin line
  - generated included builds
- Update distribution scripts so release artifacts preserve the versioned runtime layout.

### Acceptance

- A local build can produce at least two sidecar payload versions.
- The Rust server can launch either payload via explicit JVM classpath.

## Phase 3: Local Cache and Provisioning

Status: Completed on 2026-03-27.

### Outcome

Projects can use a matching runtime even when it is not bundled in the release artifact.

### Changes

- Add a local cache directory for sidecar runtimes keyed by:
  - kotlin-analyzer version
  - target platform
  - Kotlin version
- Support overriding the cache root with `KOTLIN_ANALYZER_RUNTIME_CACHE_DIR`.
- On startup, runtime resolution checks:
  - bundled runtimes
  - cached runtimes
  - optional provision source directories
- Support overriding provision source directories with `KOTLIN_ANALYZER_RUNTIME_SOURCE_DIRS`.
- Provision exact-match runtimes into the cache with a temp-dir + atomic rename flow.
- Validate cached runtime manifests against:
  - directory/version match
  - kotlin-analyzer version
  - target platform
  - classpath file existence
- Cache key must include:
  - Kotlin version
  - kotlin-analyzer version
  - target platform

### Tasks

- Define runtime manifest format. Completed.
- Add integrity checks for cached payloads. Completed.
- Add cleanup policy for old runtimes. Deferred.

### Acceptance

- A missing exact runtime can be provisioned once and reused offline afterwards.
- Corrupt cache entries are detected and replaced cleanly.

### Notes

This phase currently provisions from local runtime source directories rather than a remote download service. That preserves the runtime-selection seam for future network provisioning while delivering reusable local caching now.

## Phase 4: UX, Warnings, and Observability

### Outcome

Users can tell when they are running an exact match, a fallback, or an incompatible runtime.

### Changes

- Show a warning when runtime selection falls back across minor lines.
- Include selected runtime version in diagnostics/logging/health output.
- Add telemetry-style counters in logs for:
  - exact matches
  - same-minor fallbacks
  - cross-minor fallbacks
  - provisioning failures

### Acceptance

- When fallback occurs, the user gets one clear message instead of silent misanalysis.
- Debug logs are sufficient to explain runtime choice and cache behavior.

## Testing Strategy

### Unit tests

- Version parser and comparator behavior.
- Runtime selection policy.
- Runtime manifest/cache validation.

### Integration tests

- Rust startup selects exact match when bundled.
- Rust startup selects cached runtime when bundled runtime is absent.
- Bridge restart reuses selected runtime.
- Mismatch warning appears when fallback is used.

### End-to-end tests

- Reproduce `ka-wlod` against the target Gradle project.
- Verify one project on Kotlin `A.B.C` and another on a different Kotlin line can both run correctly in separate editor sessions.
- Verify opening two different projects sequentially reselects the correct runtime each time.

## Open Design Decisions

### 1. Exact patch match vs same-minor compatibility

Recommendation:

- treat exact patch match as preferred
- permit same-minor fallback only after validation

Reason:

The Analysis API is not stable enough to assume all patch combinations are harmless without testing.

### 2. Bundle multiple runtimes vs download on demand

Recommendation:

- bundle one current runtime
- support on-demand provisioning for exact matches

Reason:

Bundling multiple compiler lines increases release size quickly. A hybrid model keeps the base install reasonable while still allowing accurate matching.

### 3. One codebase vs per-version implementation modules

Recommendation:

- keep one protocol/bootstrap layer
- isolate version-bound Analysis API code behind a narrow implementation layer

Reason:

This limits how much code must change when a new Kotlin line introduces API breakage.

## Risks

### Packaging complexity

Multi-version artifacts will complicate Gradle build logic and release packaging.

Mitigation:

- land Phase 1 runtime selection interfaces before Phase 2 packaging changes
- keep the first implementation to two versions max

### Analysis API source incompatibility

New Kotlin lines may require code changes in the sidecar implementation.

Mitigation:

- keep version-bound code behind one implementation seam
- validate each new runtime line with focused integration tests

### Cache corruption / partial installs

Provisioned runtimes may be interrupted or left incomplete.

Mitigation:

- install into a temp dir, then atomically rename into place
- require manifest verification before use

### User confusion during fallback

If the selected runtime differs from the project version, users may not know why diagnostics are off.

Mitigation:

- explicit warning with concrete versions
- log both requested and selected versions

## Recommended Execution Order

1. Land Phase 0 to fix the current bug.
2. Land Phase 1 Rust/runtime selection plumbing with the existing single bundled runtime.
3. Land Phase 2 packaging split for two validated Kotlin lines.
4. Land Phase 3 cache/provisioning.
5. Land Phase 4 user-visible warnings and operational polish.

## Definition of Done

This work is complete when:

- `kotlin-analyzer` can select a Kotlin runtime per project before sidecar startup.
- At least one non-default Kotlin version can be used without rebuilding the whole product.
- Exact or fallback runtime choice is visible in logs and initialize metadata.
- The `ka-wlod` reproduction is clean.
- Tests cover selection, restart, and mismatch behavior.
