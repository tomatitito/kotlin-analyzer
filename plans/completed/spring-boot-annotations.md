# Spring Boot Annotation Support

## Problem Statement

When opening Spring Boot Kotlin projects in Zed with kotlin-analyzer, Spring annotations show as `Unresolved reference 'springframework'`. This affects all Spring annotations (`@Service`, `@RestController`, `@Autowired`, etc.).

## Root Cause (Investigated February 2026)

Two bugs in the classpath extraction pipeline were identified:

### Bug 1: Silent Exception Swallowing

The Gradle init script in `server/src/project.rs` wrapped classpath extraction in a `try/catch` that silently discarded all errors:

```kotlin
try {
    val compileClasspath = project.configurations.getByName("compileClasspath")
    compileClasspath.resolve().forEach { file ->
        sb.appendLine("CLASSPATH=${file.absolutePath}")
    }
} catch (_: Exception) {}  // <-- entire classpath silently dropped
```

Spring Boot projects use the `spring-boot-gradle-plugin` and `io.spring.dependency-management` plugin, which interact with dependency resolution in non-trivial ways. If `resolve()` throws, the sidecar receives an empty classpath with no indication of failure.

**Fix applied:** Error messages are now reported via `CLASSPATH_ERROR=` lines and logged at WARN level in the Rust parser.

### Bug 2: Multi-Module Parser Break

The Rust parser for Gradle output broke on the first `---KOTLIN-ANALYZER-END---` marker. Since the init script uses `allprojects { ... }`, multi-module Spring Boot projects (the norm) produce multiple START/END sections. Only the first module's data was captured — typically the root project, which often has no dependencies.

**Fix applied:** Changed `break` to `in_section = false; continue;` so all modules' classpath entries and source roots are accumulated.

### Key Finding: compileClasspath IS Correct

The initial hypothesis that `compileClasspath` was missing Spring dependencies was **wrong**. Spring Boot starters are `implementation` dependencies, which extend `compileClasspath`. The JARs were being lost in the extraction/parsing pipeline, not missing from Gradle's dependency graph.

### Additional Finding: No External Dependency Validation

The Analysis API spike (`spike/analysis-api/`) only tested with kotlin-stdlib on the classpath. The pathway of "project classpath → Analysis API library modules → resolution" for external libraries had never been validated end-to-end.

## Fixes Applied

- [x] Replace silent `catch (_: Exception) {}` with error reporting (`CLASSPATH_ERROR=`, `KOTLIN_VERSION_ERROR=`)
- [x] Log classpath extraction errors at WARN level in Rust parser
- [x] Fix multi-module parser to read all START/END sections
- [x] Add classpath diagnostic logging in sidecar CompilerBridge (empty classpath warning, Spring JAR detection)
- [x] Add tests for multi-module parsing and error handling

## Expected Outcome

With these fixes:
- `@Service`, `@RestController`, and all Spring annotations resolve without errors
- Go-to-definition navigates to Spring classes
- Hover shows type information for Spring types with package context
- Multi-module Spring Boot projects have complete classpath coverage

## Hover Quality Improvements (February 2026)

Enhanced the hover experience for annotations and library symbols:
- [x] Show package/container info (e.g., `*(org.springframework.stereotype)*`) above rendered declaration
- [x] Extract documentation from decompiled Kotlin metadata (KDoc)
- [x] Extract Javadoc from Java library declarations (Spring is mostly Java)
- [x] Try multiple documentation sources: PSI KDoc → decompiled doc comments → Javadoc reflection

## Future Work: Rich Spring Support

For Spring-specific intelligence beyond basic annotation resolution, a separate project could wrap the **Spring Boot Language Server** (VMware/Broadcom's Spring Tools 4, `spring-projects/sts4`) in a Zed extension:

- Bean navigation (jump between `@Component` definitions and injection points)
- `application.properties` / `application.yml` completion and validation
- `@Value` property name completion
- Spring Expression Language (SpEL) support
- Live application data via JMX/actuator

### Caveats

- The Spring Boot LS depends on JDT LS (Eclipse Java Development Tools) for classpath resolution
- It has poor Kotlin support (open issue spring-projects/sts4#1616)
- It is Java-focused and may not work well with Kotlin-only projects
- This is a **separate Zed extension**, not a modification to kotlin-analyzer

### Priority

Low — deferred to post-v1. The classpath fixes solve the immediate `Unresolved reference` problem. Rich Spring intelligence is a nice-to-have for the future.
