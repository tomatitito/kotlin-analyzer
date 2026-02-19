# Compiler Integration

This document describes how the kotlin-analyzer JVM sidecar integrates with the Kotlin compiler for semantic analysis. It covers the target compiler API, project model extraction, incremental analysis, and runtime characteristics.

---

## Target Compiler API: Analysis API (Standalone, FIR-backed)

kotlin-analyzer targets the **Kotlin Analysis API** with the **FIR (K2) backend**. This is the new compiler frontend that replaces the legacy K1 pipeline.

### Key Entry Points

| API | Purpose |
|-----|---------|
| `KtAnalysisSession` | Scoped session for performing analysis operations |
| `analyze(KtElement) { ... }` | Entry point to obtain a `KtAnalysisSession` for a given element |
| `KtSymbol` | Represents declarations (functions, classes, properties, etc.) |
| `KtType` | Represents resolved types |
| `KtDiagnostic` | Compiler diagnostics (errors, warnings) from resolution |

### Target Artifacts

> **Validated by spike:** The standalone Analysis API has been proven to work outside IntelliJ. See [spike/analysis-api/FINDINGS.md](../spike/analysis-api/FINDINGS.md) for full results and the working project at `spike/analysis-api/`.

The Analysis API artifacts are published to the **JetBrains Space** repository (not Maven Central) with a `-for-ide` suffix. All `-for-ide` artifacts **must** use `isTransitive = false` because their POMs reference unpublished internal artifacts.

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-ide-plugin-dependencies")
}

val analysisApiVersion = "2.1.20"  // Latest stable with published -for-ide artifacts

