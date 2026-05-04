---
id: ka-pg9l
status: open
deps: []
links: [ka-3sg2]
created: 2026-03-30T21:50:09Z
type: feature
priority: 2
assignee: Jens Kouros
tags: [code-actions, types, kotlin, lsp, zed]
---
# Add Kotlin code actions to generate or regenerate explicit type definitions

Add Kotlin code actions for symbols that infer a type but do not have an explicit type definition. When no explicit type is present, offer an action to generate one. When an explicit type definition already exists, offer an action to regenerate/refresh it. Only the applicable action should be shown in each state.

## Design

Scope:
- Trigger on Kotlin symbols/declarations whose types can be inferred or already declared explicitly.
- If the symbol has no explicit type annotation, offer `Generate Type Definition`.
- If the symbol already has an explicit type annotation, offer `Regenerate Type Definition`.
- Hide the non-applicable action in each case.

Potential targets:
- local properties
- member properties
- parameters where appropriate
- return types for functions if feasible

Implementation ideas:
- Use the sidecar/Analysis API to compute the rendered Kotlin type for the target symbol.
- Detect whether the PSI already contains an explicit type reference.
- Apply a text edit that inserts or replaces the type annotation.
- Keep formatting/style conservative and compatible with existing code formatting.
- Fail conservatively for cases where the inferred/rendered type is unavailable, unstable, anonymous, or undesirable.

## Implementation Outline

### Research notes / current state

There is already partial code-action support for explicit types in the sidecar:
- `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt`
  - `codeActions(...)`
  - `addExplicitTypeAction(...)`

Current behavior appears to:
- offer an “Add explicit type” refactoring for some `KtProperty` declarations without a type annotation
- compute the rendered type via the Analysis API
- insert `: Type` after the property name

This ticket should generalize and refine that behavior so the UI becomes state-aware:
- show `Generate Type Definition` when no explicit type exists
- show `Regenerate Type Definition` when an explicit type already exists
- never show both for the same symbol

### Recommended first slice

1. Rework the existing property-only explicit-type action into a shared “type definition action” planner.
2. Support `KtProperty` first:
   - local `val` / `var`
   - member properties
3. Add state-aware visibility:
   - no explicit type -> `Generate Type Definition`
   - explicit type present -> `Regenerate Type Definition`
4. Apply edits conservatively by only inserting/replacing the `typeReference` text.
5. Defer broader targets such as function return types and parameters until the property flow is solid.

### UX proposal

Offer exactly one action depending on declaration state:

- `Generate Type Definition`
- `Regenerate Type Definition`

Optionally include the computed type in the title for clarity, for example:
- `Generate Type Definition ': List<User>'`
- `Regenerate Type Definition ': UserDto'`

But keep the first implementation simple if title stability matters for tests.

### Supported targets to phase in

#### Phase 1
- `KtProperty`
  - local properties
  - class/object properties

#### Phase 2
- `KtNamedFunction`
  - explicit return type generation/regeneration
- `KtParameter`
  - only where Kotlin syntax actually permits or expects explicit type rewriting in a useful way

Phase 1 alone is already valuable and aligns with the current implementation base.

### Suggested implementation steps

#### 1. Refactor the existing explicit-type action into a generic planner

Create a helper that, given the PSI element under the cursor, returns a plan such as:
- target declaration kind
- whether an explicit type reference exists
- computed rendered type string
- insertion or replacement range
- action title

A possible shape:
- `TypeDefinitionActionPlan`
  - `title`
  - `kind` (`generate` | `regenerate`)
  - `range`
  - `replacementText`

This keeps the visibility logic and edit generation in one place.

#### 2. Detect the declaration and its current explicit-type state

For properties:
- resolve the nearest `KtProperty`
- determine whether `property.typeReference` is null
- use that to decide generate vs regenerate

Visibility rules:
- `typeReference == null` -> only generate
- `typeReference != null` -> only regenerate

If the cursor is not on a supported declaration, surface no action.

#### 3. Compute the canonical rendered type via Analysis API

Reuse Analysis API type rendering, likely with:
- `property.symbol.returnType`
- `KaTypeRendererForSource.WITH_SHORT_NAMES`
- `Variance.INVARIANT`

