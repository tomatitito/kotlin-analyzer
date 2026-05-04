---
id: ka-26kw
status: open
deps: [ka-ceoo]
links: [ka-35z8, ka-gjfa]
created: 2026-03-31T12:15:03Z
type: feature
priority: 2
assignee: Jens Kouros
tags: [code-actions, testing, lsp, zed]
---
# Add Kotlin unit-test code action for existing-or-new tests

Add a Kotlin code action on class declarations that opens an existing matching unit test or creates a new one when absent. Use conservative conventions only for the first slice: src/main/kotlin -> src/test/kotlin, mirrored package path, Foo -> FooTest. Surface as a concrete action such as 'Open or Create Unit Test'. This ticket is split out of ka-gjfa.

## Acceptance Criteria

- On a Kotlin class with an existing unit test, invoking the action opens the unit test class.
- On a Kotlin class without a matching unit test, invoking the action creates a new unit test.
- The action is only offered when the server can conservatively determine source root, package, class name, and target test path.
- Matching uses mirrored package path plus exact filename/classname only.
- Add tests covering existing-test navigation and new-test generation for unit tests.


## Notes

**2026-03-31T12:22:35Z**

Refined unit-test implementation guidance after codebase review:

Testing strategy should be staged around the actual architecture constraints.

1. Add planner-level tests first.
   - Extract unit-test target planning into a pure helper that takes source URI/path, package name, class name, and returns either:
     - Existing(targetUri, targetClassName)
     - Create(targetPath, targetUri, targetClassName, fileContents)
     - Unavailable(reason)
   - Cover exact conservative rules only for the first slice:
     - `src/main/kotlin` -> `src/test/kotlin`
     - mirrored package path
     - exact `Foo` -> `FooTest`
     - existing file detection under the mirrored package
     - rejection when the source file is not under `src/main/kotlin`
     - rejection when the project layout is ambiguous or the test root cannot be determined conservatively

2. Add sidecar integration tests for action discovery on class declarations.
   - Verify the action is offered on a `KtClass` declaration/name.
   - Verify the returned payload matches the chosen transport contract for either:
     - existing-test navigation, or
     - missing-test creation
   - Keep these tests independent from diagnostic-driven code actions. The current `codeActions()` path still does diagnostic work first, but the unit-test action itself should not rely on diagnostics.

3. Add Rust LSP tests for the transport contract before wiring UX expectations.
   - Important current constraint: `server/src/server.rs` only parses sidecar code actions as flat text edits and always emits `command: None`.
   - That means “open existing test” and “create new file then open it” are not fully representable today unless implementation also extends the Rust server.
   - If implementation uses `WorkspaceEdit`, add coverage for `documentChanges` / `CreateFile` style parsing.
   - If implementation uses commands, add coverage for command parsing/execution and client-visible navigation behavior.

4. Add one fixture-backed end-to-end creation test with a real test classpath.
   - Existing fixtures do not yet give strong coverage for generated JUnit-backed unit tests.
   - Add a dedicated Gradle fixture with:
     - `src/main/kotlin`
     - `src/test/kotlin`
     - JUnit 5 test dependency
   - Verify that generated `FooTest.kt` is syntactically valid Kotlin and still resolves main-source symbols when opened/analyzed.

5. Keep existing-test and missing-test paths separate in assertions.
   - “Open existing test” should assert the exact target URI/class and should not rely on file writes.
   - “Create missing test” should assert the exact target path, filename, package declaration, class declaration, and minimal skeleton contents.

Known implementation risks to account for in the tests:
- Rust LSP code action parsing is currently edit-only, which is insufficient for open-or-create behavior without a server-side protocol extension.
- Source-root discovery in current code is biased toward main sources and does not automatically discover `src/test/kotlin`.
- Existing server integration tests only prove that code actions are returned, not that file creation/opening/navigation works end to end.

**2026-03-31T12:52:51Z**

Implementation gate / first decision:

Before coding the unit-test action itself, the implementation must choose and wire the transport contract that makes the UX possible in the current architecture.

Required first step:
- Decide how the Rust LSP server will represent:
  - opening an existing test file, and
  - creating a missing test file and then opening/navigating to it

This is not fully supported by the current edit-only code action parsing in `server/src/server.rs`.

Acceptable first-slice options:
1. Extend code action handling to support command-based navigation/execution.
2. Extend code action handling to support `WorkspaceEdit.documentChanges` / `CreateFile` style operations if that is sufficient for Zed.
3. Narrow the first implementation slice explicitly if needed, but only if the resulting acceptance criteria are updated to match.

Do not start planner/action plumbing before this transport choice is made, because it determines the sidecar payload shape, Rust parsing work, and the end-to-end tests that need to be written.

**2026-03-31T13:03:41Z**

Implementation sequencing decision: do not start the unit-test creation feature until the command-based code action infrastructure ticket is in place. Even though create-only could be implemented via documentChanges/CreateFile, we are standardizing first on command-capable code action plumbing in the Rust LSP server so the later open/create flows share one transport model.

**2026-03-31T16:41:27Z**

Selection/range behavior is now explicit for the first slice:\n- On open-existing-test, place the cursor at the beginning of the first test method in the target test class.\n- On create-new-test, generate a first empty test method named `hurz` and place the cursor inside that method body.\n- The planner/generator should compute and include a concrete selection range in the command payload rather than leaving selection unset, so Rust only executes the provided navigation target and selection.\n- For the create path, the generated Kotlin skeleton should include the mirrored package declaration, a `FooTest` class, an import for `org.junit.jupiter.api.Test`, and an empty first test method named `hurz`.
