# Fix: False Error Diagnostics for `context` Keyword and Arrow `raise` DSL

## Problem

Users see error diagnostics in `my-zed` when using Kotlin's `context` keyword (context parameters/receivers) and Arrow's `raise` DSL, even when the required compiler flags (`-Xcontext-receivers` or `-Xcontext-parameters`) are set in their `build.gradle(.kts)`.

Expected behavior: code using `context(Raise<MyError>)` or `context(logger: Logger)` should produce zero diagnostics when the appropriate flag is in `freeCompilerArgs`.

Typical error messages from the compiler:
- `"The feature "context receivers" is experimental and should be enabled explicitly"`
- `"The feature "context parameters" is experimental and should be enabled explicitly"`
- `UNRESOLVED_REFERENCE` on `raise`, `Raise`, or context-bound names

## Architecture Context

The compiler flag pipeline has five stages:

```
build.gradle → Gradle init script → Rust parser → JSON-RPC initialize → Analysis API session
```

Any break in this chain produces false-positive diagnostics.

## Investigation Plan

### Phase 1: Observe and Reproduce (~15 min)

Use `my-zed` to capture the exact error messages and JSON-RPC traffic.

- [ ] **1.1** Open a Kotlin project that uses `context` and/or Arrow `raise` in `my-zed`
- [ ] **1.2** Open a file that uses the `context` keyword — note the exact diagnostic messages and `factoryName`/`code` values in the sidecar stderr
- [ ] **1.3** Open a file that uses Arrow's `raise` DSL — note the exact diagnostic messages
- [ ] **1.4** Classify each diagnostic:
  - **(A) Feature-gated error** — "The feature ... is experimental" → flag not reaching the Analysis API
  - **(B) UNRESOLVED_REFERENCE on `Raise`/`raise`** — Arrow JARs missing from classpath
  - **(C) UNRESOLVED_REFERENCE on context-bound names** — virtual file module association issue (see `virtual-file-member-resolution.md`)
  - **(D) Syntax error** — parser doesn't recognize `context(...)` at all → wrong Kotlin language version

### Phase 2: Verify Flag Extraction (~15 min)

Check whether flags make it out of Gradle.

- [ ] **2.1** In the target project, run the init script manually and inspect output:
  ```bash
  cd /path/to/user/project
  cp /dev/stdin .kotlin-analyzer-init.gradle <<< '<paste INIT_SCRIPT>'
  ./gradlew --init-script .kotlin-analyzer-init.gradle kotlinAnalyzerExtract
  ```
  Look for `COMPILER_FLAG=-Xcontext-receivers` or `COMPILER_FLAG=-Xcontext-parameters` in the output between the `---KOTLIN-ANALYZER-START---` markers.

- [ ] **2.2** If flags are **missing**: the Gradle init script may not be finding them. Possible causes:
  - Project uses `kotlinOptions { }` (old DSL) instead of `compilerOptions { }` — the init script only checks `task.compilerOptions.freeCompilerArgs`
  - Flags are set via a convention plugin or `subprojects { }` block and the task hasn't been configured yet at extraction time
  - Flags are set via `kotlin { compilerOptions { } }` top-level extension (Kotlin 2.0+ DSL) instead of per-task

- [ ] **2.3** If flags are **present**: the issue is downstream — proceed to Phase 3.

### Phase 3: Verify Flag Passthrough to Sidecar (~10 min)

- [ ] **3.1** Check `my-zed` terminal output for the sidecar's initialization log. Look for:
  ```
  CompilerBridge: initializing session
  ```
  The flags should appear in the JSON-RPC `initialize` request params. Add temporary logging in `bridge.rs` if not already visible:
  ```rust
  tracing::info!("sending compilerFlags to sidecar: {:?}", config.compiler_flags);
  ```

- [ ] **3.2** Check if `mapCompilerFlag()` logs any `"unknown compiler flag"` messages — this would mean the flag string doesn't match any known pattern.

- [ ] **3.3** Verify the `languageFeatures` map is non-empty after flag mapping. The sidecar should log the enabled features (add logging if missing):
  ```kotlin
  System.err.println("CompilerBridge: languageFeatures=$languageFeatures")
  ```

### Phase 4: Verify Classpath for Arrow (~10 min)

For Arrow's `raise` DSL, the `Raise` type must be on the analysis classpath.

- [ ] **4.1** Check sidecar stderr for classpath size: `CompilerBridge: classpath=N entries`
- [ ] **4.2** Add targeted logging for Arrow JARs (similar to existing Spring JAR logging):
  ```kotlin
  classpath.filter { "arrow" in it.lowercase() }.forEach {
      System.err.println("CompilerBridge: arrow JAR on classpath: $it")
  }
  ```
- [ ] **4.3** If Arrow JARs are missing: check that `compileClasspath.resolve()` in the Gradle init script includes transitive dependencies. Arrow is typically pulled in via `implementation("io.arrow-kt:arrow-core:...")`.

### Phase 5: Verify Virtual File Module Association (~10 min)

If flags reach the session but diagnostics still appear, the issue may be in how virtual files (editor content) inherit language settings.

- [ ] **5.1** Check whether the custom `KotlinProjectStructureProvider` was registered successfully:
  ```
  CompilerBridge: registered custom KotlinProjectStructureProvider for virtual file support
  ```
  vs the warning:
  ```
  CompilerBridge: WARNING — failed to register custom module provider: ...
  ```

