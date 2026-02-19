# kotlin-analyzer v1 Roadmap

This roadmap defines the scope and milestones for the first public release of kotlin-analyzer. The original 7-milestone plan has been compressed to 4 milestones, cutting scope aggressively to ship a minimum viable Kotlin LSP for Zed.

v1 targets single-module Gradle projects on macOS and Linux. Multi-module support, refactoring features, and advanced navigation are deferred.

---

## Pre-Milestone: Analysis API Spike

The core technical risk — whether the Kotlin Analysis API works in standalone mode outside IntelliJ — has been validated against Kotlin 2.1.20. See [spike/analysis-api/FINDINGS.md](../spike/analysis-api/FINDINGS.md) for full results.

**Key findings (2.1.20):**
- `StandaloneAnalysisAPISession` works fully outside IntelliJ
- Diagnostics are collected via `KtFile.collectDiagnostics()` with severity, message, and positions
- Compiler flags (e.g., `-Xcontext-parameters`) are applied via `LanguageFeature` enum in `LanguageVersionSettingsImpl`
- Cold start: ~1.1s (session creation + first analysis for a small file)
- Memory: ~97 MB for small files within `-Xmx512m`
- Dependencies: 21 JARs, ~70 MB total (artifacts on JetBrains Space repo, not Maven Central)
- No blockers found

**Implications for roadmap:**
- M2 sidecar work can proceed with confidence; the API is confirmed functional
- Dependency setup requires JetBrains Space repo and `isTransitive = false` on all `-for-ide` artifacts
- The sidecar must use a single long-lived session (concurrent sessions are not supported)
- All kotlin-stdlib JARs (base, jdk7, jdk8) must be provided on the analysis classpath

### Outstanding: Re-validate spike against Kotlin 2.3.10

- [x] Initial spike validated against Kotlin 2.1.20
- [ ] Update `spike/analysis-api/build.gradle.kts` to target Kotlin 2.3.10
- [ ] Verify session creation, diagnostics, and compiler flag application still work
- [ ] Check for new/renamed `-for-ide` artifacts or API changes
- [ ] Update `spike/analysis-api/FINDINGS.md` with 2.3.10-specific results (startup time, memory, JAR sizes)
- [ ] Confirm no blockers before starting M2

This is a **gate for M2** — do not begin sidecar implementation until the spike passes on 2.3.10.

---

## Milestone 1: Extension + Tree-sitter + CI Foundation

Establish the Zed extension, tree-sitter integration, and CI pipeline. No LSP server yet — this milestone delivers syntax-level features only.

- [x] Fork `zed-extensions/zed-kotlin` as the starting point
- [x] Zed extension scaffold (`extension.toml`, `Cargo.toml`, `src/lib.rs`)
- [x] Language registration with `fwcd/tree-sitter-kotlin` grammar
- [x] Port and improve tree-sitter queries from the existing extension
  - [x] `highlights.scm` — review and improve coverage for Kotlin 2.x syntax
  - [x] `outline.scm` — ensure classes, functions, properties, objects, enums, interfaces appear
  - [x] `indents.scm` — review and test
  - [x] `brackets.scm`, `injections.scm`, `overrides.scm` — carry forward
- [x] Write `textobjects.scm` (function, class, comment, parameter text objects)
- [x] Write `runnables.scm` (detect `fun main()` and `@Test` annotated functions)
- [x] Create Kotlin source fixture files (see `docs/testing.md` §3.2)
  - [x] `correct/` files: BasicConstructs, ClassHierarchy, DataAndSealed, ExtensionFunctions, Generics, NullSafety, Lambdas, Collections, TypeAliases, Delegation, Annotations, MainFunction, OutlineShowcase
  - [x] `errors/` files: TypeMismatch, UnresolvedReference, MissingOverride, WrongArgCount, NullViolation, ReturnTypeMismatch, DuplicateDeclaration, AbstractInstantiation, ValReassignment, VisibilityViolation, MissingSuperCall
  - [x] `warnings/` files: UnusedVariable, UnusedImport, UnusedParameter, DeprecatedUsage, RedundantCast, UnnecessarySafeCall, UnreachableCode, RedundantIf
  - [x] `edge-cases/` files: Empty, OnlyComments, OnlyPackage, SyntaxError, UnicodeIdentifiers
  - [x] `compiler-flags/` files: ContextParameters, MultiDollarInterpolation, ContextReceivers
