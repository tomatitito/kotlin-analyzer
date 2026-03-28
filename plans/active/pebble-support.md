# Pebble Template Support

## Goal

Add Pebble-aware language support to kotlin-analyzer so Zed can:

- open Pebble files as a first-class language in the extension
- go to definition from a Pebble template into Kotlin declarations where the mapping is statically knowable
- include Pebble usages in `textDocument/references` results for Kotlin symbols
- navigate between Pebble templates for `include`, `extends`, `import`, macros, and blocks

The design constraint is that current semantic navigation is Kotlin-only and implemented in the JVM sidecar against Kotlin PSI / Analysis API. Pebble support therefore needs a parallel template-analysis path plus a cross-language index that connects Spring MVC template rendering and model attributes back to Kotlin symbols.

## Non-Goals

- Full IntelliJ-grade semantic understanding of all Pebble features in the first release
- Dynamic runtime data-flow through arbitrary helper methods, reflection, or maps with unknown keys
- Generic support for all server-side templating engines in the same milestone
- Diagnostics, completion, formatting, or rename for Pebble unless they materially help the navigation MVP

## Current State

- The Zed extension only registers Kotlin grammar and Kotlin language server attachment.
- The Rust server advertises definition and references globally, but the JVM sidecar assumes Kotlin source documents.
- `definition` and `references` are implemented in `CompilerBridge` using Kotlin PSI and Analysis API resolution.
- There is no Pebble grammar, no Pebble language config, and no cross-language index for Spring view names or model attributes.

## Principles

1. Start with deterministic, statically recoverable mappings only.
2. Keep Pebble analysis separate from Kotlin Analysis API code paths.
3. Build cross-language navigation around explicit indexed facts, not ad hoc string searches.
4. Return partial results rather than blocking on unsupported dynamic cases.
5. Ship template-to-template and controller-to-template navigation before deep expression resolution.

## Phase 1: MVP

### User-visible scope

- Zed recognizes `.peb`, `.pebble`, and configured Pebble template paths as a Pebble language.
- `Go to Definition` works for:
  - Pebble `{% include %}`, `{% extends %}`, `{% import %}` target templates
  - Spring controller return values such as `return "users/detail"` to the matching Pebble file
  - `ModelAndView("users/detail")` to the matching Pebble file
  - Pebble variables whose origin is a straightforward Spring model binding, for example:
    - `model.addAttribute("user", user)`
    - `mv.addObject("user", user)`
    - `@ModelAttribute("user") fun ...`
- `Find All References` works for:
  - Pebble template file references from Kotlin controllers
  - Kotlin symbols referenced by Pebble variables or simple property chains when the root variable can be resolved from indexed model bindings

### Implementation tasks

#### Extension and language registration

- Add a Pebble language config under `languages/pebble/`.
- Register a Pebble grammar in `extension.toml`.
- Attach the existing `kotlin-analyzer` language server to both Kotlin and Pebble.
- Confirm Zed opens Pebble files with the new language id and routes LSP requests to the server.

#### Rust server routing

- Detect document kind from URI/path suffix.
- Branch `didOpen`, `didChange`, `didClose`, `definition`, and `references` by document kind.
- Extend bridge protocol with Pebble-aware methods rather than forcing Pebble files through Kotlin handlers:
  - `pebble/textDocument/didOpen`
  - `pebble/textDocument/didChange`
  - `pebble/textDocument/didClose`
  - `pebble/definition`
  - `pebble/references`
- Keep existing Kotlin handlers unchanged for `.kt` and `.kts`.

#### JVM sidecar Pebble subsystem

- Add a Pebble document store separate from Kotlin virtual files.
- Add a lightweight Pebble parser or token walker that can identify:
  - template path literals in `include`, `extends`, and `import`
  - variable roots like `user`
  - simple property chains like `user.name`
  - macro and block declarations/usages needed for template navigation
- Build a Pebble symbol/reference index keyed by URI and source range.

#### Cross-language Spring index

- Index view-to-template mappings from Kotlin code:
  - string return values in Spring MVC handlers
  - `ModelAndView(...)`
  - optionally constants used directly in those contexts when trivially resolvable
- Index model attribute producers:
  - `model.addAttribute("name", expr)`
  - `ModelAndView.addObject("name", expr)`
  - `@ModelAttribute("name")`
- Resolve the expression type for attribute values via Analysis API where possible.
- Store facts in a dedicated cross-language index:
  - template name -> Pebble file URI
  - controller location -> template URI
  - template URI + variable name -> Kotlin declaration/type origin

