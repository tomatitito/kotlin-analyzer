---
id: ka-2pai
status: in_progress
deps: [ka-3maw]
links: [ka-q5t5, ka-rlvq, ka-888e, ka-quxz, ka-gwtp, ka-jnj8]
created: 2026-03-28T21:36:35Z
type: task
priority: 2
assignee: Jens Kouros
external-ref: TOM-72
parent: ka-7dbe
tags: [pebble, mvp, tests, zed]
---
# Pebble MVP: fixtures, regression tests, and Zed verification

Phase 1, step 6. Lock the MVP down with fixtures and end-to-end validation.

## Acceptance Criteria

- Automated tests cover the MVP navigation paths.
- Manual Zed verification confirms the feature works in the editor.
- Regressions in Kotlin-only navigation are caught by the test suite.

## Current Test Coverage Status

### Tests Already Passing (26 tests across 2 files)

**PebbleTemplateIndexTest.kt** — 12 tests covering:
- Template reference parsing, variable resolution, include bindings
- On-disk fallback, relative path resolution, Spring view name resolution
- Document change/removal invalidation
- CompilerBridge JSON location exposure

**PebbleSpringIndexTest.kt** — 14 tests covering:
- String return and ModelAndView view name → template navigation
- Model.addAttribute() and ModelAndView.addObject() → Pebble variable linking
- Property segment navigation to Kotlin declarations
- Kotlin → Pebble references (bidirectional)
- For-loop alias → collection element property resolution
- @pebvariable typed method definitions and Kotlin references
- Include-with variable forwarding (both directions)
- Conservative failure for dynamic/unsupported expressions

### Navigation Paths: Tested vs. Untested

| Path | Status | Test Coverage |
|------|--------|--------------|
| Spring view name → template file | Tested | PebbleSpringIndexTest (2 tests) |
| Model attribute → Pebble variable | Tested | PebbleSpringIndexTest (2 tests) |
| Property chains (e.g. `user.name`) | Tested | PebbleSpringIndexTest |
| @pebvariable type hints | Tested | PebbleSpringIndexTest |
| For-loop aliases (`{% for x in items %}`) | Tested | PebbleSpringIndexTest (2 tests) |
| Collection element member access | Tested | PebbleSpringIndexTest |
| Typed method calls (e.g. `.marke()`) | Tested | PebbleSpringIndexTest |
| Include-with variable forwarding | Tested | PebbleSpringIndexTest (2 tests) |
| Template-to-template navigation | Tested | PebbleTemplateIndexTest |
| Kotlin → Pebble references | Tested | PebbleSpringIndexTest (2 tests) |
| Complex nested expressions | Not tested | Edge case — conservative failure expected |
| Dynamic template names/views | Tested (negative) | Conservative failure confirmed |

## Remaining Work

The MVP navigation features identified in the March 30 note have since been implemented and tested (ka-jnj8 addressed @pebvariable, for-loop aliases, and collection member access). The remaining work is:

### Step 1: Verify existing tests pass

```bash
cd sidecar && ./gradlew test --tests dev.kouros.sidecar.PebbleTemplateIndexTest
cd sidecar && ./gradlew test --tests dev.kouros.sidecar.PebbleSpringIndexTest
```

### Step 2: Add Kotlin-only navigation regression test

Verify that Pebble support does not break standard Kotlin navigation. Add a test in `CompilerBridgeIntegrationTest.kt` or a dedicated regression file:
- Open a Kotlin file and a Pebble file in the same session
- Verify go-to-definition on a Kotlin symbol still works
- Verify Kotlin references still return correct locations

### Step 3: Manual Zed verification

Prerequisite: ka-q5t5 (Pebble grammar registration) must be resolved so Zed recognizes `.peb` files.

Once Pebble files are recognized:
1. Open `tests/fixtures/pebble-spring-mvc/` in my-zed
2. Verify go-to-definition from `{{ user.name }}` in `detail.peb` → `UsersController.kt` addAttribute
3. Verify find-all-references on `addAttribute("user", ...)` in controller → includes Pebble template locations
4. Verify go-to-definition from `{% include "..." %}` → target template file
5. Document results in this ticket's notes

### Step 4: Document MVP scope

Update `docs/lsp-protocol.md` or a new `docs/pebble-support.md` documenting:
- Supported navigation paths
- Known limitations (dynamic expressions, complex macros)
- Conservative failure behavior

## Notes

**2026-03-30T12:40:01Z**

Manual verification in /Volumes/sourcecode/meta-breuninger/entdecken/reco/outfit-genie found remaining gaps in Pebble Find All References. Current MVP works for direct controller model bindings and simple property chains in fixtures, but real templates rely on `{# @pebvariable ... #}` type hints, `{% for item in items %}` loop-variable aliases, collection element member access (e.g. `outfit.path`), and typed method calls like `farbartikel.marke()`. These cases are not currently indexed, so real-world references/definitions fail conservatively. Do not close ka-2pai until manual Zed verification is either documented as MVP-only or the follow-up coverage is addressed.

**2026-04-14T00:00:00Z**

Assessment update: The navigation gaps identified on 2026-03-30 have been addressed by ka-jnj8 (landed as commit c67801a and predecessors). All 26 sidecar tests pass covering @pebvariable hints, for-loop aliases, collection element member access, typed method calls, and include-with forwarding. Remaining work is Kotlin regression coverage, manual Zed verification (blocked on ka-q5t5), and MVP scope documentation.
