# Rich Hover Information for Annotations (and All Symbols)

## Status: COMPLETE

All phases implemented and verified in Zed with a Spring Boot project.

## Goal

Match or exceed IntelliJ's hover experience for Kotlin/Spring Boot annotations in Zed. The reference screenshots (`images/ReadingConverterAnnotation.png`, `images/RepositoryAnnotation.png`) show what IntelliJ provides.

## Implementation Summary

### Phase 1: Enhance the Sidecar Hover Method

All changes in `CompilerBridge.kt`, in the `hover()` method and its helpers.

#### 1.1 Add Meta-Annotation Rendering

- [x] When the hovered symbol is a `KaNamedClassSymbol` with `classKind == ANNOTATION_CLASS`, extract its annotations via `symbol.annotations`
- [x] For each annotation, render it as `@AnnotationName(args)` — use the annotation's short name and render arguments
- [x] Filter to show useful meta-annotations: `@Target`, `@Retention`, `@Documented`, `@Repeatable`, `@MustBeDocumented`, `@Inherited`
- [x] Also show semantically meaningful annotations like `@Component`, `@Service`, `@Qualifier` — these are important for Spring (they show annotation composition)
- [x] Render as a code block above the declaration

#### 1.2 Add Supertypes

- [x] For classes/interfaces, extract supertypes via `symbol.superTypes`
- [x] Render as a line after the declaration: `: Interface1, Interface2`
- [x] Skip `Any` and `Annotation` (implicit supertypes) to avoid noise

#### 1.3 Enhance KDoc Rendering

- [x] Parse KDoc bracket references `[ClassName]` and render as inline code
- [x] Handle fully qualified references `[org.foo.Bar]` — render as `` `Bar` ``
- [x] Render `@author` tags as `**Author:** Name`
- [x] Render `@since` tags as `**Since:** version`
- [x] Render `@see` tags as `**See also:** `` `ClassName` `` `
- [x] Render `@version` tags as `**Version:** value`
- [x] Preserve paragraph breaks from KDoc

#### 1.4 Add Source Origin Info

- [x] For library symbols (not source files), extract the JAR path from `symbol.psi?.containingFile?.virtualFile`
- [x] For library symbols without PSI (e.g., stdlib), use `symbol.origin` and `classId.packageFqName`
- [x] Render at the bottom: `*From: spring-data-commons-4.0.0.jar*` or `*From: kotlin-stdlib*`

### Phase 2: Improve Markdown Structure

#### 2.1 Structured Hover Layout

- [x] Package in italics at the top
- [x] Declaration + annotations in a single fenced code block
- [x] Horizontal rules (`---`) to separate sections
- [x] Documentation body as plain markdown with inline code
- [x] Tags (author, since, see) as bold-label pairs
- [x] Source origin in italics at the bottom

#### 2.2 Apply to All Symbol Kinds

- [x] Classes: Show annotations, supertypes, KDoc
- [x] Functions: Show annotations, KDoc
- [x] Annotation entries: Special handler resolves to annotation CLASS

### Phase 3: Test

- [x] Add integration tests for annotation hover (created `Annotated.kt` test fixture)
- [x] Test hover on a stdlib annotation (`@Deprecated`)
- [x] Test hover on a regular class with KDoc
- [x] Test hover on annotation reference shows type info
- [x] Visual verification in Zed with `my-zed`

### Phase 4: Visual Polish in Zed

- [x] Open `my-zed` with a Spring Boot Kotlin project (hello-reco)
- [x] Hover over `@RestController` — shows package, @Target, @Retention, @MustBeDocumented, @Controller, declaration
- [x] Hover over `@GetMapping` — shows package, @Target, @Retention, @MustBeDocumented, full constructor
- [x] Rendering matches IntelliJ-style annotation hover

## Key Fixes Applied

1. **PSI walk-up order**: `KtConstructorCalleeExpression` (a `KtExpression`) was intercepting the walk-up before reaching `KtAnnotationEntry`. Fixed by adding `insideAnnotation` guard to the `KtExpression` check, mirroring the guard on `KtReferenceExpression`.

2. **Library symbol container info**: `buildContainerInfo` relied on PSI for FQ name resolution, which fails for library symbols without PSI. Added fallback using `classId.packageFqName` for `KaClassLikeSymbol` and `callableId.packageName` for `KaCallableSymbol`.

3. **Library source origin**: `buildSourceOrigin` relied on PSI virtual file path. Added fallback using `symbol.origin` to detect library symbols and `classId.packageFqName` to identify the library.

4. **Duplicate annotation rendering**: `symbol.render()` includes annotations, and `buildAnnotationsBlock()` adds them again. Fixed by stripping annotation-prefixed lines from rendered output when `annotationsBlock` is present.

5. **Annotation argument rendering**: Replaced fragile `toString().removePrefix("KaAnnotationValue.")` with proper `renderAnnotationArgValue()` handling `EnumEntryValue`, `ArrayValue`, `ClassLiteralValue`, `ConstantValue`, `NestedAnnotationValue`.

## Key Files Modified

| File | What was modified |
|------|-------------------|
| `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` | `hover()` method, `buildAnnotationEntryHover()`, `buildContainerInfo()`, `buildAnnotationsBlock()`, `buildSupertypesLine()`, `buildSourceOrigin()`, `extractSymbolDocumentation()`, `extractKDocText()`, `enrichKDocText()`, `renderAnnotationArgValue()` |
| `sidecar/src/test/kotlin/dev/kouros/sidecar/CompilerBridgeIntegrationTest.kt` | 4 new annotation hover tests |
| `sidecar/src/test/resources/test-sources/Annotated.kt` | New test fixture with `@Deprecated`, `@Suppress`, custom `@MyMarker` |