dependencies {
    // From Maven Central
    implementation("org.jetbrains.kotlin:kotlin-compiler:$analysisApiVersion")

    // From JetBrains Space — all with isTransitive = false
    listOf(
        "analysis-api-for-ide",
        "analysis-api-standalone-for-ide",
        "analysis-api-k2-for-ide",
        "analysis-api-impl-base-for-ide",
        "analysis-api-platform-interface-for-ide",
        "low-level-api-fir-for-ide",
        "symbol-light-classes-for-ide",
    ).forEach {
        implementation("org.jetbrains.kotlin:$it:$analysisApiVersion") { isTransitive = false }
    }

    // Undocumented runtime dependency — FIR session provider uses Caffeine cache internally
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
}
```

**Gotchas discovered during spike:**
- The `-for-ide` artifact naming is undocumented; the internal names (`analysis-api-standalone`, `analysis-api-fir`) do not exist in any public repository.
- All three kotlin-stdlib JARs (base, jdk7, jdk8) must be provided on the analysis classpath; omitting any causes `UNRESOLVED_REFERENCE` for basic functions like `println`.
- Two concurrent sessions cannot coexist; the sidecar must use a single long-lived session.
- Artifact versions must match `kotlin-compiler` exactly; mismatches cause `NoSuchMethodError`.

### API Stability

The Analysis API surface is annotated with `@InternalApi` and experimental opt-in markers. There is no public stability guarantee from JetBrains.

Mitigations:

1. **Pin to a specific Kotlin version** (e.g., 2.3.10). Do not track Kotlin releases eagerly.
2. **Abstract behind a stable interface layer.** The sidecar defines its own `CompilerBridge` interface that encapsulates all direct Analysis API calls. When the upstream API changes, only the bridge implementation needs updating -- the rest of the sidecar and the Rust frontend are unaffected.

### Version Management Strategy

The sidecar ships with a pinned Analysis API version. All `-for-ide` artifacts and `kotlin-compiler` must be at the same version — mismatches cause `NoSuchMethodError` (confirmed by spike).

#### v1: Single pinned version

v1 ships with Kotlin 2.3.10 and documents this as a supported version constraint. Users whose projects target a different Kotlin minor version may see false-positive diagnostics for new language features or missing resolution for new stdlib APIs.

This is the same model rust-analyzer uses: it tracks Rust compiler releases rather than attempting to support every version simultaneously.

#### Tracking new Kotlin releases

JetBrains ships stable Kotlin releases roughly every 3-4 months.

Update process:

1. **Automated version check.** A scheduled CI workflow checks JetBrains Space for new `-for-ide` artifact versions weekly and opens a PR when one appears. Dependabot does not support JetBrains Space repos, so this is a custom script (`curl` + version comparison against the Maven metadata XML).
2. **Spike validation gate.** The `spike/analysis-api/` project runs as a CI fixture against the new version. If session creation, diagnostics, and compiler flag application still work, the update is safe. If not, the PR flags a breaking API change for manual investigation.
3. **Patch vs. minor updates.** Patch updates within a minor line (e.g., 2.3.10 -> 2.3.20) are expected to be safe and can be merged after CI passes. Minor version bumps (e.g., 2.3.x -> 2.4.0) may include API changes and require manual review of the `CompilerBridge` interface.
4. **Release cadence.** kotlin-analyzer releases a new version within two weeks of each stable Kotlin release, after validation.

#### Future: Matching the project's Kotlin version

The pinned-version approach breaks when a user's project uses a Kotlin version newer than what the sidecar ships with. The `CompilerBridge` abstraction layer is the seam for addressing this in a future version. Options to evaluate post-v1:

- **Download matching artifacts on first use.** Detect the project's Kotlin version from Gradle (the Tooling API already extracts this), pull the matching `-for-ide` artifacts from JetBrains Space, and cache them locally. Adds a network dependency and first-run delay but eliminates version mismatch entirely.
- **Bundle multiple compiler versions.** Ship 2-3 recent versions and select at runtime. Increases the distribution size (~70 MB per version) but works offline.
- **Warn on mismatch.** Detect the project's Kotlin version, compare against the bundled version, and surface a diagnostic if they differ. The user at least understands why features are missing.

---

## Why Not K1

The K1 compiler frontend uses `KotlinCoreEnvironment`, `BindingContext`, `AnalysisResult`, and `DeclarationDescriptor`. These APIs are **deprecated as of Kotlin 2.0** and are in maintenance mode with no new feature work.

Reasons to avoid K1:

- **Kotlin 2.x language features** -- context parameters, guard conditions in `when`, name-based destructuring -- are implemented only in the FIR frontend. Building on K1 means these features produce false-positive diagnostics or are unavailable entirely.
- **No investment from JetBrains.** K1 receives bug fixes only. Performance improvements, new resolution capabilities, and API enhancements go exclusively to FIR/K2.
- **Building on K1 in 2026 is building on a dead foundation.** The entire Kotlin toolchain (IntelliJ plugin, compiler, build tools) has migrated to K2. A new project starting today has no reason to target K1.

---

## K1 to K2 API Mapping

For reference when reading older Kotlin tooling code (e.g., fwcd/kotlin-language-server) or JetBrains documentation that still references K1 APIs:

| K1 API | K2 / FIR Equivalent |
|--------|---------------------|
| `KotlinCoreEnvironment` | `KtAnalysisSession` / `StandaloneAnalysisAPISession` |
| `BindingContext` | `analyze(KtElement) { ... }` blocks |
| `DeclarationDescriptor` | `KtSymbol` (via Analysis API) |
| `AnalysisResult` | `KtDiagnostic` collection from resolution |

The mental model shift: K1 performed a single monolithic analysis pass producing a `BindingContext` that was queried after the fact. K2's Analysis API uses scoped `analyze { }` blocks where resolution happens on demand within the session.

---

## Compiler Flag Extraction

This is a **hard requirement** and the primary differentiator of kotlin-analyzer over existing LSPs. Existing language servers do not honor compiler flags from Gradle build scripts, causing false-positive diagnostics for any Kotlin feature gated behind an experimental flag.

### What Gets Extracted

Flags from `freeCompilerArgs` / `kotlinOptions` in `build.gradle(.kts)`, including:

- `-Xcontext-parameters`
- `-Xvalue-classes`
- `-Xmulti-dollar-interpolation`
- `-Xcontext-receivers` (deprecated but still used)
- Any other `-X` experimental flags

These flags are applied to the `StandaloneAnalysisAPISession` via `LanguageVersionSettingsImpl.specificFeatures`, mapping each `-X` flag to its corresponding `LanguageFeature` enum value:

```kotlin
// Example: applying -Xcontext-parameters
languageVersionSettings = LanguageVersionSettingsImpl(
    languageVersion = LanguageVersion.KOTLIN_2_1,
    apiVersion = ApiVersion.KOTLIN_2_1,
    specificFeatures = mapOf(
        LanguageFeature.ContextParameters to LanguageFeature.State.ENABLED
    )
)
```

This mechanism was validated in the [Analysis API spike](../spike/analysis-api/FINDINGS.md): `context(logger: Logger)` syntax analyzed with zero diagnostics when `ContextParameters` was enabled.

### Extraction Mechanism

Flags are extracted via the **Gradle Tooling API**, which connects to the project's Gradle daemon and queries the resolved build model. This handles:

- Standard `kotlinOptions { }` / `compilerOptions { }` blocks
- Convention plugins that set flags transitively
- Version catalogs (`libs.versions.toml`)
- `buildSrc` and included build plugins
- `gradle.properties` entries (e.g., `kotlin.code.style`)
- Per-module flag divergence in multi-module projects

### Build File Watching

The Rust frontend watches the following files for changes and triggers re-extraction with debounce:

- `build.gradle.kts` / `build.gradle`
- `settings.gradle.kts` / `settings.gradle`
- `buildSrc/**`
- `gradle.properties`
- `libs.versions.toml` (version catalog)
- `.editorconfig` (affects formatting behavior; see `lsp-protocol.md` §7.3)

### Caching

The extracted project model (source roots, classpath, compiler flags) is cached to disk as JSON. On subsequent startups, the sidecar loads the cached model immediately and begins analysis, then re-extracts from Gradle in the background to detect changes.

### Performance

Expect **5-15 seconds** for initial Gradle configuration time, depending on project complexity and whether the Gradle daemon is already running. The disk cache eliminates this cost on warm starts.

---

## Incremental Analysis Model

### v1: Module-Level Re-analysis

The Kotlin compiler resolves at module granularity, not file granularity. The v1 incremental strategy reflects this:

1. Maintain a persistent `StandaloneAnalysisAPISession` for the active module.
2. On file change (save with debounce), invalidate that file's state within the session and re-analyze the module.
3. Collect per-file diagnostics from the module-wide analysis pass.
4. Publish diagnostics to the Rust frontend, which forwards them via LSP `textDocument/publishDiagnostics`.

This approach is straightforward and fast enough for projects under approximately 500 source files.

### Future: File-Level Dependency Tracking

A file-level dependency graph enables smarter invalidation: when file A changes, only re-analyze A and its transitive dependents rather than the entire module. This requires tracking import relationships and symbol references across files.

Beyond that, **declaration-level** tracking (re-analyze only when a function signature or class hierarchy changes) is a further optimization, but significantly more complex. It is deferred until performance demands it.

---

## Compose Compiler Plugin Support

Compose adoption is high in the Kotlin ecosystem. Without the Compose compiler plugin loaded into the analysis session, `@Composable` annotations produce false-positive diagnostics because the compiler does not recognize the Compose-specific transformations.

### Detection

The sidecar detects Compose usage by checking for either of these Gradle plugins in the extracted project model:

- `org.jetbrains.compose`
- `org.jetbrains.kotlin.plugin.compose`

### Integration

When detected, the Compose compiler plugin artifact is loaded into the analysis session's compiler plugin classpath. This ensures:

- `@Composable` functions are recognized and validated correctly
- Compose-specific compiler checks (e.g., stability inference, recomposition rules) run as expected
- No false-positive diagnostics on valid Compose code

This is a high-priority integration given the prevalence of Compose in Android and Kotlin Multiplatform projects.

---

## KAPT / KSP Generated Sources

Annotation processors (KAPT) and Kotlin Symbol Processing (KSP) generate source files that the main application code depends on (e.g., Dagger/Hilt components, Room DAOs, Moshi adapters).

### v1 Approach

The sidecar does **not** run KAPT or KSP itself. Instead:

1. Add `build/generated/source/kapt/` and `build/generated/ksp/` to the analysis session's source roots.
2. Users must run `./gradlew kaptGenerateStubs` or `./gradlew kspKotlin` at least once before the language server can resolve generated symbols.

This is a pragmatic tradeoff. Running annotation processors within the sidecar would add substantial complexity, memory overhead, and startup time.

### User Requirements

Users of projects that depend on KAPT/KSP-generated code must:

```shell
# For KAPT-based projects (Dagger, Room, etc.)
./gradlew kaptGenerateStubs

# For KSP-based projects (Moshi, Koin, etc.)
./gradlew kspKotlin
```

This must be re-run when annotation-processed declarations change. The language server surfaces a diagnostic or status message when expected generated source directories are missing.

---

## Java Interop

Most Kotlin/JVM projects mix Kotlin and Java source files. The Analysis API handles Java interop natively.

### Classpath Configuration

Java sources (or their compiled `.class` files) are included in the analysis session classpath. This enables:

- Resolution of Java types, methods, and fields from Kotlin code
- Correct handling of Java nullability annotations (`@Nullable`, `@NotNull`)
- Platform type inference for unannotated Java APIs

### Cross-Language Navigation

- **Kotlin to Java**: Go-to-definition from Kotlin code to Java declarations resolves via the classpath.
- **Java to Kotlin**: When Java files reference Kotlin classes, the compiled Kotlin `.class` files (with Kotlin metadata annotations) provide the necessary type information.

No additional configuration is required. The Gradle Tooling API extracts both Kotlin and Java source roots as part of the project model.

---

## .kts Scripting Support

### v1 Scope

`.kts` files (Kotlin scripts) are registered in the language configuration for syntax highlighting via tree-sitter. **Semantic analysis of `.kts` files is out of scope for v1.**

### Why Deferred

Script analysis requires different compiler configuration than regular Kotlin source files:

- **Script definitions** -- each script type (`build.gradle.kts`, `settings.gradle.kts`, standalone `.kts`) has a different implicit API surface.
- **Implicit imports** -- `build.gradle.kts` files implicitly import the entire Gradle API; standalone scripts have `kotlin.script.experimental` infrastructure.
- **Classpath differences** -- `build.gradle.kts` needs the Gradle API and all applied plugin APIs on the classpath, which is a fundamentally different classpath from the project's own source code.

Providing incorrect or partial script analysis would produce more noise than value. This is deferred to a future version.

---

## JDK Version Detection

The correct JDK standard library must be on the analysis session classpath. Using the wrong JDK version causes incorrect resolution of `java.*` APIs and missing or phantom platform types.

### Discovery Order

The sidecar resolves the JDK in the following order:

1. `KOTLIN_LS_JAVA_HOME` environment variable (explicit user override)
2. `java.toolchain` configuration in the Gradle build (extracted via Tooling API)
3. `JAVA_HOME` environment variable
4. `java` binary on `PATH`

### Minimum JDK Version

The sidecar itself requires **JDK 17+** to run (Kotlin 2.x compiler requires it). The project being analyzed may target a different JDK version; in that case, the project's target JDK stdlib is placed on the analysis classpath while the sidecar runs on JDK 17+.

---

## Memory Estimates

| Component | Estimated Memory |
|-----------|-----------------|
| `kotlin-compiler-embeddable` classes | 80-120 MB |
| Resolved module with typical classpath | 50-150 MB |
| FIR / Analysis API session (~200 source files) | 50-100 MB |
| **Total (single module)** | **200-400 MB** |

### Configuration

- The sidecar targets `-Xmx512m` as the default heap limit for single-module projects.
- `-Xmx` is exposed as a user-configurable setting (`kotlin-analyzer.sidecar.maxMemory`).
- Consider `-XX:+UseZGC` for lower GC pause times, reducing latency spikes during analysis.

### Multi-Module Considerations (v2)

Memory scales linearly with the number of loaded modules. For multi-module support in v2, **lazy loading is essential**: only modules with currently open files are analyzed. Modules without open files are represented only by their compiled output on the classpath.

---

## Sidecar Startup Time

| Phase | Expected Duration |
|-------|-------------------|
| JVM startup | 200-500 ms |
| Loading compiler classes | 1-2 s |
| First full module analysis | 3-15 s (depends on project size) |
| **Total time to first diagnostics** | **5-20 s** |

### User Experience During Startup

The Rust frontend provides **LSP progress notifications** (`window/workDoneProgress`) during the sidecar startup window. The editor displays a progress indicator so the user knows semantic features are loading.

Tree-sitter-based features (syntax highlighting, outline, brackets, indentation) are available immediately -- they do not depend on the sidecar.
