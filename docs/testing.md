# Testing Strategy

This document describes the testing approach for kotlin-analyzer, covering unit tests, integration tests, test fixtures, conformance testing, CI integration, and regression testing.

---

## 1. Unit Tests

### Rust Side

Unit tests for the Rust binary cover individual modules in isolation with no JVM dependency.

**`state.rs` — Document Store**
- Open a document, retrieve its content
- Apply a full-content change, verify updated content
- Apply multiple changes in sequence
- Close a document, verify it is removed
- Retrieve content for a non-existent document (error case)
- Track document versions correctly

**`jsonrpc.rs` — JSON-RPC Framing**
- Serialize a request with `Content-Length` header
- Deserialize a response with `Content-Length` header
- Handle messages with `Content-Type` header (optional per spec)
- Reject messages with missing `Content-Length`
- Reject messages with mismatched `Content-Length` and body size
- Handle UTF-8 content correctly (Kotlin source contains non-ASCII)
- Parse batch and notification messages
- Handle malformed JSON gracefully (return parse error, do not panic)

**`bridge.rs` — Sidecar Protocol**
- Serialize analysis requests to JSON-RPC format
- Deserialize analysis responses from JSON-RPC format
- Map LSP types to sidecar protocol types and back
- Handle error responses from the sidecar
- Handle timeout when sidecar does not respond (with mocked channel)

**`config.rs` — Configuration**
- Parse configuration with all fields present
- Parse configuration with missing fields (apply defaults)
- Default `javaHome` is `None` (triggers JVM discovery)
- Default formatter is `ktfmt`
- Invalid configuration values produce clear error messages

**`project.rs` — Classpath Extraction**
- Parse Gradle Tooling API output into classpath entries
- Extract `freeCompilerArgs` from `build.gradle.kts` (mocked Gradle output)
- Handle Gradle project with no Kotlin plugin
- Handle Gradle project with multiple source sets
- Handle missing `build.gradle.kts` (not a Gradle project)
- Parse Maven `dependency:build-classpath` output (mocked)

### JVM Side

Unit tests for the sidecar cover the compiler bridge in isolation.

**`CompilerBridge.kt` — Analysis API Wrapper**
- Analyze a single-file Kotlin snippet, extract diagnostics
- Analyze a snippet with a compile error, verify error diagnostic position and message
- Analyze a snippet with a warning, verify warning diagnostic
- Resolve a type at a cursor position (hover)
- Resolve a definition location (go-to-def) for a local variable
- Resolve a definition location for a function call
- Compute completions at a cursor position in a partial expression
- Verify completions include auto-import suggestions
- Analyze a snippet using `-Xcontext-parameters`, verify no false-positive errors
- Analyze a snippet using `-Xmulti-dollar-interpolation`, verify correct parsing
- Handle an empty file (no diagnostics, no crash)
- Handle a file with only comments

Test against small, self-contained Kotlin snippets with known expected results. Each test constructs a `KotlinCoreEnvironment` with the snippet as a virtual file.

---

## 2. Integration Tests

### Mock Sidecar Tests

These tests run the full Rust LSP binary against a mock JVM process that returns predefined JSON-RPC responses. No JVM or Kotlin compiler is involved.

**Purpose:** Verify the Rust LSP logic, protocol handling, and sidecar lifecycle management without the cost and complexity of a real JVM.

**How it works:**
- Write a small Rust binary (or script) that reads JSON-RPC from stdin, matches against predefined request patterns, and writes predefined responses to stdout.
- The mock supports configurable behavior: normal responses, delayed responses, malformed responses, crash (exit unexpectedly), hang (never respond).

**Test cases:**

*Lifecycle:*
- Start LSP, send `initialize`, verify capabilities in response
- Send `initialized`, verify no error
- Send `shutdown` + `exit`, verify clean termination
- Start LSP without sending `initialize` first, verify error response

*Sidecar lifecycle:*
- Verify sidecar process is spawned on first semantic request
- Verify health check pings detect a healthy sidecar
- Simulate sidecar crash (mock exits), verify Rust restarts it
- Simulate repeated sidecar crashes, verify backoff and eventual degraded state
- Send `shutdown`, verify sidecar receives shutdown command

*Document synchronization:*
- Open a document, verify sidecar receives analysis request
- Change a document, verify updated content reaches sidecar
- Close a document, verify cleanup

*Cancellation:*
- Send a completion request, then send `$/cancelRequest` before mock responds
- Verify the cancellation is propagated to the sidecar
- Verify the client receives a cancellation response

