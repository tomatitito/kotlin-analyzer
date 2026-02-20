# Gradle Init Script Compatibility

## Problem Statement

When opening Kotlin projects using Gradle 8+ in Zed with kotlin-analyzer, project resolution fails with errors from the `.kotlin-analyzer-init.gradle.kts` init script:

```
Unresolved reference 'convention'
Cannot infer type for type parameter 'T'. Specify it explicitly.
Unresolved reference 'JavaPluginConvention'
Cannot infer type for type parameter 'S'. Specify it explicitly.
Argument type mismatch: actual type is 'PluginDependencySpec', but 'Class<uninferred S (of fun <S : Task> withType)>' was expected.
```

The entire project resolution fails, leaving the user with no diagnostics, completions, or navigation.

## Root Cause (Investigated February 2026)

Two bugs in the Gradle init script at `server/src/project.rs:98-162`:

### Bug 1: Removed Convention API

The committed version uses `project.convention.findPlugin(JavaPluginConvention::class.java)` to access source sets. `JavaPluginConvention` was removed in Gradle 8.0 and `convention` is removed in Gradle 9.0.

A partial fix exists in the working copy that switches to `project.extensions.findByType(JavaPluginExtension::class.java)`, but it has not been built or released.

### Bug 2: KotlinCompile Class Not on Init Script Classpath

The init script references `org.jetbrains.kotlin.gradle.tasks.KotlinCompile` directly. Because the script is `.gradle.kts` (Kotlin DSL), it is statically compiled. Init scripts are compiled before project build scripts are evaluated, so the Kotlin Gradle Plugin classes are not on the compile classpath.

In older Gradle versions this may have worked due to more lenient script compilation, but Gradle 8.x enforces strict type resolution at compile time. The compiler fails to resolve `KotlinCompile`, producing cascading type inference errors (the confusing `PluginDependencySpec` error is a downstream compiler confusion from the unresolvable type).

### Key Insight: `.kts` vs `.gradle`