#### Definition and references integration

- Implement Pebble `definition`:
  - template literals navigate to template files
  - variable roots navigate to the Kotlin declaration or producer site when indexed
  - simple property chains navigate to the resolved Kotlin property/function when the root type is known
- Extend Kotlin `references` to append Pebble occurrences from the cross-language index.
- Implement Pebble `references` for template/template and template/Kotlin symbol edges discovered by the index.

#### Tests

- Add fixtures for a minimal Spring Boot + Pebble project.
- Add sidecar tests for:
  - controller string view name -> Pebble template definition
  - Pebble include/extends/import definition
  - Pebble variable -> Kotlin model producer definition
  - Kotlin property/class references including Pebble usages
- Add end-to-end verification in Zed with `my-zed`.

### Success criteria

- Opening a Pebble file in Zed uses the Pebble language and attaches the same server.
- From `{{ user.name }}`, definition can reach the Kotlin producer for `user` and then the `name` declaration in simple cases.
- From a Kotlin property or type used in Pebble, references include the Pebble template occurrence.
- From `return "users/detail"`, definition reaches `templates/users/detail.peb` or equivalent configured path.

## Phase 2: Spring MVC Coverage Expansion

### Scope

Broaden the static model/view extraction so navigation works for more real Spring code without requiring full interprocedural analysis.

### Tasks

- Support template names built from simple constants and string templates.
- Support helper wrappers around `Model`, `ModelMap`, and `ModelAndView` when the attribute name and value remain statically visible.
- Support class-level and shared `@ModelAttribute` providers.
- Support additional controller return styles:
  - `String`
  - `ModelAndView`
  - `Rendering`
  - view name constants
- Add configurable template root resolution for common Spring Boot layouts:
  - `src/main/resources/templates`
  - custom Pebble prefix/suffix from application config when statically available

### Success criteria

- Most conventional Spring MVC + Pebble projects work without code changes.
- Unsupported cases degrade cleanly to empty results rather than wrong results.

## Phase 3: Deeper Pebble Semantics

### Scope

Improve template-local navigation beyond raw variables and file paths.

### Tasks

- Track macro declarations and usages.
- Track block declarations and overrides across inheritance chains.
- Support navigation across imported macro aliases.
- Improve expression understanding:
  - method calls on known model types
  - collection element access where the collection element type is recoverable
  - loop variables whose source collection type is known
- Add workspace symbol style indexing for templates if useful.

### Success criteria

- Template-to-template navigation covers common Pebble authoring patterns.
- Simple inherited template structures are navigable without manual searching.

## Phase 4: Reliability and UX

### Scope

Make the feature robust enough for day-to-day use and release.

### Tasks

- Add logging and tracing for cross-language resolution misses.
- Add explicit `reason` fields for empty Pebble results where useful.
- Add snapshot tests for LSP responses spanning Kotlin and Pebble documents.
- Measure performance on medium Spring Boot projects and cap indexing costs.
- Ensure sidecar restart replay restores both Kotlin and Pebble open-document state.
- Document supported and unsupported Pebble/Spring patterns.

### Success criteria

- Pebble support survives sidecar restart and project reloads.
- Cross-language navigation stays within acceptable latency for normal projects.
- Documentation clearly states the supported static subset.

## Open Design Questions

1. Grammar choice: adopt an existing tree-sitter grammar for Pebble, fork one, or keep syntax support intentionally minimal at first?
2. Protocol shape: new Pebble-specific bridge methods versus a generalized document-kind aware method envelope?
3. Template resolution: should template roots and suffixes come only from convention in MVP, or also from parsed Spring configuration?
4. Spring coverage boundary: how much Analysis API effort is justified before introducing a lightweight Kotlin index dedicated to MVC facts?
5. Reference identity: should Pebble references attach to declarations, types, or producer sites when a variable is supplied by an expression rather than a named symbol?

## Risks

- Pebble has weaker ecosystem support than major template languages, so parser/grammar quality may require local maintenance.
- Spring MVC model flow is partly dynamic; aggressive inference can produce incorrect navigation.
- Cross-language indexing increases replay and invalidation complexity in the sidecar.
- Returning incorrect references is worse than returning none; conservative resolution is required.

## Recommended Execution Order

1. Phase 1 extension/language plumbing
2. Phase 1 Pebble parser and template-to-template navigation
3. Phase 1 Spring view/model indexing
4. Phase 1 Kotlin/Pebble cross-language definition and references
5. Phase 2 coverage expansion
6. Phase 3 deeper template semantics
7. Phase 4 reliability and release hardening