*Backpressure:*
- Send 100 completion requests in rapid succession
- Verify the LSP debounces/supersedes stale requests
- Verify the most recent request is served
- Verify no requests are dropped silently (each gets a response or cancellation)

### Real Sidecar Tests

These tests spawn the actual Rust binary and JVM sidecar against real Kotlin project fixtures. They verify end-to-end behavior.

**Purpose:** Verify that the full system produces correct results for real Kotlin code.

**Test cases:**
- Open a single-file Kotlin project, verify diagnostics are published
- Introduce a type error, verify error diagnostic appears at the correct location
- Fix the type error, verify diagnostic is cleared
- Go-to-definition on a stdlib function call (e.g., `println`), verify it resolves
- Go-to-definition on a local function, verify file and position
- Hover over a variable, verify type information is returned
- Request completions after `"hello".`, verify String methods appear
- Open a Gradle project with dependencies, verify classpath is resolved
- Open a project with `-Xcontext-parameters`, verify no false-positive errors

**Execution:** These tests are slow (JVM startup + compilation). They run in CI on pushes to `main` and on pull requests, but not on every local `cargo test` invocation. Gate them behind a `#[cfg(feature = "integration")]` flag or a `#[ignore]` attribute with a CI-specific test runner configuration.

---

## 3. Test Fixtures

### 3.1 Fixture Projects

Small Kotlin projects stored in `tests/fixtures/` that cover specific build system and project structure scenarios.

| Fixture | Description | Used By |
|---------|-------------|---------|
| `single-file/` | One `.kt` file, no build system | Unit tests, basic integration |
| `gradle-simple/` | Gradle project with `kotlin("jvm")` plugin, one dependency | Classpath extraction, real sidecar |
| `gradle-compose/` | Gradle project with Compose compiler plugin | Compose detection |
| `gradle-compiler-flags/` | Project with `freeCompilerArgs = listOf("-Xcontext-parameters")` | Compiler flag extraction |
| `gradle-kapt/` | Project using KAPT annotation processing | Generated source roots |
| `gradle-ksp/` | Project using KSP symbol processing | Generated source roots |
| `gradle-multimodule/` | Multi-module project (`:app` depends on `:core`) | v2 multi-module support |
| `maven-simple/` | Maven project with `kotlin-maven-plugin` | Maven classpath extraction |
| `no-build-system/` | Directory of `.kt` files with no `build.gradle` or `pom.xml` | Fallback behavior |

Each fixture is a minimal but valid project. Gradle fixtures include a `gradle-wrapper.jar` and `gradlew` script so they can be built without a system Gradle installation. The `settings.gradle.kts` and `build.gradle.kts` files are kept minimal.

**Fixture management:** Fixtures are checked into the repository under `tests/fixtures/`. They are not generated. When a Kotlin or Gradle version bump changes expected behavior, update the fixtures and expected outputs.

### 3.2 Kotlin Source Fixtures

In addition to project-level fixtures, we maintain a library of individual `.kt` files with known expected outcomes. These files serve two purposes:

1. **Tree-sitter validation (Milestone 1):** Verify highlighting, outline, runnables, and text objects produce correct results. Tested via `tree-sitter` CLI query matching or manual inspection in Zed.
2. **Semantic validation (Milestone 2+):** Verify the sidecar produces correct diagnostics, completions, hover, and navigation. Tested via automated LSP integration tests.

Each file includes a header comment specifying the expected outcome (which diagnostics at which lines, or explicit "no diagnostics"). This makes them self-documenting and machine-parseable by test harnesses.

Source fixtures live in `tests/fixtures/kotlin/` organized by expected outcome:

#### `tests/fixtures/kotlin/correct/` — Zero Diagnostics Expected

Files that compile without errors or warnings. Any diagnostic produced is a false positive and a test failure.