- [x] Verify tree-sitter queries against fixture files
  - [x] Run `highlights.scm` against all `correct/` files, verify no missing or incorrect highlights
  - [x] Run `outline.scm` against `OutlineShowcase.kt`, verify all classes, functions, objects, enums, interfaces appear
  - [x] Run `runnables.scm` against `MainFunction.kt`, verify `fun main()` is detected
  - [x] Run `runnables.scm` against `TestClass.kt`, verify `@Test` functions are detected
  - [x] Run `textobjects.scm` against `ClassHierarchy.kt` and `Lambdas.kt`, verify function and class boundaries
  - [x] Verify `SyntaxError.kt` produces a partial tree with `ERROR` node, does not hang
- [x] **CHECKPOINT: Verify extension loads and tree-sitter works in Zed**
  - [x] Add `eprintln!` log statements in `src/lib.rs` at language registration to confirm extension initialization
  - [x] Start Zed in foreground mode via tmux: `tmux new-session -d -s zed 'zed --foreground /path/to/fixtures'`
  - [x] Attach to the tmux session (`tmux attach -t zed`) and verify log output shows the kotlin-analyzer extension loaded successfully
  - [x] Open `tests/fixtures/kotlin/correct/BasicConstructs.kt` in Zed
  - [x] Use Peekaboo to capture an annotated screenshot: `peekaboo see --app Zed --annotate`
  - [x] Verify syntax highlighting is applied — keywords, types, strings, and comments should show distinct colors
  - [x] Open `tests/fixtures/kotlin/correct/OutlineShowcase.kt`, open the outline panel, verify classes/functions/objects/enums/interfaces appear
  - [x] Open `tests/fixtures/kotlin/correct/MainFunction.kt`, verify runnables gutter icon appears for `fun main()`
  - [x] Open `tests/fixtures/kotlin/edge-cases/SyntaxError.kt`, verify partial highlighting renders without crash or hang
  - [x] Capture a Peekaboo screenshot for each verification step — these serve as visual regression references
- [ ] Set up GitHub Actions CI workflow
  - [ ] Build matrix for platform targets
  - [ ] Cross-platform Rust binary builds (using `cargo-zigbuild`)
  - [ ] Sidecar JAR build step (Gradle `shadowJar`)
  - [ ] Automated packaging into release archives (`.tar.gz`, `.zip`)
  - [ ] Tag-triggered release creation on GitHub (use `gh release create` for manual/local releases)
  - [ ] Verify CI runs with `gh run list` and `gh run view`

---

## Milestone 2: LSP Skeleton + JVM Sidecar + Diagnostics

Build the Rust LSP binary and JVM sidecar. Establish the bridge between them. Deliver diagnostics as the first semantic feature.

### Rust LSP Server

