---
id: ka-wlod
status: open
deps: []
links: []
created: 2026-03-27T12:52:50Z
type: bug
priority: 2
assignee: Jens Kouros
tags: [zed, kotlin, diagnostics, gradle, analysis-api]
---
# False unresolved kotlin.test.Test diagnostics for custom integration test source sets

Reproduced against `/Volumes/sourcecode/meta-breuninger/entdecken/reco/outfit-genie` using `ZED_LSP_TRACE=kotlin-analyzer my-zed` and opening `src/test/kotlin/com/breuninger/reco/outfitgenie/domain/model/LandTest.kt`.

Observed behavior:
- Kotlin Analyzer starts successfully and analyzes the open unit-test file without diagnostics.
- Background project analysis reports false diagnostics in `src/test-it/kotlin`, for example in `src/test-it/kotlin/com/breuninger/reco/outfitgenie/ports/inbound/frontend/api/ApiControllerTest.kt`.
- The reported failures are unresolved references to `Test`, including the import `import kotlin.test.Test`.

What was verified:
- The target project defines custom Gradle source sets `integrationTest` and `e2eTest`.
- The Kotlin Analyzer Gradle extractor emits `src/test-it/kotlin` and `src/test-e2e/kotlin` as source roots.
- The extracted classpath includes `kotlin-test`, `kotlin-test-junit5`, `spring-boot-starter-test`, and JUnit jars.
- Regular `src/test/kotlin` files using `org.junit.jupiter.api.Test` analyze cleanly.

Likely cause:
- The sidecar/compiler is pinned to Kotlin Analysis API 2.1.20 while the reproduced project uses Kotlin 2.3.20.
- This looks like an analyzer compatibility gap rather than a Gradle extraction failure.

Impact:
- False positives in custom test source sets make the analyzer noisy on real Gradle projects that use additional test source sets and/or `kotlin.test.Test`.

Suggested next step:
- Upgrade the sidecar/compiler line to match newer Kotlin versions and rerun the same `my-zed` reproduction.

## Acceptance Criteria

- Reproduce against `outfit-genie` with `my-zed`.
- No false `Unresolved reference 'Test'` diagnostics in `src/test-it/kotlin` or `src/test-e2e/kotlin`.
- Existing clean `src/test/kotlin` analysis remains clean.
- Background project analysis completes without these false positives.