| File | What It Exercises |
|------|-------------------|
| `BasicConstructs.kt` | `fun`, `val`, `var`, `if`, `when`, `for`, `while`, string templates, ranges |
| `ClassHierarchy.kt` | `class`, `abstract class`, `interface`, `object`, `companion object`, inheritance, overrides |
| `DataAndSealed.kt` | `data class`, `sealed class`, `sealed interface`, `enum class`, destructuring |
| `ExtensionFunctions.kt` | Extension functions, extension properties, scope functions (`let`, `apply`, `run`, `with`, `also`) |
| `Generics.kt` | Generic classes, functions, variance (`in`, `out`), star projection, type bounds |
| `NullSafety.kt` | Safe calls, elvis, `!!`, smart casts, `let`-based null checks, platform type handling |
| `Lambdas.kt` | Higher-order functions, lambda syntax, trailing lambdas, `it`, function references |
| `Coroutines.kt` | `suspend fun`, `launch`, `async`/`await`, `withContext`, `Flow` basics (requires `kotlinx-coroutines` on classpath — use in `gradle-simple/`) |
| `Collections.kt` | `listOf`, `mapOf`, `setOf`, `filter`, `map`, `flatMap`, `groupBy`, `associate`, sequences |
| `TypeAliases.kt` | `typealias`, including generic type aliases |
| `Delegation.kt` | `by lazy`, `by Delegates.observable`, interface delegation (`by`), `Map` delegation |
| `Annotations.kt` | Annotation declarations, `@Target`, `@Retention`, annotation usage on classes, functions, properties, parameters |
| `MainFunction.kt` | `fun main()` and `fun main(args: Array<String>)` — also used for runnables detection |
| `TestClass.kt` | Class with `@Test`-annotated functions — also used for runnables detection (requires JUnit on classpath — use in `gradle-simple/`) |
| `OutlineShowcase.kt` | Top-level functions, classes, nested classes, objects, enums, interfaces in one file — used for outline query validation |

#### `tests/fixtures/kotlin/errors/` — Specific Error Diagnostics Expected

Each file contains intentional errors. The expected error type and line number are documented in the file header.

| File | Error | Expected Diagnostic |
|------|-------|---------------------|
| `TypeMismatch.kt` | `val x: String = 42` | Type mismatch: inferred type is `Int` but `String` was expected |
| `UnresolvedReference.kt` | Call to a function that doesn't exist | Unresolved reference: `doesNotExist` |
| `MissingOverride.kt` | Class implements interface but doesn't implement a required member | Class must be declared abstract or implement abstract member |
| `WrongArgCount.kt` | Function called with wrong number of arguments | Too many / too few arguments |
| `NullViolation.kt` | Assigning nullable value to non-null type without check | Null can not be a value of a non-null type |
| `ReturnTypeMismatch.kt` | Function declares `Int` return but returns `String` | Type mismatch on return |
| `DuplicateDeclaration.kt` | Two functions with the same signature in the same scope | Conflicting declarations |
| `AbstractInstantiation.kt` | `val x = AbstractClass()` | Cannot create an instance of an abstract class |
| `ValReassignment.kt` | Reassigning a `val` | Val cannot be reassigned |
| `VisibilityViolation.kt` | Accessing a `private` member from outside its scope | Cannot access: it is private |
| `MissingSuperCall.kt` | Constructor of derived class doesn't call super | Primary constructor call expected |

#### `tests/fixtures/kotlin/warnings/` — Specific Warning Diagnostics Expected

Each file compiles successfully but should produce one or more warnings.

| File | Warning |
|------|---------|
| `UnusedVariable.kt` | Variable declared but never read |
| `UnusedImport.kt` | Import statement that is not referenced |
| `UnusedParameter.kt` | Function parameter never used in the body |
| `DeprecatedUsage.kt` | Call to a function annotated with `@Deprecated` |
| `RedundantCast.kt` | Cast that the compiler can prove is always safe (`as` on a value already of that type) |
| `UnnecessarySafeCall.kt` | Safe call `?.` on a non-null receiver |
| `UnreachableCode.kt` | Code after a `return` or `throw` statement |
| `RedundantIf.kt` | `if (x) true else false` instead of just `x` |

#### `tests/fixtures/kotlin/edge-cases/` — Boundary Conditions

| File | Scenario | Expected |
|------|----------|----------|
| `Empty.kt` | Completely empty file (0 bytes) | No diagnostics, no crash |
| `OnlyComments.kt` | File containing only comments (line and block) | No diagnostics |
| `OnlyPackage.kt` | File with only a package statement | No diagnostics |
| `SyntaxError.kt` | Incomplete expression (`val x = `) | Syntax error diagnostic at the incomplete expression |
| `UnicodeIdentifiers.kt` | Identifiers using non-ASCII characters (`val наименование = "test"`) | No diagnostics (valid Kotlin) |
| `LargeFile.kt` | File with ~500 declarations | No crash, diagnostics within performance budget |
| `DeepNesting.kt` | Deeply nested lambdas and control flow (10+ levels) | No crash, correct diagnostics if any |

#### `tests/fixtures/kotlin/compiler-flags/` — Flag-Dependent Behavior

Files that are **correct when the corresponding compiler flag is enabled** but produce errors without it. Used to verify compiler flag extraction and application.

