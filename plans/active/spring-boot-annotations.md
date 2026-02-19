# Spring Boot Annotation Support

## Problem Statement

While core LSP features (hover, rename, go-to-definition, find references) work correctly for standard Kotlin code, Spring Boot annotations have limited support:

1. **@Service annotation**: Shows red squiggly lines (unresolved reference)
2. **@RestController annotation**: No squiggly lines but hover shows no information
3. **Missing rich documentation**: IntelliJ shows comprehensive JavaDoc/KDoc for Spring annotations, but our LSP returns empty or minimal information

## Root Cause

The issue is likely that Spring Boot JARs are not being included in the classpath passed to the Analysis API. The current classpath extraction only looks at `compileClasspath`, which may not include all necessary dependencies.

## Quick Fix to Try

### Step 1: Verify Current Classpath
Add logging to see what's actually being passed:

```kotlin
// In CompilerBridge.kt initialize method
logger.info("Classpath entries: ${classpath.size}")
classpath.forEach { entry ->
    if (entry.contains("spring")) {
        logger.info("Spring JAR found: $entry")
    }
}
```

### Step 2: Expand Gradle Classpath Extraction
Modify the Gradle init script to include runtime dependencies:

```groovy
// In project.clj or gradle init script
def allDeps = [] as Set
configurations.compileClasspath.each { allDeps.add(it.absolutePath) }
configurations.runtimeClasspath.each { allDeps.add(it.absolutePath) }
println "CLASSPATH:${allDeps.join(':')}"
```

### Step 3: Test with Simple Spring Boot Project
Create a minimal test project with Spring Initializr and verify if the annotations resolve after the classpath fix.

## Expected Outcome

Once Spring JARs are on the classpath:
- `@Service` and `@RestController` should resolve without errors
- Basic type information should appear on hover
- Go-to-definition should navigate to decompiled Spring classes

## Documentation Enhancement (Phase 2)

For rich JavaDoc like IntelliJ shows, we'd need:
1. Source JAR discovery (`spring-context-5.x.x-sources.jar`)
2. JavaDoc extraction from source files
3. Hover response enhancement to include documentation

This is a larger change that can be deferred to post-v1.

## Priority

Medium - Important for Spring Boot users, but not blocking v1.0 since core Kotlin features work. Can be addressed in v1.1 with a focused classpath fix.

## Next Steps

1. Add classpath logging to identify missing JARs
2. Test with expanded classpath extraction
3. If successful, update the Gradle integration permanently
4. Document the fix for Spring Boot users
