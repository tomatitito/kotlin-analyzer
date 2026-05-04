---
id: ka-wvv8
status: closed
deps: [ka-e6eo]
links: []
created: 2026-04-14T12:42:44Z
type: task
priority: 2
assignee: Jens Kouros
parent: ka-ftb5
tags: [refactoring, sidecar, navigation]
---
# Extract BridgeNavigationHelpers from CompilerBridge

Move shared interface/navigation discovery helpers out of `CompilerBridge.kt` into `BridgeNavigationHelpers.kt`. These helpers are used by both go-to-implementation and interface navigation code actions, so they must not live privately in `BridgeCodeActions.kt`.

## Motivation

The current implementation of `implementation()` calls `findImplementationNavigationTargets()`, while `codeActions()` also uses `findImplementationNavigationTargets()` and `findInterfaceNavigationTargets()` to build interface navigation actions. The existing extraction plan assigns those helpers to the code-actions ticket, which would make navigation depend on code actions or duplicate logic. This ticket creates a neutral shared home.

## File to Create

`sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeNavigationHelpers.kt`

## Methods and Types to Extract

Move these from `CompilerBridge.kt`:

- `NavigationTarget` data class
- `KaSession.findImplementationNavigationTargets(interfaceDeclaration: KtClass): List<NavigationTarget>`
- `KaSession.findInterfaceNavigationTargets(classOrObject: KtClassOrObject): List<NavigationTarget>`
- `isConcreteImplementationCandidate(candidate: KtClassOrObject): Boolean`
- `navigationTargetFor(declaration: KtClassOrObject, title: String): NavigationTarget?`

Also make sure the `implementation()` extraction owns or can call:

- `KaSession.findImplementingMemberLocations(interfaceDeclaration: KtClass, memberName: String, locationsArray: JsonArray)`

That helper is implementation-specific and should move with `implementation()` unless it becomes shared later.

## BridgeContext Dependencies

The shared helper functions need access to these `BridgeContext` members after `ka-e6eo` lands:

- `allSessionKtFiles()`
- `fileUriForKtFile(ktFile)`
- `selectionForOffset(document, offset, length)`

Use explicit `BridgeContext` parameters for KaSession extension helpers where needed, instead of Kotlin context receivers. Example shape:

```kotlin
internal data class NavigationTarget(
    val title: String,
    val targetUri: String,
    val selection: JsonObject,
)

internal fun KaSession.findImplementationNavigationTargets(
    ctx: BridgeContext,
    interfaceDeclaration: KtClass,
): List<NavigationTarget>

internal fun KaSession.findInterfaceNavigationTargets(
    ctx: BridgeContext,
    classOrObject: KtClassOrObject,
): List<NavigationTarget>

internal fun isConcreteImplementationCandidate(candidate: KtClassOrObject): Boolean

internal fun navigationTargetFor(
    ctx: BridgeContext,
    declaration: KtClassOrObject,
    title: String,
): NavigationTarget?
```

## Call Site Updates

- `BridgeCodeActions.kt` calls these helpers for `addInterfaceNavigationActions()` instead of defining private copies.
- The navigation extraction ticket calls these helpers from `implementation()` when the cursor is on an interface declaration.
- Do not introduce a dependency from navigation code to `BridgeCodeActions.kt`.
- Do not put these helpers on `BridgeContext` unless they need direct state ownership; keep `BridgeContext` focused on state and broadly shared infrastructure.

## Related Ticket Updates Needed

- `ka-4dsl` should remove ownership of `NavigationTarget`, `findImplementationNavigationTargets`, `findInterfaceNavigationTargets`, `isConcreteImplementationCandidate`, and `navigationTargetFor`; it should depend on this ticket and call the shared helpers.
- `ka-8vwe` should either become a navigation extraction ticket or explicitly include `implementation()` and `findImplementingMemberLocations()`; it should depend on this ticket.
- `ka-xwq9` already expects final facade delegation to `ctx.implementation(...)`; keep that public API unchanged.

## Acceptance Criteria

- [ ] `BridgeNavigationHelpers.kt` exists in `sidecar/src/main/kotlin/dev/kouros/sidecar/`
- [ ] `NavigationTarget` and shared interface navigation helpers are no longer private members of `CompilerBridge`
- [ ] `BridgeCodeActions.kt` uses the shared helpers for interface navigation code actions
- [ ] The `implementation()` extraction uses the shared helpers for interface declaration navigation
- [ ] `CompilerBridge.kt` has no duplicate copies of these helpers after the facade extraction
- [ ] No dependency is introduced from navigation feature code to `BridgeCodeActions.kt`
- [ ] `cd sidecar && ./gradlew test` passes
- [ ] `cargo test` passes