| File | Required Flag | Without Flag | With Flag |
|------|---------------|-------------|-----------|
| `ContextParameters.kt` | `-Xcontext-parameters` | Error: syntax not recognized | No diagnostics |
| `MultiDollarInterpolation.kt` | `-Xmulti-dollar-interpolation` | Error: unexpected `$` | No diagnostics |
| `ContextReceivers.kt` | `-Xcontext-receivers` | Error: syntax not recognized | No diagnostics (deprecated but still used in the wild) |

Each flag-dependent file should be tested **twice**: once without the flag (expecting errors) and once with the flag (expecting clean compilation). This directly validates the critical compiler flag extraction pipeline.

### 3.3 Expected-Output Format

Each fixture `.kt` file begins with a structured comment block documenting the expected outcome:

```kotlin
// FIXTURE: errors/TypeMismatch.kt
// EXPECT-ERROR: line 8, "Type mismatch"
// EXPECT-ERROR: line 12, "Type mismatch"

package fixtures.errors

fun main() {
    val x: String = 42       // error here
    println(x)

    val y: Int = "hello"     // error here
    println(y)
}
```

For correct files:

```kotlin
// FIXTURE: correct/BasicConstructs.kt
// EXPECT: no-diagnostics

package fixtures.correct
// ...
```

For warning files:

```kotlin
// FIXTURE: warnings/UnusedVariable.kt
// EXPECT-WARNING: line 7, "unused"

package fixtures.warnings

fun main() {
    val unused = 42          // warning here
    println("hello")
}
```

This format lets the test harness parse expected outcomes directly from the file, eliminating the need for a separate expected-results file. The pattern (`EXPECT-ERROR`, `EXPECT-WARNING`, `EXPECT: no-diagnostics`) is simple enough to parse with regex and extend later if needed.

### 3.4 How Fixtures Are Used Per Milestone

| Milestone | What Gets Tested | How |
|-----------|-----------------|-----|
| **M1** | `correct/OutlineShowcase.kt` | Run `tree-sitter` queries, verify outline nodes match expected classes/functions |
| **M1** | `correct/MainFunction.kt`, `correct/TestClass.kt` | Run `runnables.scm` query, verify `main` and `@Test` functions are detected |
| **M1** | All `correct/` files | Run `highlights.scm`, visually inspect or diff against expected highlight output |
| **M1** | `edge-cases/SyntaxError.kt` | Verify tree-sitter produces a partial parse tree with `ERROR` node, does not hang |
| **M2** | All `correct/` files | Open via LSP, assert zero diagnostics published |
| **M2** | All `errors/` files | Open via LSP, assert expected diagnostics at expected lines |
| **M2** | All `warnings/` files | Open via LSP, assert expected warnings at expected lines |
| **M2** | All `edge-cases/` files | Open via LSP, assert no crash, expected behavior per file |
| **M2** | `compiler-flags/` files (without flags) | Open via LSP without flags, assert errors |
| **M2** | `compiler-flags/` files (with flags) | Open via LSP with flags via `gradle-compiler-flags/` fixture project, assert no errors |
| **M3** | `correct/` files with identifiers | Hover, go-to-def, completion at known cursor positions, assert expected results |
| **M3** | `correct/TestClass.kt` | Completion after `assert`, verify JUnit assertions appear |
| **M4** | `edge-cases/LargeFile.kt` | Measure diagnostic latency, assert within performance budget |

---

## 4. End-to-End / Conformance Tests

### LSP Protocol Conformance

Verify that the server correctly implements the LSP protocol handshake and capability negotiation.

- Send `initialize` with client capabilities, verify server capabilities in response
- Verify `textDocumentSync` is set to `Full` (v1 strategy)
- Verify declared capabilities match implemented methods
- Send `shutdown`, verify response, send `exit`, verify process terminates
- Send a request after `shutdown`, verify error response
- Verify `Content-Length` headers on all outgoing messages

### Request/Response Shape Validation

Use `lsp-types` to construct test requests and assert that responses conform to the expected LSP types. This catches serialization bugs.

### Snapshot Testing

Use the `insta` crate for snapshot testing of serialized JSON-RPC messages. This catches unintended changes to the wire format.

```rust
#[test]
fn test_initialize_response_shape() {
    let response = server.initialize(init_params()).await;
    insta::assert_json_snapshot!(response);
}
```

Snapshot files are checked in and reviewed in pull requests. Update snapshots deliberately with `cargo insta review`.

---

## 5. CI Pipeline

### Test Stages

| Stage | Trigger | Tests Run | Timeout |
|-------|---------|-----------|---------|
| Unit tests | Every push, every PR | `cargo test`, `./gradlew :sidecar:test` | 10 min |
| Mock sidecar integration | Every push, every PR | `cargo test --features mock-integration` | 10 min |
| Real sidecar integration | Push to `main`, PRs | `cargo test --features integration` | 20 min |
| Cross-platform build | Push to `main`, PRs | Build matrix (4 targets), no extra tests | 15 min |

