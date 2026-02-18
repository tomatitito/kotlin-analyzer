# Kotlin Analysis API Standalone Spike - Findings

## 1. Does StandaloneAnalysisAPISession work outside IntelliJ?

**Yes.** The Analysis API works fully in standalone mode without any IntelliJ IDE process. The `buildStandaloneAnalysisAPISession` function creates a self-contained session with its own IntelliJ Platform infrastructure (PSI, VFS, project model) bootstrapped from the `kotlin-compiler` fat JAR.

Key observations:
- The session requires a `Disposable` for lifecycle management
- `buildKtModuleProvider` DSL configures the project structure (source modules, libraries, SDK)
- The session exposes `modulesWithFiles` which provides parsed `KtFile` PSI trees
- `analyze(ktFile) { ... }` opens a `KaSession` scope for analysis operations
- Multiple sessions can be created sequentially (each with its own disposable)
- The second session creates faster (122ms vs 544ms) due to JVM warmup and cached platform initialization

## 2. Can diagnostics be collected? What format are they in?

**Yes.** Diagnostics are collected via `KtFile.collectDiagnostics(filter)` within an `analyze` block.

Each diagnostic (`KaDiagnosticWithPsi`) provides:
- `severity`: `KaSeverity` enum (ERROR, WARNING, INFO)
- `factoryName`: String identifier (e.g., `TYPE_MISMATCH`, `INITIALIZER_TYPE_MISMATCH`, `UNRESOLVED_REFERENCE`)
- `defaultMessage`: Human-readable description (e.g., "Type mismatch: inferred type is 'String', but 'Int' was expected.")
- `psi`: The PSI element the diagnostic is attached to
- `textRanges`: List of `TextRange` objects with start/end offsets
- `diagnosticClass`: KClass reference for programmatic handling

Position info (line/column) requires converting from text offsets via `ktFile.viewProvider.document`:
```kotlin
val line = document.getLineNumber(textRange.startOffset) + 1  // 1-based
val col = textRange.startOffset - document.getLineStartOffset(lineNum) + 1
```

The `KaDiagnosticCheckerFilter` enum controls what checkers run:
- `ONLY_COMMON_CHECKERS` - Language-level errors only
- `ONLY_EXTENDED_CHECKERS` - IDE-specific inspections only
- `EXTENDED_AND_COMMON_CHECKERS` - Both (recommended for LSP)

## 3. Can compiler flags be applied? Specifically `-Xcontext-parameters`?

**Yes.** Compiler flags map to `LanguageFeature` enum values, configured via `LanguageVersionSettingsImpl`:

```kotlin
buildKtSourceModule {
    languageVersionSettings = LanguageVersionSettingsImpl(
        languageVersion = LanguageVersion.KOTLIN_2_1,
        apiVersion = ApiVersion.KOTLIN_2_1,
        specificFeatures = mapOf(
            LanguageFeature.ContextParameters to LanguageFeature.State.ENABLED
        )
    )
}
```

The mapping from `-X` flags to `LanguageFeature` values:
- `-Xcontext-parameters` -> `LanguageFeature.ContextParameters`
- Other `-X` flags map similarly (see `LanguageFeature` enum in kotlin-compiler)

Verification: `ContextParams.kt` using `context(logger: Logger)` syntax analyzed with **zero diagnostics** when the feature was enabled.

## 4. Startup time

Measured on Apple Silicon (M-series), JDK 17 (Temurin):

| Phase | First Session | Second Session |
|---|---|---|
| Session creation | ~544ms | ~122ms |
| Analysis (1 small file) | ~596ms | ~105ms |
| **Total per file** | **~1140ms** | **~227ms** |

Total wall-clock time for both analyses: **~1400ms**

Notes:
- First session includes JVM class loading and IntelliJ Platform bootstrap
- Second session reuses warmed JVM but creates a fresh analysis environment
- For an LSP server, the session would be created once and reused, so the first-analysis cost is a one-time startup penalty

## 5. Memory usage

| Configuration | Max Heap | Used Memory | Allocated |
|---|---|---|---|
| Default (no limit) | 12,288MB | 171MB | 272MB |
| -Xmx512m | 512MB | 97MB | 150MB |

The Analysis API works within a 512MB heap for small files. For larger projects, 1-2GB would be a reasonable default. The memory usage is dominated by:
- IntelliJ Platform infrastructure (PSI, VFS, indexes)
- FIR (Frontend IR) trees for analyzed modules
- Kotlin stdlib deserialized stubs

## 6. Dependencies and JAR sizes

### Required Dependencies