- [ ] Vendor `tower-lsp` 0.20.0 as an internal workspace crate (see `lsp-protocol.md` §1 for rationale)
  - [ ] Fork source into `crates/tower-lsp/`
  - [ ] Replace `#[async_trait]` with native Rust async traits
  - [ ] Update to `lsp-types` 0.97, `tower` 0.5
  - [ ] Fix concurrent handler state-drift (upstream #284): serialize handler execution
  - [ ] Fix cancellation panic (upstream #417)
- [ ] LSP `initialize`/`shutdown`/`exit` lifecycle
- [ ] Document `textDocument/didOpen`, `textDocument/didClose`, `textDocument/didChange` synchronization (full sync mode)
- [ ] Error types with `thiserror`
- [ ] Structured logging with `tracing` (logs to stderr, configurable verbosity)

### Verification Gate: Rust Toolchain Checks

Once the Rust project scaffold exists, enable automated checks that run on every change and before every commit.

- [ ] Set up pre-commit hooks using `prek`:
  - [ ] `cargo fmt --check` — reject unformatted code
  - [ ] `cargo clippy -- -D warnings` — reject linter warnings
  - [ ] `cargo test` — reject failing tests
- [ ] Run `cargo fmt` to format all Rust code (do this after every code change from this point forward)
- [ ] Run `cargo test` and fix any failures before proceeding to the next section
- [ ] Verify the pre-commit hooks fire correctly by making a test commit

> **From this point on:** run `cargo fmt` and `cargo test` after every code change. Fix failures immediately. The pre-commit hooks enforce this, but don't rely solely on them — run checks proactively.

### JVM Sidecar

- [ ] Sidecar Kotlin project scaffold (`sidecar/build.gradle.kts`)
- [ ] Fat JAR build with `shadowJar` plugin
- [ ] `kotlin-compiler-embeddable` dependency (pinned version: 2.3.10)
- [ ] Analysis API standalone setup (FIR-backed, non-IntelliJ)
- [ ] JSON-RPC server over stdin/stdout (reads requests, writes responses)
- [ ] `analyze` RPC method: accept Kotlin source + classpath + compiler flags, return diagnostics

### Verification Gate: Sidecar Build and Tests

Once the sidecar project scaffold exists, verify it builds and its tests pass before wiring up the bridge.

- [ ] Run `cd sidecar && ./gradlew build` — verify clean build with no errors
- [ ] Run `cd sidecar && ./gradlew test` — verify all unit tests pass
- [ ] Add sidecar checks to `prek` pre-commit hooks:
  - [ ] `cd sidecar && ./gradlew test` — reject failing sidecar tests
- [ ] Test the sidecar JAR manually: pipe a JSON-RPC `analyze` request into `java -jar sidecar/build/libs/sidecar-all.jar` with a simple Kotlin snippet, verify it returns a valid JSON-RPC response
- [ ] Run `cargo test && (cd sidecar && ./gradlew test)` as a combined smoke test

> **From this point on:** run `./gradlew test` in the sidecar after every Kotlin/Gradle change. The pre-commit hooks now cover both Rust and JVM.

### Rust-JVM Bridge

- [ ] JSON-RPC client in Rust for communicating with the sidecar
- [ ] Sidecar lifecycle manager: spawn JVM process, manage stdin/stdout pipes
- [ ] Startup state machine: `Starting` -> `Ready` -> `Degraded` -> `Restarting`
- [ ] Health check via periodic heartbeat RPC
- [ ] Crash detection and automatic restart with exponential backoff
- [ ] Graceful shutdown: send `shutdown` RPC, wait, then terminate process

### Project Model

- [ ] Gradle project detection (find `build.gradle.kts` or `build.gradle` in workspace root)
- [ ] Classpath extraction via Gradle Tooling API
- [ ] Compiler flag extraction (`freeCompilerArgs`, `kotlinOptions`, `compilerOptions`)
- [ ] Project model caching to disk (avoid re-running Gradle on every startup)
- [ ] Build file watching with debounced re-extraction (watch `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`)

### Diagnostics

- [ ] On document open/change: send source to sidecar, receive diagnostics, publish via `textDocument/publishDiagnostics`
- [ ] Map sidecar diagnostic positions to LSP positions (line/column)
- [ ] Debounce analysis requests (wait for typing pause before analyzing)

### Verification Gate: End-to-End Diagnostics

Once diagnostics are wired end-to-end (Zed → Rust LSP → sidecar → Rust LSP → Zed), verify the full pipeline works before moving on.

**CLI-level verification:**
- [ ] Start the full LSP server (`cargo run -- --log-level debug`) and send an LSP `textDocument/didOpen` notification with `tests/fixtures/kotlin/errors/TypeMismatch.kt` via a test client script; verify `textDocument/publishDiagnostics` arrives with the expected error positions and messages
- [ ] Repeat with `tests/fixtures/kotlin/correct/BasicConstructs.kt` — verify zero diagnostics are published
- [ ] Repeat with each `compiler-flags/` file WITHOUT the flag — verify errors are returned
- [ ] Repeat with each `compiler-flags/` file WITH the flag (via `gradle-compiler-flags/` project) — verify no errors

**Visual verification in Zed:**
- [ ] Start Zed in foreground mode via tmux: `tmux new-session -d -s zed 'zed --foreground /path/to/fixture-project'`
- [ ] Open `tests/fixtures/kotlin/errors/TypeMismatch.kt` in Zed
- [ ] Use Peekaboo to screenshot: `peekaboo see --app Zed --annotate`
- [ ] Verify red squiggly underlines appear at the expected error locations (line 8, line 12)
- [ ] Open `tests/fixtures/kotlin/warnings/UnusedVariable.kt`
- [ ] Screenshot with Peekaboo — verify yellow/warning indicators appear at the expected lines
- [ ] Open `tests/fixtures/kotlin/correct/BasicConstructs.kt`
- [ ] Screenshot with Peekaboo — verify NO squiggly lines appear (clean file)
- [ ] Check the Zed log output in the tmux session for any bridge errors, protocol violations, or sidecar crashes

**Run all automated checks:**
- [ ] `cargo fmt --check && cargo clippy -- -D warnings && cargo test && (cd sidecar && ./gradlew test)` — everything must pass

### Extension Integration

- [ ] Extension `language_server_command` downloads and launches Rust binary from GitHub releases
- [ ] Platform detection and archive selection in extension code

### Testing

- [ ] Integration tests with mock sidecar (lifecycle, document sync, crash recovery)
- [ ] Unit tests for JSON-RPC framing, document store, config parsing, classpath extraction
- [ ] Architecture dependency test: verify `use crate::` imports conform to the allowed dependency graph (see `architecture.md` §4.1, `testing.md` §6)
- [ ] Diagnostic verification against fixture files (real sidecar)
  - [ ] All `correct/` files produce zero diagnostics
  - [ ] All `errors/` files produce expected diagnostics at expected lines
  - [ ] All `warnings/` files produce expected warnings at expected lines
  - [ ] All `edge-cases/` files: no crash, expected behavior per file
  - [ ] `compiler-flags/` files produce errors WITHOUT flags
  - [ ] `compiler-flags/` files produce no errors WITH flags (via `gradle-compiler-flags/` fixture)
- [ ] Write test harness that parses `EXPECT-ERROR` / `EXPECT-WARNING` / `EXPECT: no-diagnostics` headers from fixture files and asserts against actual LSP `textDocument/publishDiagnostics`

---

## Milestone 3: Core Semantic Features

Build the semantic features that make the LSP useful for daily Kotlin development.

### Navigation and Intelligence

- [ ] Go-to-definition (`textDocument/definition`)
  - Local variables, functions, classes
  - Stdlib and dependency symbols (navigate to decompiled stubs)
- [ ] Hover (`textDocument/hover`)
  - Type information for variables and expressions
  - KDoc rendering for functions and classes
  - Signature display for function calls
- [ ] Completion (`textDocument/completion`)
  - Type-aware completions (methods, properties, functions in scope)
  - Auto-import suggestions for unresolved symbols
  - Keyword completions in appropriate contexts
  - Snippet completions for common patterns (`if`, `when`, `fun`)
- [ ] Signature help (`textDocument/signatureHelp`)
  - Parameter hints when typing function arguments
  - Highlight active parameter

### Formatting

- [ ] Formatting via `textDocument/formatting`
- [ ] Shell to external formatter (ktfmt or ktlint, configurable)
- [ ] Formatter binary discovery (PATH lookup, configurable path)
- [ ] Handle formatter not found (clear error message, no crash)
- [ ] `.editorconfig` support
  - [ ] Ensure the formatter is invoked with the project root as its working directory so it discovers `.editorconfig` files (ktlint reads them natively)
  - [ ] Watch `**/.editorconfig` for changes (add to `workspace/didChangeWatchedFiles` registrations)
  - [ ] On `.editorconfig` change: invalidate any cached formatting state and re-format open files if the user has format-on-save enabled
  - [ ] For ktfmt: map applicable `.editorconfig` properties (`max_line_length` → `--max-width`) since ktfmt does not read `.editorconfig` natively
  - [ ] Respect per-section overrides (e.g., `[{*.kt,*.kts}]` for Kotlin-specific settings, `[src/test/**/*.kt]` for test-specific rule disabling)
  - [ ] Pass through ktlint-specific `.editorconfig` properties (`ktlint_code_style`, `ktlint_standard_*` rule toggles) — these are handled by ktlint itself, but the LSP must not interfere with them

### Request Management

- [ ] Cancellation propagation: LSP `$/cancelRequest` -> Rust -> sidecar
- [ ] Backpressure handling: bounded request channels, supersede stale completion/hover requests
- [ ] Request prioritization: cancellation > document sync > diagnostics > completion > hover

### Kotlin-Specific Support

- [ ] Compose compiler plugin detection (look for `org.jetbrains.compose` or `kotlin("plugin.compose")` in build file)
- [ ] Compose compiler plugin loading in sidecar environment
- [ ] KAPT generated source roots (`build/generated/source/kapt/`)
- [ ] KSP generated source roots (`build/generated/ksp/`)

### Configuration

- [ ] Configuration schema passed from extension to LSP:
  - `kotlin-analyzer.javaHome`: override JVM path
  - `kotlin-analyzer.compiler.flags`: additional compiler flags
  - `kotlin-analyzer.formatting.formatter`: `ktfmt` | `ktlint` | `none`
  - `kotlin-analyzer.formatting.path`: path to formatter binary
  - `kotlin-analyzer.sidecar.maxMemory`: JVM `-Xmx` value (default: `512m`)
- [ ] Progress notifications during sidecar initialization (`window/workDoneProgress`)

### Testing

- [ ] Integration tests with real sidecar + Kotlin project fixtures
- [ ] Test each semantic feature against known-good expected results
  - [ ] Hover over typed variables in `correct/Generics.kt`, `correct/NullSafety.kt` — verify type info
  - [ ] Go-to-definition on local functions in `correct/BasicConstructs.kt` — verify position
  - [ ] Go-to-definition on stdlib calls (`println`, `listOf`) — verify resolution
  - [ ] Completion after `"hello".` — verify `String` methods appear
  - [ ] Completion in `correct/Collections.kt` after collection method chains — verify suggestions
  - [ ] Signature help inside function call parens in `correct/Lambdas.kt` — verify parameter hints
- [ ] Snapshot tests for serialized LSP responses
- [ ] Add `correct/Coroutines.kt` and `correct/TestClass.kt` to `gradle-simple/` fixture (require classpath dependencies)
- [ ] Add `edge-cases/LargeFile.kt` and `edge-cases/DeepNesting.kt` (deferred from M1 since they need sidecar to validate)

### Verification Gate: Semantic Features in Zed

Once semantic features are wired end-to-end, verify they work visually in Zed before moving to M4.

- [ ] Start Zed in foreground mode via tmux with the `gradle-simple/` fixture project open
- [ ] Open `correct/BasicConstructs.kt`, hover over a typed variable
  - [ ] Screenshot with Peekaboo — verify type information tooltip appears
- [ ] Open `correct/Collections.kt`, type `.` after a collection variable
  - [ ] Screenshot with Peekaboo — verify completion menu appears with collection methods (e.g., `filter`, `map`, `flatMap`)
- [ ] Open `correct/ClassHierarchy.kt`, go-to-definition on a function call
  - [ ] Verify cursor navigates to the function definition
- [ ] Open `correct/Lambdas.kt`, position cursor inside a function call's parentheses
  - [ ] Screenshot with Peekaboo — verify signature help / parameter hints appear
- [ ] Open a file with unresolved imports, trigger completion
  - [ ] Verify auto-import suggestions appear in the completion menu
- [ ] Verify formatting works: run the format command on a deliberately mis-formatted file, confirm it formats correctly
- [ ] Verify configuration works end-to-end via Zed settings:
  - [ ] Set `kotlin-analyzer.compiler.flags` to `["-Xcontext-parameters"]` in Zed settings, open a `compiler-flags/ContextParameters.kt` file — verify no errors (flag is applied)
  - [ ] Remove the flag from settings, reload — verify errors reappear
  - [ ] Set `kotlin-analyzer.formatting.formatter` to `"none"`, verify format command is a no-op
  - [ ] Set `kotlin-analyzer.sidecar.maxMemory` to a different value, verify sidecar restarts with the new `-Xmx`
  - [ ] Screenshot each configuration change with Peekaboo to confirm the setting takes effect visually
- [ ] Check Zed log output for any errors, warnings, or sidecar issues during all of the above

**Run all automated checks:**
- [ ] `cargo fmt --check && cargo clippy -- -D warnings && cargo test && (cd sidecar && ./gradlew test)` — everything must pass

---

## Milestone 4: Polish + Release

Harden the system, measure performance, fill gaps, write documentation, and publish.

### Performance

- [ ] Measure baseline performance metrics:
  - Time to LSP connection (Rust binary startup)
  - Time to first diagnostics (including JVM startup + initial analysis)
  - Completion latency (after initial analysis)
  - Hover/go-to-def latency
  - Memory usage (Rust binary RSS, JVM sidecar RSS)
- [ ] Set concrete performance targets based on measurements
- [ ] Optimize against targets (profile, identify bottlenecks, fix)
- [ ] Set up CI performance tracking (compare metrics across commits)

### Fallback Support

- [ ] Maven project support (`pom.xml` detection, `dependency:build-classpath` for classpath)
- [ ] Manual configuration fallback (`.kotlin-analyzer.json` in project root)
  - Specify source roots, classpath entries, compiler flags manually
  - For projects without Gradle or Maven, or when automatic extraction fails
- [ ] No-build-system fallback (analyze `.kt` files with stdlib only)

### Error Handling and User Experience

- [ ] Clear error messages for common failure modes:
  - No JVM found
  - JVM version too old
  - Gradle classpath extraction failed (with Gradle error output)
  - Sidecar crash (with restart notification)
  - Out of memory (suggest increasing `sidecar.maxMemory`)
- [ ] Zed status bar integration: show sidecar state (Starting, Ready, Degraded)
- [ ] Graceful degradation: when sidecar is down, return empty results instead of errors

### Verification Gate: Error UX and Fallback Behavior

Before writing documentation and publishing, verify that error states and fallback behavior are user-visible and correct.

- [ ] Start Zed with no JVM installed (or `JAVA_HOME` pointed at a nonexistent path)
  - [ ] Screenshot with Peekaboo — verify a clear error message is shown (not a silent failure)
- [ ] Start Zed with the sidecar JAR missing or corrupted
  - [ ] Verify the status bar shows the degraded state and a meaningful message appears in logs
- [ ] Open a project with no `build.gradle.kts` or `pom.xml`
  - [ ] Verify the no-build-system fallback activates (stdlib-only analysis) and syntax highlighting still works
- [ ] Simulate a sidecar crash (kill the JVM process while Zed is running)
  - [ ] Verify the sidecar auto-restarts, diagnostics reappear, and no data is lost
  - [ ] Screenshot with Peekaboo before and after the restart to confirm recovery

**Run all automated checks one final time:**
- [ ] `cargo fmt --check && cargo clippy -- -D warnings && cargo test && (cd sidecar && ./gradlew test)` — everything must pass
- [ ] Verify pre-commit hooks still fire correctly (they should be catching all of the above already)

### Documentation

- [ ] README with:
  - What kotlin-analyzer is and what it provides
  - Installation instructions (Zed extension install)
  - JDK requirement and setup
  - Configuration reference
  - Troubleshooting guide
- [ ] CHANGELOG for the initial release

### Legal and Publishing

- [x] License selection: **Apache 2.0** (compatible with `zed-kotlin` fork [Apache 2.0] and `kotlin-compiler-embeddable` [Apache 2.0])
- [ ] Add `LICENSE` file to repository root
- [ ] Publish extension to Zed extension registry
- [ ] Create GitHub release with binaries (`gh release create v0.1.0 --title "..." --notes "..." *.tar.gz`)

### Feedback

- [ ] Announce availability (Zed community, Kotlin community)
- [ ] Set up issue templates for bug reports and feature requests (use `gh` to manage issues)
- [ ] Gather initial user feedback, triage into backlog

---

## Deferred (Post-v1)

These features are explicitly out of scope for v1. They will be considered based on user demand after the initial release.

### Additional LSP Features
- Find references (`textDocument/references`)
- Rename symbol (`textDocument/rename`)
- Code actions and quickfixes (`textDocument/codeAction`)
- Workspace symbols (`workspace/symbol`)
- Inlay hints (`textDocument/inlayHint`)
- Semantic tokens (`textDocument/semanticTokens`)
- Call hierarchy (`callHierarchy/incomingCalls`, `callHierarchy/outgoingCalls`)
- Type hierarchy (`typeHierarchy/supertypes`, `typeHierarchy/subtypes`)
- Code lens (`textDocument/codeLens`)

### Project Support
- `.kts` scripting support (build scripts, standalone scripts)
- Multi-module Gradle projects (v2 architecture with per-module analysis environments)
- Kotlin Multiplatform / Kotlin/JS / Kotlin/Native

### Distribution
- Bundled JVM for zero-dependency installation
- Windows as a primary (fully tested) target

---

## Performance Targets

These targets are aspirational for v1. They will be validated and adjusted in Milestone 4 based on actual measurements.

| Metric | Target | Notes |
|--------|--------|-------|
| Time to LSP connection | < 500ms | Rust binary startup only |
| Time to first diagnostics | < 15s | Includes JVM startup + initial analysis |
| Completion latency | < 500ms | After initial analysis is complete |
| Hover / go-to-def latency | < 200ms | After initial analysis is complete |
| Memory (Rust binary) | < 20 MB | RSS of the Rust process |
| Memory (JVM sidecar) | < 512 MB | For single-module projects; configurable via `-Xmx` |
