# Rich Hover Information for Annotations (and All Symbols)

## Goal

Match or exceed IntelliJ's hover experience for Kotlin/Spring Boot annotations in Zed. The reference screenshots (`images/ReadingConverterAnnotation.png`, `images/RepositoryAnnotation.png`) show what IntelliJ provides:

### What IntelliJ Shows

```
📦 org.springframework.stereotype                          ← package

@Target({ElementType.TYPE})                                ← meta-annotations
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Repository                               ← declaration
extends java.lang.annotation.Annotation                    ← supertypes

Indicates that an annotated class is a "Repository"...     ← KDoc/JavaDoc
                                                              with inline `code` refs
Teams implementing traditional Jakarta EE patterns...         and multiple paragraphs

Author: Oliver Gierke                                      ← @author tag

📦 Gradle: org.springframework.data:spring-data-commons:4.0.0  ← source origin
```

### What kotlin-analyzer Shows Today

```
*(default package)*

​```kotlin
class Greeter(val name: String)
​```
```

Basic declaration + optional KDoc. No meta-annotations, no source origin, no rich formatting.

## Gap Analysis

| Feature | IntelliJ | kotlin-analyzer | Effort |
|---------|----------|-----------------|--------|
| Package name | Yes | Yes (container info) | Done |
| Declaration signature | Yes | Yes (short names) | Done |
| KDoc/JavaDoc body | Yes, rich | Yes, basic | Enhance |
| Inline code references (`[Converter]`) | Rendered as code | Raw text | Medium |
| Meta-annotations (@Target, @Retention, etc.) | Yes | No | Medium |
| Supertypes / extends | Yes | No | Small |
| @author, @since, @see tags | Yes | Partial | Small |
| Source JAR / dependency coordinates | Yes | No | Medium |
| Annotation arguments rendered | Yes (e.g., `{ElementType.TYPE}`) | No | Medium |

## Implementation Plan

### Phase 1: Enhance the Sidecar Hover Method (~2 hours)

All changes in `CompilerBridge.kt`, in the `hover()` method and its helpers.

#### 1.1 Add Meta-Annotation Rendering

For annotation classes (and annotated declarations), show their annotations:

- [ ] When the hovered symbol is a `KaNamedClassSymbol` with `classKind == ANNOTATION_CLASS`, extract its annotations via `symbol.annotations`
- [ ] For each annotation, render it as `@AnnotationName(args)` — use the annotation's short name and render arguments
- [ ] Filter to show useful meta-annotations: `@Target`, `@Retention`, `@Documented`, `@Repeatable`, `@MustBeDocumented`, `@Inherited`
- [ ] Also show semantically meaningful annotations like `@Component`, `@Service`, `@Qualifier` — these are important for Spring (they show annotation composition)
- [ ] Render as a code block above the declaration:
  ```
  @Target(AnnotationTarget.CLASS)
  @Retention(AnnotationRetention.RUNTIME)
  @MustBeDocumented
  @Component
  ```

#### 1.2 Add Supertypes

- [ ] For classes/interfaces, extract supertypes via `symbol.superTypes`
- [ ] Render as a line after the declaration: `extends Annotation` or `: Interface1, Interface2`
- [ ] Skip `Any` (implicit Kotlin supertype) to avoid noise

#### 1.3 Enhance KDoc Rendering

- [ ] Parse KDoc bracket references `[ClassName]` and render as inline code `` `ClassName` ``
- [ ] Handle fully qualified references `[org.foo.Bar]` — render as `` `Bar` ``
- [ ] Render `@author` tags as `**Author:** Name`
- [ ] Render `@since` tags as `**Since:** version`
- [ ] Render `@see` tags as `**See also:** `` `ClassName` `` `
- [ ] Preserve paragraph breaks from KDoc

#### 1.4 Add Source Origin Info

- [ ] For library symbols (not source files), extract the JAR path from `symbol.psi?.containingFile?.virtualFile`
- [ ] Parse the JAR filename to extract Maven/Gradle coordinates if possible (e.g., `spring-data-commons-4.0.0.jar` → `org.springframework.data:spring-data-commons:4.0.0`)
- [ ] Render at the bottom: `*From: spring-data-commons-4.0.0.jar*`

