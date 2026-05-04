---
id: ka-8vwe
status: closed
deps: [ka-e6eo, ka-wvv8, ka-b4j7]
links: []
created: 2026-04-14T11:53:08Z
type: task
priority: 2
assignee: Jens Kouros
parent: ka-ftb5
tags: [refactoring, sidecar]
---
# Extract BridgeNavigation from CompilerBridge

Move go-to-definition and go-to-implementation logic to `BridgeNavigation.kt`.
Shared interface navigation discovery helpers are owned by `ka-wvv8` in
`BridgeNavigationHelpers.kt`, not by this file and not by `BridgeCodeActions.kt`.

## Methods to Extract

### Public entry points

| Method | Current lines | Signature |
|--------|---------------|-----------|
| `definition` | 1012-1139 | `fun definition(uri: String, line: Int, character: Int): JsonObject` |
| `implementation` | 1288-1355 | `fun implementation(uri: String, line: Int, character: Int): JsonObject` |

Both become internal extension functions on `BridgeContext`:

```kotlin
internal fun BridgeContext.definition(uri: String, line: Int, character: Int): JsonObject
internal fun BridgeContext.implementation(uri: String, line: Int, character: Int): JsonObject
```

### Definition-private helpers

| Method | Current lines | Signature | Notes |
|--------|---------------|-----------|-------|
| `buildDefinitionLocation` | 1146-1226 | `KaSession.buildDefinitionLocation(ctx, symbol, fallbackUri)` | KaSession helper; calls `renderLibrarySymbolStub`, `writeDecompiledFile`, `buildFileUri`, `buildPsiDefinitionLocation` |
| `buildPsiDefinitionLocation` | 1231-1269 | `BridgeContext.buildPsiDefinitionLocation(psiTarget, fallbackUri)` | Calls `buildFileUri` |
| `renderLibrarySymbolStub` | 5755-5823 | `KaSession.renderLibrarySymbolStub(ctx, symbol, vfPath)` | Calls `writeDecompiledFile` and `extractSymbolDocumentation` |

### Implementation-private helpers

| Method | Current lines | Signature | Notes |
|--------|---------------|-----------|-------|
| `findImplementingMemberLocations` | 1362-1404 | `KaSession.findImplementingMemberLocations(ctx, interfaceDeclaration, memberName, locationsArray)` | Implementation-specific search for overriding interface members |

`findImplementingMemberLocations()` uses `allSessionKtFiles()`, `fileUriForKtFile()`, and
`isConcreteImplementationCandidate()`. Pass `BridgeContext` explicitly for context access and
call `isConcreteImplementationCandidate()` from `BridgeNavigationHelpers.kt`.

### Shared helpers used but not owned here

These are provided by `ka-wvv8` in `BridgeNavigationHelpers.kt`:

- `NavigationTarget`
- `KaSession.findImplementationNavigationTargets(ctx, interfaceDeclaration)`
- `KaSession.findInterfaceNavigationTargets(ctx, classOrObject)`
- `isConcreteImplementationCandidate(candidate)`
- `navigationTargetFor(ctx, declaration, title)`

`implementation()` uses `findImplementationNavigationTargets(ctx, classDeclaration)` for the
interface-declaration case. `BridgeCodeActions.kt` uses the same helpers for interface navigation
code actions.

### Shared helpers that stay on BridgeContext

| Method / field | Reason |
|----------------|--------|
| `session` | Current Analysis API session |
| `pebbleSpringIndex` | Kotlin-to-Pebble definition shortcut |
| `ensureSessionCurrent()` | Shared session lifecycle |
| `ensurePebbleSpringIndexCurrent()` | Shared Pebble/Spring index lifecycle |
| `findKtFile(session, uri)` | Shared file lookup |
| `lineColToOffset(ktFile, line, character)` | Shared position conversion |
| `buildFileUri(vfPath)` | Shared URI construction |
| `writeDecompiledFile(vfPath, text)` | Owns decompiled-file cache state |
| `findTargetDeclaration(element)` | Shared with references and rename |
| `findSpringModelProducerDeclaration(element)` | Shared with references |

`extractSymbolDocumentation()` does **not** live on `BridgeContext`. It is owned by `ka-b4j7` in
`BridgeHover.kt` as an `internal fun KaSession.extractSymbolDocumentation(...)`; this ticket calls
that helper from `renderLibrarySymbolStub()`.

## Implementation Steps

### Step 1: Create BridgeNavigation.kt

Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeNavigation.kt` with package
`dev.kouros.sidecar` and the imports needed for Gson, PSI, and Analysis API symbols.

### Step 2: Extract definition

Move `definition()` into:

```kotlin
internal fun BridgeContext.definition(uri: String, line: Int, character: Int): JsonObject
```

Calls to state and shared helpers resolve through the `BridgeContext` receiver. Helpers that also
need the `KaSession` receiver should take `ctx: BridgeContext` explicitly.

### Step 3: Extract implementation

Move `implementation()` into:

```kotlin
internal fun BridgeContext.implementation(uri: String, line: Int, character: Int): JsonObject
```

Inside `analyze(ktFile) { ... }`, pass the context into helpers that need bridge state:

```kotlin
val targets = findImplementationNavigationTargets(this@implementation, classDeclaration)
findImplementingMemberLocations(this@implementation, containingInterface, memberName, locationsArray)
```

### Step 4: Extract private navigation helpers

Move these into `BridgeNavigation.kt`:

- `buildDefinitionLocation(...)`
- `buildPsiDefinitionLocation(...)`
- `renderLibrarySymbolStub(...)`
- `findImplementingMemberLocations(...)`

Do not move or duplicate the shared interface navigation helpers from `ka-wvv8`.

### Step 5: Update CompilerBridge facade

Replace the two public method bodies with one-line delegations:

```kotlin
fun definition(uri: String, line: Int, character: Int): JsonObject =
    ctx.definition(uri, line, character)

fun implementation(uri: String, line: Int, character: Int): JsonObject =
    ctx.implementation(uri, line, character)
```

### Step 6: Remove extracted code from CompilerBridge.kt

Delete:

- `definition()` body
- `implementation()` body
- `buildDefinitionLocation()`
- `buildPsiDefinitionLocation()`
- `renderLibrarySymbolStub()`
- `findImplementingMemberLocations()`

Do not delete shared helpers owned by `BridgeContext`, `BridgeHover.kt`, or
`BridgeNavigationHelpers.kt`.

### Step 7: Verify

```bash
cd sidecar && ./gradlew test
cargo test
```

All existing tests must pass without changes to `AnalysisServer.kt` or test files.

## Acceptance Criteria

- [ ] `BridgeNavigation.kt` exists
- [ ] `definition()` is an `internal fun BridgeContext.definition(...)`
- [ ] `implementation()` is an `internal fun BridgeContext.implementation(...)`
- [ ] `findImplementingMemberLocations()` moves with implementation logic
- [ ] Shared interface navigation helpers are called from `BridgeNavigationHelpers.kt`, not duplicated
- [ ] `CompilerBridge.kt` delegates both public navigation methods through `ctx`
- [ ] `cd sidecar && ./gradlew test` passes
- [ ] `cargo test` passes

## Risks

- `renderLibrarySymbolStub()` and `buildDefinitionLocation()` need both `KaSession` and
  `BridgeContext`; use explicit `ctx: BridgeContext` parameters instead of context receivers.
- `implementation()` and code actions share interface navigation helpers. Keep them in
  `BridgeNavigationHelpers.kt` to avoid a dependency from navigation to code actions.
