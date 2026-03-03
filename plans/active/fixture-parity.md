# Test Fixture Feature Parity

## `tests/fixtures/gradle-spring-web`
- [x] Hover: `@RestController` shows package info, meta-annotations (`@Target`, `@Retention`, `@MustBeDocumented`, `@Controller`, `@ResponseBody`), declaration
- [x] Hover: `@GetMapping` shows package info, meta-annotations including `@RequestMapping(method = [RequestMethod.GET])`, declaration
- [x] Hover: `ResponseStatusException` shows class declaration (not constructor), package info
- [x] Hover: `HttpStatus` shows clean `enum class HttpStatus : HttpStatusCode` (no duplicate supertypes)
- [x] Diagnostics: Spring + Arrow project produces zero errors with `-Xcontext-parameters` flag
- [x] Compiler flags: `-Xcontext-parameters` correctly forwarded from `build.gradle.kts` to sidecar
- [x] Go-to-definition: Spring types resolve to library source/decompiled class (synthetic `kotlin-analyzer:///library/` URIs for JAR-based symbols)
- [x] Code actions: Import suggestions for Spring types (verified — existing import mechanism works)
- [x] Completions: Spring annotation attributes (verified — 1172 items returned at annotation position)

## `tests/fixtures/gradle-kotlin-simple`
- [x] Hover: stdlib types (covered by `CompilerBridgeIntegrationTest`)
- [x] Diagnostics: clean file produces zero errors (covered by `CompilerBridgeIntegrationTest`)
- [x] Go-to-definition: stdlib functions (covered by `CompilerBridgeIntegrationTest`)
- [x] Completions: basic Kotlin completions (covered by `CompilerBridgeIntegrationTest`)

## `tests/fixtures/gradle-java-only`
- [x] Verify LS handles Java-only projects gracefully (no crashes) — 2 tests in CompilerBridgeIntegrationTest
- [x] Diagnostics: Java file handling — analyzes without crash, virtual Kotlin files work in Java-only projects

## `tests/fixtures/kotlin`
- [x] Diagnostics: clean files produce zero errors (covered by `CompilerBridgeIntegrationTest.diagnostics - clean file has no errors`)
- [x] Diagnostics: type mismatches detected (covered by `CompilerBridgeIntegrationTest.diagnostics - type mismatch detected`)
- [x] Hover: class, function, annotation, constructor, stdlib references (6 tests in `CompilerBridgeIntegrationTest`)
- [x] Definition: class/variable references (2 tests)
- [x] References: find-all-references within and across files (2 tests)
- [x] Completions: dot member and scope completions with auto-import (3 tests)
- [x] Code actions: organize imports, convert function body style (4 tests)
- [x] Compiler flags: context parameters/receivers produce zero errors (2 regression tests)
- [x] Diagnostics: `errors/` files produce expected specific errors (11 tests in FixtureDiagnosticsTest)
- [x] Diagnostics: `warnings/` files produce expected warnings (8 tests; 3 are IDE-only inspections not available in standalone API)
- [x] Edge cases: empty file, unicode identifiers, syntax errors (5 tests in FixtureDiagnosticsTest)

## Hover Quality Comparison with IntelliJ

### IntelliJ hover structure (reference: images/RepositoryAnnotation.png, ReadingConverterAnnotation.png)
1. Package icon + package name
2. Meta-annotations in Java syntax (`@Target({ElementType.TYPE})`)
3. Declaration in Java syntax (`public @interface Repository`)
4. Supertypes (`extends java.lang.annotation.Annotation`)
5. JavaDoc documentation text (multi-paragraph)
6. `@author` tag
7. Source origin (`Gradle: groupId:artifactId:version (jar-file.jar)`)

### kotlin-analyzer hover structure (current)
1. Package name (✓ matches)
2. Meta-annotations in Kotlin syntax (`@Target(AnnotationTarget.CLASS)`) (✓ all shown via blocklist)
3. Declaration in Kotlin syntax (`annotation class RestController`) (✓ correct for Kotlin analyzer)
4. Supertypes (✓ shown when not already in declaration)
5. KDoc documentation (✓ when available in source)
6. Source origin (✓ shown)

### Remaining gaps
- JavaDoc from compiled library classes is not parsed (KDoc only)
- `@author` tags from JavaDoc not extracted
- Kotlin syntax vs Java syntax is intentional (correct for Kotlin analyzer)

## Improvements Made (2026-03-03)
1. **Meta-annotations blocklist**: Changed `buildAnnotationsBlock` from allowlist to blocklist — now shows ALL meta-annotations like IntelliJ (e.g., `@Controller`, `@ResponseBody` on `@RestController`)
2. **Duplicate supertype fix**: Skip supertypes line when rendered declaration already contains `:`
3. **Constructor-to-class navigation**: Hover on constructor calls (e.g., `ResponseStatusException(...)`) now shows the containing class instead of the constructor signature
4. **Verified in Zed**: Hover popup with meta-annotations and package info confirmed working via Shift+K (Vim hover)

## Improvements Made (2026-03-03, session 2)
1. **Go-to-definition for library symbols**: Added Analysis API-based resolution (`resolveToSymbol`) with synthetic `kotlin-analyzer:///library/` URIs for JAR-based symbols; catches `Throwable` (not just `Exception`) for robust error handling
2. **Annotation entry definition**: Added `KtAnnotationEntry` handling in definition method to resolve annotation types to their class definitions
3. **FixtureDiagnosticsTest**: New test class with 26 tests covering all `errors/`, `warnings/`, and `edge-cases/` fixture files
4. **Java-only project handling**: 2 new tests verifying graceful handling of projects with no Kotlin source files
5. **Spring definition tests**: 2 new tests for `@RestController` and `ResponseStatusException` go-to-definition
6. **Spring completion test**: 1 new test verifying annotation attribute completions
7. **Total test count**: 76 tests (up from 45), all passing