### CI Configuration

- **Rust toolchain**: stable, pinned to a specific version in `rust-toolchain.toml`
- **JDK**: 17 (for sidecar tests), installed via `actions/setup-java`
- **Gradle**: wrapper included in fixtures, no system Gradle required
- **Caching**: Cache `~/.cargo/registry`, `~/.cargo/git`, `target/`, `.gradle/` for faster builds
- **Failure behavior**: Unit tests and mock integration tests block merge. Real sidecar integration tests are advisory (allowed to fail on platform-specific issues without blocking).

---

## 6. Regression Testing

### Compiler Flag Extraction Regression Suite

Maintain a collection of `build.gradle.kts` snippets that exercise different patterns of compiler flag declaration:

```kotlin
// Pattern 1: kotlinOptions block
kotlinOptions {
    freeCompilerArgs += listOf("-Xcontext-parameters")
}

// Pattern 2: compilerOptions DSL (Kotlin 1.8+)
compilerOptions {
    freeCompilerArgs.addAll("-Xcontext-parameters", "-Xmulti-dollar-interpolation")
}

// Pattern 3: tasks.withType
tasks.withType<KotlinCompile> {
    kotlinOptions.freeCompilerArgs += "-Xcontext-parameters"
}
```

Each pattern has an expected output (list of extracted flags). Add new patterns when a user reports that flag extraction failed for their build file.

### "Previously Broken" Kotlin Files

Keep a suite of Kotlin source files that previously caused incorrect behavior:

- Files that triggered false-positive diagnostics
- Files that caused the sidecar to crash
- Files that produced incorrect completions
- Files that caused infinite loops or excessive memory usage

Each file is annotated with the expected behavior (e.g., "no diagnostics", "completion includes X"). Run these as part of the real sidecar integration tests.

### Architecture Tests

Verify that internal module dependencies follow the allowed dependency graph defined in
`architecture.md` section 4.1. These tests run as part of `cargo test` and fail the
build if a module imports another module it is not allowed to depend on.

**How it works:**

A test scans every `.rs` file under `src/` for `use crate::` and `crate::` path
expressions. For each file, it extracts the set of internal modules referenced and
checks them against the allowed dependency table. Any import that violates the table
is a test failure.

```rust
#[test]
fn architecture_dependency_rules() {
    // Maps each module to its allowed internal dependencies.
    let allowed: HashMap<&str, Vec<&str>> = HashMap::from([
        ("main",    vec!["server", "config", "error"]),
        ("server",  vec!["bridge", "state", "config", "error"]),
        ("bridge",  vec!["jsonrpc", "state", "config", "error"]),
        ("project", vec!["config", "error"]),
        ("state",   vec!["error"]),
        ("jsonrpc", vec!["error"]),
        ("config",  vec!["error"]),
        ("error",   vec![]),
    ]);

    for (module, allowed_deps) in &allowed {
        let path = format!("src/{}.rs", module);
        let source = std::fs::read_to_string(&path)
            .unwrap_or_else(|_| panic!("could not read {}", path));

        for line in source.lines() {
            if let Some(dep) = extract_crate_dep(line) {
                assert!(
                    allowed_deps.contains(&dep.as_str()),
                    "{}.rs imports crate::{}, which is not in its allowed \
                     dependencies: {:?}",
                    module, dep, allowed_deps
                );
            }
        }
    }
}
```

This is a compile-time-free, zero-dependency test. It parses source text directly
rather than relying on external tools. It runs in milliseconds and catches dependency
violations before they reach code review.

**What it catches:**
- A new `use crate::server` added to `bridge.rs` (bridge must not know about the LSP layer)
- `error.rs` importing any internal module (error is a leaf dependency)
- `state.rs` importing `bridge` (the document store must be passive)

**What it does not catch:**
- Dependencies on external crates (those are reviewed in `Cargo.toml`)
- Transitive dependency direction (e.g., if module A calls module B which calls module C,
  the test only checks direct imports, not call chains)

### Performance Regression

Track key metrics across CI runs:

- Rust binary startup time (time from process start to LSP `initialize` response)
- Sidecar startup time (time from spawn to first successful health check)
- Completion latency (time from request to response on a standard fixture)
- Memory usage (RSS of Rust binary and sidecar after analysis of standard fixture)

Store metrics as CI artifacts. Compare against baseline on PRs. Flag regressions that exceed thresholds (e.g., completion latency > 2x baseline).