Then add conservative guards. Do not offer an action when the rendered type is:
- empty
- `Unit` / `Nothing` where it makes no sense
- error/unknown/unstable
- anonymous or otherwise non-representable in source

If needed, normalize whitespace and nullability formatting before building the edit.

#### 4. Generate insertion vs replacement edits

For generate:
- insert `: <RenderedType>` immediately after the declaration name

For regenerate:
- replace the existing `typeReference` text range with `<RenderedType>`
- preserve the colon and surrounding spacing, or replace the whole `: OldType` range consistently if that is easier/safer

Be conservative around delegated properties or declarations with unusual syntax.

#### 5. Preserve imports / short-name correctness

Because the current renderer uses short names, verify whether the resulting file already has the needed imports.

Options:
- first slice: only use short names when already resolvable in-file/imports are already sufficient
- or render fully qualified names for safety
- or pair the action with import insertion if missing

Recommended first slice:
- prefer correctness over prettiness
- if import handling is uncertain, either:
  - render fully qualified types, or
  - suppress the action for types whose short names would require new imports

A follow-up ticket can improve import cleanup if needed.

#### 6. Wire into existing code-action plumbing

Update `codeActions(...)` so the new planner replaces `addExplicitTypeAction(...)`.

Likely shape:
- rename `addExplicitTypeAction(...)` to something like `addTypeDefinitionAction(...)`
- make it handle both generate + regenerate cases
- keep action kind as `refactor`

No new LSP command path should be needed if this remains a pure text-edit code action.

#### 7. Extend to function return types later

After property support works, add `KtNamedFunction` support:
- no explicit return type -> generate
- explicit return type -> regenerate

Be conservative and skip:
- constructors
- override functions if rewriting the return type is noisy or redundant
- local functions if the resulting style is undesirable
- expression/body forms where the edit range is tricky until tested

### Likely code areas

#### JVM sidecar
- `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt`
  - `codeActions(...)`
  - existing `addExplicitTypeAction(...)`
  - Analysis API rendering utilities already used elsewhere

#### Rust frontend
- likely no major protocol changes needed if the feature stays edit-only
- existing code action parsing should already handle returned text edits

### Tests to add

#### Sidecar / integration tests

Add `CompilerBridgeIntegrationTest` coverage for:
- property without explicit type -> shows `Generate Type Definition`
- property with explicit type -> shows `Regenerate Type Definition`
- property without explicit type does not show regenerate
- property with explicit type does not show generate
- generated edit inserts the correct annotation text
- regenerated edit replaces the old annotation with the newly computed type
- unsupported declarations do not surface misleading actions

#### Suggested concrete fixtures/tests

- `val name = "Ada"` -> generate `: String`
- `val items = listOf("a")` -> generate/regenerate collection type conservatively
- `val user: Any = User("Ada")` -> regenerate to `User` if recoverable
- member property inside a class
- delegated/complex property where action should be suppressed if unsafe

#### Regression coverage

Because this feature builds on existing explicit-type behavior, add a regression test that ensures:
- only one of the two actions is visible at a time
- the old “Add explicit type ...” behavior is fully superseded by the new titles/logic

### Open design questions

- Should “type definition” mean only Kotlin source type annotations, or also function return types from day one?
- Should regenerated types use short names with imports, or fully qualified names for correctness?
- Should override declarations be skipped to avoid redundant explicit types?
- Should we support destructuring declarations, or fail conservatively?

### Explicit defer list

Defer until after the first implementation:

- destructuring declarations
- lambda parameter types
- automatic import insertion/cleanup for newly introduced short names if not trivial
- style-aware formatting beyond the local replacement text
- bulk “generate all missing explicit types in file” actions
- editor prompts or multi-step flows

## Acceptance Criteria

- On a Kotlin symbol without an explicit type definition, the editor shows `Generate Type Definition` and does not show `Regenerate Type Definition`.
- Invoking `Generate Type Definition` inserts a valid explicit Kotlin type annotation for supported symbols.
- On a Kotlin symbol with an explicit type definition, the editor shows `Regenerate Type Definition` and does not show `Generate Type Definition`.
- Invoking `Regenerate Type Definition` replaces the existing explicit type annotation with the freshly computed type.
- Unsupported cases fail conservatively and do not surface misleading actions.
- Add tests covering both action visibility and edit generation for generate vs regenerate flows.