### Phase 2: Improve Markdown Structure (~1 hour)

#### 2.1 Structured Hover Layout

Redesign the markdown output to follow this template:

```markdown
*org.springframework.stereotype*

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Component
annotation class Repository
```

---

Indicates that an annotated class is a "Repository", originally defined by
Domain-Driven Design (Evans, 2003) as...

A class thus annotated is eligible for Spring `DataAccessException`
translation when used in conjunction with a `PersistenceExceptionTranslationPostProcessor`.

**Author:** Oliver Gierke

---

*From: spring-data-commons-4.0.0.jar*
```

Key formatting decisions:
- [ ] Package in italics at the top
- [ ] Declaration + annotations in a single fenced code block
- [ ] Horizontal rules (`---`) to separate sections
- [ ] Documentation body as plain markdown with inline code
- [ ] Tags (author, since, see) as bold-label pairs
- [ ] Source origin in italics at the bottom

#### 2.2 Apply to All Symbol Kinds (not just annotations)

The same improvements benefit all hover targets:

- **Classes**: Show annotations, supertypes, KDoc
- **Functions**: Show annotations (`@Deprecated`, `@JvmStatic`, etc.), KDoc with `@param`/`@return`
- **Properties**: Show annotations, backing field info, KDoc
- **Parameters**: Show type, default value, KDoc `@param` section

### Phase 3: Test (~30 min)

- [ ] Add integration tests for annotation hover (create a test fixture with annotated classes)
- [ ] Test hover on a Spring Boot annotation (requires spring-boot JARs on classpath — may need a separate test project)
- [ ] Test hover on a stdlib annotation (`@Deprecated`, `@Suppress`)
- [ ] Test hover on a regular class with KDoc
- [ ] Visual verification in Zed with `my-zed`

### Phase 4: Visual Polish in Zed (~30 min)

- [ ] Open `my-zed` with a Spring Boot Kotlin project
- [ ] Hover over `@RestController`, `@Repository`, `@Service`, `@Autowired`
- [ ] Compare rendering with IntelliJ screenshots
- [ ] Adjust markdown formatting if Zed renders differently than expected (Zed's markdown renderer may have quirks with nested code blocks, horizontal rules, etc.)
- [ ] Take comparison screenshots

## Key Files

| File | What to modify |
|------|---------------|
| `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` | `hover()` method + new helper functions for annotation rendering, supertype extraction, KDoc enhancement, source origin |
| `server/src/server.rs` | No changes needed — Rust side is a pass-through for markdown |
| `sidecar/src/test/kotlin/dev/kouros/sidecar/CompilerBridgeIntegrationTest.kt` | New hover tests for annotations |
| `sidecar/src/test/resources/test-sources/` | New test fixture with annotated classes |

## Analysis API Entry Points

| What | API |
|------|-----|
| Symbol annotations | `symbol.annotations` → `List<KaAnnotation>` |
| Annotation arguments | `KaAnnotation.arguments` → `List<KaNamedAnnotationValue>` |
| Supertypes | `symbol.superTypes` → `List<KaType>` |
| KDoc | `declaration.docComment` → `KDoc` with sections |
| Source file / JAR | `symbol.psi?.containingFile?.virtualFile?.path` |
| Class kind | `KaNamedClassSymbol.classKind` (CLASS, INTERFACE, ANNOTATION_CLASS, ENUM, OBJECT) |
| FQN | `symbol.callableId?.asSingleFqName()` or `symbol.classId?.asFqNameString()` |

## Design Decisions

1. **Annotations in the code block, not above it**: IntelliJ renders meta-annotations as code. We do the same by including them inside the fenced kotlin block. This gives syntax highlighting for free.

2. **Short names in declaration, FQN in package line**: The declaration uses `@Component` not `@org.springframework.stereotype.Component`. The package line at the top provides the FQN context.

3. **Selective meta-annotation display**: Don't dump every annotation. Show: retention, target, documented, inherited, plus any Spring/framework composition annotations. Skip internal/compiler annotations.

4. **No HTML**: Zed's hover renderer uses markdown only. No HTML tags, no custom styling. Keep it clean.