| Artifact | Source | Size |
|---|---|---|
| `kotlin-compiler:2.1.20` | Maven Central | 57,006KB (55.7MB) |
| `analysis-api-for-ide:2.1.20` | JetBrains Space | 890KB |
| `analysis-api-k2-for-ide:2.1.20` | JetBrains Space | 3,635KB |
| `low-level-api-fir-for-ide:2.1.20` | JetBrains Space | 1,475KB |
| `symbol-light-classes-for-ide:2.1.20` | JetBrains Space | 726KB |
| `analysis-api-impl-base-for-ide:2.1.20` | JetBrains Space | 380KB |
| `analysis-api-standalone-for-ide:2.1.20` | JetBrains Space | 198KB |
| `analysis-api-platform-interface-for-ide:2.1.20` | JetBrains Space | 184KB |
| `caffeine:3.1.8` | Maven Central | 868KB |
| Other transitive (annotations, coroutines, trove4j, etc.) | Maven Central | ~5MB |

**Total: 21 JARs, ~70MB**

The `kotlin-compiler` JAR dominates at 55.7MB (it's a fat JAR bundling IntelliJ Platform, compiler frontend/backend, and runtime infrastructure).

### Repository Configuration

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-ide-plugin-dependencies")
}
```

### Critical: `isTransitive = false`

All `-for-ide` artifacts **must** be declared with `isTransitive = false`. Their POMs reference internal, unpublished artifacts (e.g., `analysis-api-standalone-base`, `analysis-api-fir`) that don't exist in any public repository. The `-for-ide` JARs are fat JARs that already contain these merged modules internally.

## 7. Surprises, undocumented requirements, and blockers

### Surprises

1. **Artifact naming**: The Analysis API artifacts are NOT published with their internal names (`analysis-api-standalone`, `analysis-api-fir`, etc.). They're only available as `-for-ide` variants on JetBrains Space repo. This is completely undocumented.

2. **`isTransitive = false` is mandatory**: The `-for-ide` POMs declare dependencies on non-existent artifacts with `exclude *:*`. Despite the excludes, Gradle still tries to resolve them and fails. Setting `isTransitive = false` at the Gradle level is the only workaround.

3. **Caffeine dependency**: The FIR session provider (`KaFirSessionProvider`) uses Caffeine cache internally, but it's not bundled in any of the JARs. This runtime dependency is completely undocumented and causes a `NoClassDefFoundError` if missing.

4. **Multiple stdlib JARs needed**: Finding and providing ALL kotlin-stdlib JARs (base, jdk7, jdk8) is important. Using only `kotlin-stdlib-jdk8` causes `UNRESOLVED_REFERENCE` for basic stdlib functions like `println`.

5. **Two sessions cannot share global state**: The `buildStandaloneAnalysisAPISession` function sets up global application-level services. Creating a second session after disposing the first works, but creating two concurrent sessions would likely fail. For an LSP server, a single long-lived session is the correct approach.

### No blockers found

The API is stable, well-designed, and provides everything needed for a language server. The DSL builder pattern is ergonomic and type-safe.

## 8. Recommendations for kotlin-analyzer

### Architecture

1. **Single long-lived session**: Create one `StandaloneAnalysisAPISession` at LSP server startup and keep it alive. Use `analyze(ktFile) { ... }` for each analysis request.

2. **Module structure**: Map Gradle/Maven modules to `KaSourceModule` instances. Each module gets its own `languageVersionSettings`, dependencies, and source roots.

3. **Classpath management**: Use `buildKtSdkModule` + `addBinaryRootsFromJdkHome` for JDK, and `buildKtLibraryModule` for each dependency JAR. Parse classpath from build tool integration.

4. **Compiler flags**: Map `compilerOptions` from build scripts to `LanguageVersionSettingsImpl`. The `LanguageFeature` enum has entries for all `-X` flags.

### Performance

5. **Memory budget**: Set `-Xmx1g` to `-Xmx2g` for the LSP server process. 512MB works for small files but real projects will need more.

6. **Startup cost**: Expect ~500ms-1s for initial session creation. First analysis of any file will be slower than subsequent ones. Consider warming up the session at startup.

7. **Incremental analysis**: The Analysis API supports per-file analysis. Changed files can be re-analyzed individually without recreating the session.

### Dependencies

8. **Pin to `kotlin-compiler` version**: The Analysis API artifacts MUST match the `kotlin-compiler` version exactly. Version mismatches cause `NoSuchMethodError` and `IncompatibleClassChangeError`.

9. **JetBrains Space repo**: Document the dependency on the JetBrains Space Maven repository. Consider mirroring these artifacts for build reliability.

10. **`isTransitive = false` pattern**: Extract into a shared Gradle function (as done in this spike) to keep the build file clean.
