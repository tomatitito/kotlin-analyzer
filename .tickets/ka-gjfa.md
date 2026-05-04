---
id: ka-gjfa
status: open
deps: []
links: [ka-35z8, ka-26kw, ka-ceoo]
created: 2026-03-30T21:38:01Z
type: feature
priority: 2
assignee: Jens Kouros
tags: [code-actions, testing, lsp, zed]
---
# Add Kotlin test code action for existing-or-new tests

Umbrella ticket for Kotlin code actions on class names that either open a matching test class when one already exists or generate a new test when it does not. The user-facing split is between unit tests and integration tests.

## Design

Scope:
- Trigger on Kotlin class names / declarations.
- Detect an existing matching test class and navigate to it.
- If no matching test exists, generate a new test class/file.
- Present a choice between unit test and integration test targets before creating/opening.

Implementation ideas:
- Extend code action plumbing in the Rust LSP frontend and sidecar as needed.
- Define naming and placement conventions for unit vs integration tests.
- Reuse project/source-root knowledge to locate or create target files.
- Prefer conservative behavior when conventions are ambiguous.
- Consider whether the action should be one code action with parameters or multiple concrete actions (e.g. Open/Create Unit Test, Open/Create Integration Test).
- Ensure editor UX works in Zed with either quick-pick-like selection or multiple surfaced actions.

## Implementation Outline

### Recommended first slice

1. Surface concrete code actions on Kotlin class declarations:
   - `Open or Create Unit Test`
   - `Open or Create Integration Test`
2. Reuse simple naming + path conventions only.
3. If a matching test file exists, navigate to it.
4. If it does not exist, create a minimal test file and then navigate to it.
5. Defer richer UX such as secondary prompts or project-specific templates until the basic flow works end to end.

### Proposed UX

Because LSP/Zed code actions are easiest to expose as multiple concrete actions, prefer:

- `Open Unit Test`
- `Create Unit Test`
- `Open Integration Test`
- `Create Integration Test`

or, if open/create should be merged per kind:

- `Open or Create Unit Test`
- `Open or Create Integration Test`

This still satisfies the “choose unit vs integration” requirement without needing a second interactive prompt from the editor.

### Conventions to define up front

Document and implement conservative conventions for:

- source roots:
  - `src/main/kotlin`
- unit test roots:
  - `src/test/kotlin`
- integration test roots:
  - likely `src/integrationTest/kotlin`, `src/e2eTest/kotlin`, or another project-standard root
- naming:
  - source class `FooService` -> unit test `FooServiceTest`
  - source class `FooController` -> integration test `FooControllerIntegrationTest`
- package mirroring:
  - preserve the source package under the chosen test root

If multiple candidate integration-test roots or naming schemes are present, fail conservatively and do not offer a misleading create action.

### Suggested implementation steps

#### 1. Extend code action detection for Kotlin class declarations

Identify when the cursor/range is on:
- a `KtClass`
- a `KtObject` if desired later
- optionally a `KtNamedFunction` later, but keep the first slice class-only

Only offer the action when the server can recover:
- class name
- package name
- file/source root
- target test root for the requested test kind

#### 2. Add a test-target locator/planner

Introduce a helper on the sidecar or Rust server side that, given:
- source file URI
- package name
- class name
- test kind (`unit` | `integration`)

returns a plan such as:
- existing target file URI + class name
- or new target file path + class name + package declaration + file contents
- or failure with a conservative reason

A possible shape:
- `TestTargetKind { UNIT, INTEGRATION }`
- `TestTargetPlan { kind, targetUri/path, targetClassName, exists, createContents? }`

#### 3. Detect existing tests conservatively

Search under the chosen test root for likely matches using:
- mirrored package path
- expected filename
- expected class name

Keep matching simple at first:
- exact file name match
- exact class name match

Do not try fuzzy heuristics in the first implementation.

#### 4. Generate new tests when absent

Create a minimal Kotlin test file with:
- package declaration
- test class declaration
- one placeholder test method
- correct imports for the test style in use

Example unit skeleton:
- JUnit 5 `@Test`

Example integration skeleton:
- either another JUnit-based skeleton or a project-specific integration-test base if one is already standardized

If the project has no clear integration-test convention, the integration create action should be omitted or return a conservative error.

#### 5. Wire command execution through the LSP frontend

If the code action requires file creation, add an executable command path that can:
- create directories/files
- write the new file
- return the location to open

If the file already exists, return a navigation/edit command only.

Depending on current architecture, this may be implemented as:
- a code action with `WorkspaceEdit`
- a code action with `command`
- or a combined edit + command flow

#### 6. Ensure Zed-compatible navigation behavior

Verify that invoking the action from Zed:
- opens the existing test file at the class
- or opens the newly created file after creation

If “open file” is easier via returned workspace edit plus location, prefer the simplest LSP-compatible path Zed handles reliably.

#### 7. Add configuration or convention overrides later if needed

If the project needs more than one integration-test root/naming convention, add config later, for example:
- test root overrides
- integration naming suffix override
- template customization

Do not block the first implementation on making this fully configurable.

### Likely code areas

#### Rust frontend

- code action request routing
- command execution plumbing
- workspace edit / file creation support
- URI/path handling

#### JVM sidecar

- PSI detection of Kotlin class declarations under the cursor
- package/class/source-root extraction
- target test planning logic
- optional project-convention discovery

### Tests to add

#### Sidecar/unit tests

- class under `src/main/kotlin` maps to unit test target under `src/test/kotlin`
- class under `src/main/kotlin` maps to integration target under configured/conventional integration root
- existing test detection returns open-plan instead of create-plan
- ambiguous or missing roots fail conservatively

#### LSP / integration tests

- code action appears on Kotlin class name
- invoking unit action creates a new file when absent
- invoking unit action opens existing file when present
- invoking integration action creates/opens the integration file
- created file content is syntactically valid Kotlin

#### Fixture coverage

Add fixtures for:
- source class only
- source class + existing unit test
- source class + existing integration test
- ambiguous multi-root project
- project with no detectable integration convention

### Explicit defer list

Defer until after the first working version:

- generating method-level test stubs from all public methods
- supporting nested/inner classes
- framework-specific Spring/ktor test bootstrapping beyond a minimal template
- user-customizable prompt flows inside the editor
- fuzzy matching against irregular historical test naming
- generating both unit and integration tests in one action

## Acceptance Criteria

- On a Kotlin class with an existing unit test, invoking the action opens the unit test class.
- On a Kotlin class with an existing integration test, invoking the action can open the integration test class.
- On a Kotlin class without a matching test, invoking the action can create a new unit test.
- On a Kotlin class without a matching test, invoking the action can create a new integration test.
- The user can choose unit vs integration before creation/opening.
- Behavior is conservative when no clear source/test root or naming convention can be determined.
- Add tests covering existing-test navigation and new-test generation for both test kinds.


## Notes

**2026-03-31T12:15:08Z**

Split on 2026-03-30 into two narrower tickets per discussion: ka-26kw (unit tests) and ka-35z8 (integration tests). Also rename user-facing 'e2e' terminology to 'integration'. Recommend treating ka-gjfa as umbrella/context ticket rather than the direct implementation ticket.
