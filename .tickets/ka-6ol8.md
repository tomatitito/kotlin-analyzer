---
id: ka-6ol8
status: open
deps: []
links: []
created: 2026-03-30T14:03:23Z
type: feature
priority: 2
assignee: Jens Kouros
tags: [pebble, sidecar, lsp]
---
# Support Pebble include-with variable forwarding for go to definition

Add support for resolving Pebble variables inside included templates when they are forwarded via `{% include ... with { ... } %}`.

This should allow go to definition and Kotlin references to work for cases where a Spring controller adds a model attribute to a top-level template and that template forwards the variable into a child template using an include-with map.

## Design

## Implementation plan

### `sidecar/src/main/kotlin/dev/kouros/sidecar/PebbleTemplateIndex.kt`

- [ ] Add a new fact type for include-with variable forwarding
  - e.g. `PebbleIncludeVariableBinding`
  - fields:
    - `targetTemplate: String`
    - `targetVariableName: String`
    - `sourceSegments: List<String>`

- [ ] Extend `PebbleTemplateFacts`
  - add `includeVariableBindings: List<PebbleIncludeVariableBinding>`

- [ ] Extend `PebbleTemplateParser`
  - parse `{% include ... with { ... } %}` blocks
  - extract static key/value bindings from the `with` map
  - support values that are simple variable chains:
    - `foo`
    - `foo.bar`
    - `foo.bar()`
  - normalize segments the same way as existing variable reference parsing

- [ ] Keep parsing conservative
  - skip entries with:
    - non-string keys
    - dynamic expressions
    - unsupported syntax

- [ ] Add a resolved binding type
  - e.g. `ResolvedIncludeBinding`
  - fields:
    - `sourceTemplateUri`
    - `targetTemplateUri`
    - `targetVariableName`
    - `sourceSegments`

- [ ] Add an index/query method to expose incoming include bindings for a child template
  - e.g.:
    - `fun incomingIncludeBindings(targetTemplateUri: String): List<ResolvedIncludeBinding>`

- [ ] Resolve include target templates using existing `resolveTemplate(...)`

### `sidecar/src/main/kotlin/dev/kouros/sidecar/PebbleSpringIndex.kt`

- [ ] Refactor root-variable resolution to support recursive forwarding
  - likely update:
    - `resolveVariableForRoot(uri, rootName)`

- [ ] Add cycle protection
  - use a visited set keyed by `(templateUri, variableName)`

- [ ] Add helper to resolve a variable chain in a template context
  - e.g.:
    - `resolveVariableForSegments(uri: String, segments: List<String>): PebbleResolvedVariable?`

- [ ] Update root resolution order
  - try direct Spring model fact
  - try `@pebvariable` hint
  - try for-loop alias
  - try incoming include binding from parent template

- [ ] For include binding resolution
  - map child variable name to parent `sourceSegments`
  - resolve those segments in the parent template context
  - return the resulting producer location/type

- [ ] Verify `definitionForPebbleSymbol(...)` benefits automatically from the new root resolution
  - root variable in included template
  - nested property access in included template

- [ ] Verify `pebbleUsagesForDeclaration(...)` benefits automatically
  - Kotlin references should now include usages inside included templates

### Tests: `sidecar/src/test/kotlin/dev/kouros/sidecar/PebbleTemplateIndexTest.kt`

- [ ] Add parser test for include-with bindings
  - input:
    - include target
    - `with { "outfitPath": outfitPath, "items": bestandteil.empfehlungen }`
  - assert:
    - include binding count
    - keys
    - normalized source segments

- [ ] Add conservative-failure parser test
  - unsupported expression in `with`
  - assert it is skipped

### Tests: `sidecar/src/test/kotlin/dev/kouros/sidecar/PebbleSpringIndexTest.kt`

- [ ] Add fixture setup for a parent template that includes a child with forwarded variables

- [ ] Add definition test
  - child template `{{ outfitPath }}`
  - resolves to Kotlin producer from parent controller `addAttribute("outfitPath", ...)`

- [ ] Add references test
  - Kotlin producer references include child template usage location

- [ ] Add nested forwarding test if feasible
  - parent passes `"items": bestandteil.empfehlungen`
  - child uses `items`

- [ ] Add conservative-failure test
  - unsupported include-with expression does not mis-resolve

### Fixtures under `tests/fixtures/...`

- [ ] Add or update parent Pebble template with:
  - `{% include "./child" with { ... } %}`

- [ ] Add child Pebble template that references the forwarded variable

- [ ] Add/update Kotlin fixture controller if needed
  - ensure it returns the parent template
  - ensure it sets the relevant `addAttribute(...)`

### Verification

- [ ] Run targeted sidecar tests
  - `cd sidecar && ./gradlew test`

- [ ] Run project tests as appropriate
  - `cargo test`
  - or at least the relevant Kotlin/sidecar path tests

- [ ] Manually verify on the real project case
  - `RenderBestandteileController.kt`
  - `src/frontend/templates/bestandteile/bestandteile.peb`
  - `src/frontend/templates/bestandteile/bestandteileInhalt.peb`

- [ ] Check that `go to definition` on forwarded vars in included templates now lands on the correct Kotlin producer

### Suggested first slice

- [ ] parse include-with bindings
- [ ] resolve only simple root forwarding like `"outfitPath": outfitPath`
- [ ] add 1 parser test
- [ ] add 1 end-to-end spring index test

## Acceptance Criteria

- Variables in included Pebble templates resolve back to Kotlin producers when forwarded via `{% include ... with { ... } %}`.
- Kotlin references include usages from included templates reached through include-with forwarding.
- Unsupported or dynamic include-with expressions fail conservatively without mis-linking symbols.
- Existing direct-template Pebble symbol resolution continues to work.
