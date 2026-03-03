# Gradle Build Script (.kts) Analysis Support

## Current State

`build.gradle.kts` and `settings.gradle.kts` files are analyzed as plain Kotlin, producing hundreds of false positives (e.g. 234 errors on a typical Spring Boot `build.gradle.kts`). This happens because Gradle scripts rely on a runtime environment that the Analysis API session doesn't replicate.

**Temporary fix**: Skip analysis of Gradle build scripts entirely (filter in `analyze_document`). The file is still opened/tracked for syntax highlighting via tree-sitter, but no semantic diagnostics are published.

## Why Gradle Scripts Are Special

Gradle Kotlin DSL scripts are compiled with a custom `ScriptDefinition` that provides:

1. **Implicit receivers** — The script body runs with `KotlinBuildScript` (or `KotlinSettingsScript`) as `this`, which brings `dependencies { }`, `repositories { }`, `tasks { }`, etc. into scope without imports.

2. **Generated type-safe accessors** — After evaluating the `plugins { }` block, Gradle generates extension properties/functions specific to the applied plugins (e.g. `kotlin("jvm")` generates `kotlin { }` configuration block). These accessors are synthetic and don't exist on any JAR classpath.

3. **Multi-stage compilation** — The `buildscript { }` and `plugins { }` blocks are compiled first with a minimal classpath. The main script body is compiled second with the full classpath (including plugin contributions). The Analysis API would need to replicate this two-phase model.

4. **Gradle API classpath** — At minimum requires `gradle-api`, `gradle-kotlin-dsl`, and the Kotlin stdlib bundled with Gradle (which may differ from the project's Kotlin version).

## Implementation Plan

### Phase 1: Skip analysis (do now)

Filter out Gradle build scripts in the Rust LSP server so the sidecar never sees them for analysis.

Files to skip:
- `build.gradle.kts`
- `settings.gradle.kts`
- Files under `buildSrc/` with `.kts` extension
- Files under `gradle/` convention plugin directories (e.g. `gradle/*.gradle.kts`)

Where to filter:
- `server.rs::analyze_document()` — early return before sending to sidecar
- `server.rs::initialized()` replay loop — skip replay for these files
- Optionally: `did_open` — skip sending `textDocument/didOpen` to sidecar entirely

### Phase 2: Basic Gradle script support (future)

Provide partial analysis by adding the Gradle API to the classpath and configuring a script receiver.

**Steps:**

1. **Locate the Gradle distribution** — Find the Gradle wrapper's distribution directory (`~/.gradle/wrapper/dists/<version>/`) or parse `gradle/wrapper/gradle-wrapper.properties` for the version.

2. **Add Gradle API JARs to classpath** — From the distribution: `lib/gradle-api-<version>.jar`, `lib/gradle-kotlin-dsl-<version>.jar`, and the bundled Kotlin stdlib JARs.

3. **Configure script definition in sidecar** — The Analysis API supports `KotlinScriptDefinition`. Register the Gradle script definition so the analyzer understands the implicit receiver type (`KotlinBuildScript`).

4. **Skip generated accessor resolution** — Accept that generated accessors won't resolve. This means plugin-specific DSL blocks (e.g. `kotlin { }`, `springBoot { }`) will still show errors. Suppress diagnostics for unresolved references that look like DSL calls inside the script body, or limit diagnostics to only the `dependencies { }` block where they're most useful.

**Expected result**: Basic Gradle API calls (`dependencies`, `repositories`, `tasks`, etc.) resolve correctly. Plugin-specific DSL blocks remain unresolved but don't produce noise.

### Phase 3: Full Gradle script support (aspirational)

Full parity with IntelliJ requires replicating the generated accessor machinery.

**Steps:**

1. **Integrate Gradle Tooling API** — Use `org.gradle.tooling.GradleConnector` to connect to the project's Gradle daemon. Request the `IdeaProject` model and the script classpath.

2. **Extract per-script classpath** — The Tooling API can provide the resolved classpath for each build script, including generated accessors.

3. **Feed script classpath to Analysis API** — Create a separate analysis session (or module) for each `.kts` file with its specific classpath.

4. **Cache and invalidate** — Cache the script classpath and regenerate it when `build.gradle.kts`, `settings.gradle.kts`, or `gradle.properties` change. This is expensive (requires running Gradle), so it should be debounced and backgrounded.

**Expected result**: Full resolution of all DSL constructs, diagnostics, hover, and completion in Gradle scripts. This is what IntelliJ does and it requires a running Gradle daemon.

## Risks and Considerations

- **Gradle version coupling**: Different Gradle versions have different APIs and script definitions. The sidecar would need to match the project's Gradle version.
- **Performance**: Connecting to the Gradle daemon adds startup latency. Script classpath resolution can take 10-30 seconds on first run.
- **Kotlin version mismatch**: Gradle bundles its own Kotlin version which may differ from the project's. Analysis API sessions can only use one Kotlin version.
- **Convention plugins**: Projects using `buildSrc/` or included builds for convention plugins add another layer of complexity — their scripts need their own classpath.
- **Phase 2 is probably sufficient**: Most developers care about diagnostics in their application code, not build scripts. Phase 2 (basic support) eliminates the false positives, which is the main pain point. Phase 3 is only needed if we want to compete with IntelliJ's build script editing experience.