The fundamental issue is that `.gradle.kts` scripts require compile-time type resolution for all referenced classes. Groovy `.gradle` scripts resolve types at runtime, which means they work as long as the class is available when the code actually executes (inside `doLast {}`, after the project's buildscript classpath is configured).

## Plan: Switch to Groovy Init Script

Convert the init script from Kotlin DSL (`.gradle.kts`) to Groovy DSL (`.gradle`). This is the most robust fix because Groovy resolves types at runtime, eliminating both bugs and providing forward compatibility.

### Tasks

- [x] Rewrite the init script in Groovy in the Rust string literal at `server/src/project.rs:98-162`
  - Translate Kotlin DSL syntax to Groovy closures
  - Use `JavaPluginExtension` (modern API) with a fallback to `JavaPluginConvention` for Gradle 7.x compatibility
  - Keep `KotlinCompile` reference as-is (Groovy resolves at runtime)
  - Wrap `KotlinCompile` access in a try/catch for projects without the Kotlin plugin
  - Preserve the exact same output format (`SOURCE_ROOT=`, `CLASSPATH=`, etc.)
- [x] Change the init script filename from `.kotlin-analyzer-init.gradle.kts` to `.kotlin-analyzer-init.gradle` at `server/src/project.rs:164`
- [x] Remove the partial `JavaPluginExtension` fix from the working copy (superseded by the Groovy rewrite)
- [x] Add integration tests (see Testing section below)
- [x] Build and verify with `cargo build && cargo test && cargo test --features integration`

### Testing

The existing unit tests for `parse_gradle_output()` do not need changes since the output format is unchanged. What's missing is an integration test that actually **executes** the init script against a real Gradle project. This is the only way to verify the Groovy script compiles, runs, and produces correct output across Gradle versions.

#### Test fixture: `tests/fixtures/gradle-kotlin-simple/`

Create a minimal Gradle Kotlin project. This is the first Gradle fixture in the repo (the docs planned for them but none exist yet).

```
tests/fixtures/gradle-kotlin-simple/
├── settings.gradle.kts          # rootProject.name = "test-project"
├── build.gradle.kts             # plugins { kotlin("jvm") version "2.1.20" }
└── src/main/kotlin/Example.kt   # fun main() { println("hello") }
```

No Gradle wrapper included -- the test uses the system `gradle` command (same as `find_gradle_wrapper()` fallback). The `--features integration` gate already implies a working dev environment.

#### Test fixture: `tests/fixtures/gradle-java-only/`

A Java-only project without the Kotlin plugin, to verify the init script degrades gracefully.

```
tests/fixtures/gradle-java-only/
├── settings.gradle.kts          # rootProject.name = "java-only"
├── build.gradle.kts             # plugins { java }
└── src/main/java/Example.java   # public class Example {}
```

#### Integration tests (gated behind `--features integration`)

Add these tests to `server/src/project.rs` inside the existing `#[cfg(test)]` module, gated with `#[cfg(feature = "integration")]`:

**Test 1: `init_script_kotlin_project`**

Runs `resolve_gradle_project()` against `tests/fixtures/gradle-kotlin-simple/`. Asserts:
- At least one `SOURCE_ROOT` is returned (the `src/main/kotlin` dir)
- At least one `CLASSPATH` entry exists (kotlin-stdlib at minimum)
- `kotlin_version` is `Some(_)`
- Gradle exits successfully (no init script compilation errors)

```rust
#[test]
#[cfg(feature = "integration")]
fn init_script_kotlin_project() {
    let fixture = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../tests/fixtures/gradle-kotlin-simple");
    let config = Config::default();
    let model = resolve_gradle_project(&fixture, &config)
        .expect("gradle resolution should succeed");

    assert!(!model.source_roots.is_empty(), "should find source roots");
    assert!(!model.classpath.is_empty(), "should find classpath entries");
    assert!(model.kotlin_version.is_some(), "should detect kotlin version");
}
```

**Test 2: `init_script_java_only_project`**

Runs `resolve_gradle_project()` against `tests/fixtures/gradle-java-only/`. Asserts:
- Gradle exits successfully (the init script doesn't crash without the Kotlin plugin)
- Source roots may be present (Java source dirs)
- `kotlin_version` is `None`
- No compiler flags (no `KotlinCompile` tasks)

```rust
#[test]
#[cfg(feature = "integration")]
fn init_script_java_only_project() {
    let fixture = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../tests/fixtures/gradle-java-only");
    let config = Config::default();
    let model = resolve_gradle_project(&fixture, &config)
        .expect("gradle resolution should not crash on java-only project");

    assert!(model.kotlin_version.is_none(), "no kotlin version in java-only project");
    assert!(model.compiler_flags.is_empty(), "no compiler flags without kotlin plugin");
}
```

**Test 3: `init_script_output_format`**

A more granular test that writes the init script to a temp copy of the Kotlin fixture, runs Gradle directly (not through `resolve_gradle_project`), and inspects the raw stdout. This verifies the output format markers and line prefixes are correct, independent of the Rust parser.

```rust
#[test]
#[cfg(feature = "integration")]
fn init_script_output_format() {
    let fixture = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../tests/fixtures/gradle-kotlin-simple");

    // Write the init script (same as resolve_gradle_project does)
    let init_script_path = fixture.join(".kotlin-analyzer-init.gradle");
    std::fs::write(&init_script_path, INIT_SCRIPT).unwrap();

    let output = std::process::Command::new("gradle")
        .current_dir(&fixture)
        .arg("--init-script")
        .arg(&init_script_path)
        .arg("kotlinAnalyzerExtract")
        .arg("--quiet")
        .output()
        .expect("gradle should be available for integration tests");

    std::fs::remove_file(&init_script_path).ok();

    assert!(output.status.success(), "gradle should exit 0: {}",
        String::from_utf8_lossy(&output.stderr));

    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("---KOTLIN-ANALYZER-START---"), "missing start marker");
    assert!(stdout.contains("---KOTLIN-ANALYZER-END---"), "missing end marker");
    assert!(stdout.contains("SOURCE_ROOT="), "missing source root");
    assert!(stdout.contains("CLASSPATH="), "missing classpath");
}
```

For this test, extract the init script string into a module-level constant (`INIT_SCRIPT`) so it can be referenced from both `resolve_gradle_project()` and the test.

#### What these tests catch

| Scenario | Test |
|----------|------|
| Init script fails to compile (Groovy syntax error) | All three tests fail |
| `KotlinCompile` not resolvable at runtime | Test 1 fails (no compiler flags) |
| Init script crashes on project without Kotlin plugin | Test 2 fails |
| Output markers or prefixes wrong | Test 3 fails |
| Parser can't read Groovy script output | Test 1 fails (assertions on parsed model) |
| Regression in source root extraction | Test 1 fails |
| Regression in classpath extraction | Test 1, 3 fail |

### Groovy Translation Reference

Kotlin DSL:
```kotlin
project.extensions.findByType(org.gradle.api.plugins.JavaPluginExtension::class.java)?.let { jpe ->
    jpe.sourceSets.findByName("main")?.let { main ->
        main.allSource.srcDirs.forEach { dir ->
            if (dir.exists()) sb.appendLine("SOURCE_ROOT=${dir.absolutePath}")
        }
    }
}
```

Groovy equivalent:
```groovy
def jpe = project.extensions.findByType(org.gradle.api.plugins.JavaPluginExtension)
if (jpe != null) {
    def main = jpe.sourceSets.findByName("main")
    if (main != null) {
        main.allSource.srcDirs.each { dir ->
            if (dir.exists()) sb.appendLine("SOURCE_ROOT=${dir.absolutePath}")
        }
    }
}
```

Kotlin DSL:
```kotlin
project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).forEach { task ->
    task.compilerOptions.freeCompilerArgs.get().forEach { flag ->
        sb.appendLine("COMPILER_FLAG=$flag")
    }
}
```

Groovy equivalent:
```groovy
try {
    project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).each { task ->
        task.compilerOptions.freeCompilerArgs.get().each { flag ->
            sb.appendLine("COMPILER_FLAG=$flag")
        }
    }
} catch (Exception e) {
    // Kotlin plugin not applied to this module
}
```

### Compatibility Matrix

| Gradle Version | Current (.kts) | After Fix (.gradle) |
|---------------|----------------|---------------------|
| 7.x           | Works          | Works               |
| 8.0-8.1       | Fails (JavaPluginConvention removed) | Works |
| 8.2+          | Fails (convention removed) | Works |
| 9.0+          | Fails          | Works               |

### Priority

High -- this blocks project resolution for any user on Gradle 8+, which is the current stable line.