- [ ] **5.2** If the provider registration failed: the reflection-based service replacement (lines 3126-3136 of `CompilerBridge.kt`) may have broken due to API changes in newer `kotlin-compiler-embeddable` versions. The `MockComponentManager` and `picoContainer` field names are internal APIs.

- [ ] **5.3** Check whether analysis is using a session file (full module context) or a KtPsiFactory file (depends on custom provider). Look for:
  ```
  CompilerBridge: findKtFile(...) — session file content matches virtualFiles, using session file
  ```
  vs:
  ```
  CompilerBridge: findKtFile(...) — using virtualFiles content (N chars)
  ```

## Likely Fixes

### Fix A: Expand Gradle Init Script to Handle `kotlinOptions` (if Phase 2.2)

The current init script only reads `task.compilerOptions.freeCompilerArgs`. Many projects still use the older `kotlinOptions` block:

```groovy
// Current: only handles compilerOptions (Kotlin 2.0+ DSL)
project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).each { task ->
    task.compilerOptions.freeCompilerArgs.get().each { flag ->
        sb.append("COMPILER_FLAG=${flag}\n")
    }
}
```

Add fallback for `kotlinOptions`:

```groovy
project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).each { task ->
    // Try new API first (compilerOptions)
    try {
        task.compilerOptions.freeCompilerArgs.get().each { flag ->
            sb.append("COMPILER_FLAG=${flag}\n")
        }
    } catch (Exception e1) {
        // Fallback to old API (kotlinOptions)
        try {
            task.kotlinOptions.freeCompilerArgs.each { flag ->
                sb.append("COMPILER_FLAG=${flag}\n")
            }
        } catch (Exception e2) {
            // Neither API available
        }
    }
}
```

Also handle the top-level `kotlin { compilerOptions { } }` extension (Kotlin 2.0+):

```groovy
try {
    def kotlinExt = project.extensions.findByName("kotlin")
    if (kotlinExt != null) {
        kotlinExt.compilerOptions.freeCompilerArgs.get().each { flag ->
            if (!flags.contains(flag)) {
                sb.append("COMPILER_FLAG=${flag}\n")
                flags.add(flag)
            }
        }
    }
} catch (Exception e) {
    // kotlin extension not available or no compilerOptions
}
```

### Fix B: Add Diagnostic Logging for Flag Pipeline (quick win)

Add logging at each stage so future issues are self-diagnosing:

1. **Rust side** (`bridge.rs`): log the flags sent in the `initialize` request
2. **Sidecar side** (`CompilerBridge.kt`): log the `languageFeatures` map after construction
3. **Sidecar side** (`CompilerBridge.kt`): log Arrow-related JARs on classpath

### Fix C: Handle `LanguageVersion` Mismatch (if Phase 1.4 → D)

The session is hardcoded to `LanguageVersion.KOTLIN_2_1`. If the user's project uses Kotlin 2.2+ where context parameters have different syntax rules, the Analysis API may reject the code. Consider using the extracted `KOTLIN_VERSION` from Gradle to set the language version dynamically:

```kotlin
val langVersion = kotlinVersion?.let { LanguageVersion.fromFullVersionString(it) }
    ?: LanguageVersion.KOTLIN_2_1

languageVersionSettings = LanguageVersionSettingsImpl(
    languageVersion = langVersion,
    apiVersion = ApiVersion.createByLanguageVersion(langVersion),
    specificFeatures = languageFeatures,
)
```

### Fix D: Custom Module Provider Resilience (if Phase 5.2)

If the reflection-based provider registration is fragile, add a fallback approach: instead of replacing the project-level service, wrap the `analyze {}` call so it uses the source module explicitly:

```kotlin
analyze(ktFile) {
    // Force module context for non-physical files
    val module = if (ktFile.virtualFile == null) sourceModule else null
    // ...
}
```

## Key Files

| File | What to check / modify |
|------|----------------------|
| `server/src/project.rs` lines 130-200 | Gradle init script — flag extraction logic |
| `server/src/project.rs` lines 236-292 | `parse_gradle_output()` — flag parsing |
| `server/src/bridge.rs` line 321 | `compilerFlags` sent to sidecar |
| `sidecar/.../CompilerBridge.kt` lines 73-79 | `mapCompilerFlag()` loop |
| `sidecar/.../CompilerBridge.kt` lines 162-167 | `LanguageVersionSettingsImpl` construction |
| `sidecar/.../CompilerBridge.kt` lines 3101-3141 | `registerVirtualFileModuleProvider()` |
| `sidecar/.../CompilerBridge.kt` lines 3439-3460 | `mapCompilerFlag()` — flag-to-feature mapping |

## Verification

After fixing, verify end-to-end:

1. Build: `cargo build && cd sidecar && ./gradlew shadowJar`
2. Open a project with context parameters in `my-zed`
3. Confirm zero false diagnostics on `context(...)` syntax
4. Confirm Arrow `raise { }` / `Raise<E>` resolves without errors
5. Confirm the `my-zed` terminal shows the flags in the sidecar init log
6. Edit a file using context parameters — confirm diagnostics remain clean after `didChange`

## Related Plans

- `virtual-file-member-resolution.md` — UNRESOLVED_REFERENCE for member properties in virtual files (overlapping root cause in KtPsiFactory handling)
- `diagnostics-disappear-on-file-switch.md` — diagnostics clearing on tab switch (different issue but compounds the UX problem)
