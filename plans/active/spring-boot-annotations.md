# Spring Boot Annotation Support

## Problem Statement

While core LSP features (hover, rename, go-to-definition, find references) work correctly for standard Kotlin code, Spring Boot annotations have limited support:

1. **@Service annotation**: Shows red squiggly lines (unresolved reference)
2. **@RestController annotation**: No squiggly lines but hover shows no information
3. **Missing rich documentation**: IntelliJ shows comprehensive JavaDoc/KDoc for Spring annotations, but our LSP returns empty or minimal information

## Root Cause Analysis

### 1. Missing Spring Boot Dependencies
The Analysis API needs Spring Boot JARs on the classpath to resolve annotations:
- `spring-boot-starter-web` for @RestController
- `spring-context` for @Service, @Component, @Autowired
- `spring-web` for @RequestMapping, @GetMapping, etc.

### 2. Incomplete Classpath Extraction
Current Gradle classpath extraction may not include:
- Annotation processor dependencies
- Runtime-only dependencies
- Spring Boot's transitive dependencies

### 3. JavaDoc/Source Attachment
IntelliJ downloads and indexes source JARs to show documentation. Our sidecar needs:
- Source JAR discovery alongside binary JARs
- JavaDoc extraction from source files
- Decompiled stub enhancement with documentation

## Investigation Steps

### Phase 1: Verify Classpath Contents
- [ ] Log full classpath passed to sidecar during initialization
- [ ] Check if Spring Boot JARs are present
- [ ] Compare with IntelliJ's project structure → Libraries view
- [ ] Test with explicit Spring Boot dependency in test project

### Phase 2: Gradle Integration Enhancement
- [ ] Modify Gradle init script to include all configurations:
  - `compileClasspath`
  - `runtimeClasspath`
  - `annotationProcessor`
  - `kapt` (if using KAPT)
- [ ] Extract source sets beyond main/test
- [ ] Handle multi-module Spring Boot projects

### Phase 3: Source and Documentation
- [ ] Detect `-sources.jar` artifacts in Gradle cache
- [ ] Pass source JARs to Analysis API session
- [ ] Configure `KaSession` to use source attachments
- [ ] Extract and render KDoc/JavaDoc in hover responses

## Test Projects

### Minimal Spring Boot Test
```kotlin
package com.example.demo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
class DemoApplication

@Service
class GreetingService {
    fun getGreeting(): String = "Hello, World!"
}

@RestController
class GreetingController(private val service: GreetingService) {
    @GetMapping("/greeting")
    fun greeting(): String = service.getGreeting()
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
```

Expected behavior:
- Hovering over `@Service` shows: "Indicates that an annotated class is a 'Service', originally defined by Domain-Driven Design..."
- Hovering over `@RestController` shows: "A convenience annotation that is itself annotated with @Controller and @ResponseBody..."
- No red squiggly lines on any Spring annotations

## Implementation Plan

### Step 1: Enhanced Classpath Collection
```kotlin
// In project model extraction
val configurations = listOf(
    "compileClasspath",
    "runtimeClasspath",
    "annotationProcessor",
    "kapt",
    "ksp"
)

configurations.forEach { config ->
    try {
        val paths = project.configurations
            .getByName(config)
            .resolve()
            .map { it.absolutePath }
        allClasspathEntries.addAll(paths)
    } catch (e: Exception) {
        // Config might not exist
    }
}
```

### Step 2: Source JAR Resolution
```kotlin
// For each dependency JAR
val binaryJar = File(jarPath)
val sourceJar = File(jarPath.replace(".jar", "-sources.jar"))
if (sourceJar.exists()) {
    sourceAttachments[binaryJar] = sourceJar
}
```

### Step 3: Analysis API Configuration
```kotlin
// In StandaloneAnalysisAPISession builder
analysisSession {
    modules {
        module {
            // Add both binary and source roots
            addBinaryRoots(binaryJars)
            addSourceRoots(sourceJars)
        }
    }
}
```

### Step 4: Hover Enhancement
```kotlin
// In CompilerBridge.hover()
val symbol = reference.resolveToSymbol()
val kdoc = symbol.psi?.findKDoc()
val javadoc = symbol.psi?.findJavaDoc()

return buildString {
    // Type signature
    append(symbol.render())

    // Documentation
    kdoc?.let { append("\n\n").append(it) }
    javadoc?.let { append("\n\n").append(it) }
}
```

## Success Criteria

- [ ] Spring Boot annotations resolve without errors
- [ ] Hover shows full documentation for Spring annotations
- [ ] Completion suggests Spring annotations with documentation
- [ ] Go-to-definition navigates to decompiled Spring sources
- [ ] Works with both Maven and Gradle Spring Boot projects

## Testing

### Manual Verification
1. Create Spring Boot project with Spring Initializr
2. Open in Zed with kotlin-analyzer
3. Verify each annotation (hover, no errors)
4. Compare hover output with IntelliJ

### Automated Tests
- [ ] Add Spring Boot test project to fixtures
- [ ] Integration test for Spring annotation hover
- [ ] Test classpath extraction with Spring dependencies
- [ ] Verify source JAR discovery

## Related Issues

- Classpath extraction incomplete for complex projects
- Documentation not shown for external dependencies
- Annotation processing support limited

## Priority

High - Spring Boot is a major Kotlin use case, and the annotation support directly impacts developer experience for a large portion of Kotlin users.

## References

- IntelliJ IDEA's External Libraries implementation
- Analysis API documentation on source attachments
- Spring Boot starter dependencies structure
- Gradle's dependency resolution API
