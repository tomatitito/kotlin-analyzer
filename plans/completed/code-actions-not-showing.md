# Fix: Code Actions Not Showing + Expression/Block Body Conversion

## Problem

No code actions appear in Zed. The user expects at minimum:
- Convert expression-body function (`= expr`) to block body (`{ return expr }`)
- Convert block-body function (`{ return expr }`) to expression body (`= expr`)

## Surprise Finding

The sidecar **already implements** these code actions (CompilerBridge.kt lines 1112-1217), plus several others:

| Action | Kind | Implemented |
|--------|------|-------------|
| Convert block body → expression body | refactor | Yes |
| Convert expression body → block body | refactor | Yes |
| Add explicit type annotation | refactor | Yes |
| Auto-import unresolved reference | quickfix | Yes |
| Suppress warning | quickfix | Yes |
| Organize imports | source.organizeImports | Yes |

So the question is: **why aren't they showing up in Zed?**

## Investigation Plan

### Phase 1: Observe the Protocol (~15 min)

- [ ] **1.1** Open `my-zed` with a Kotlin project
- [ ] **1.2** Place cursor on an expression-body function (e.g., `fun add(a: Int, b: Int) = a + b`)
- [ ] **1.3** Trigger code actions (Cmd+. or right-click → Code Actions)
- [ ] **1.4** Watch `my-zed` terminal logs for:
  - Does Zed send a `textDocument/codeAction` request?
  - Does the Rust server forward it to the sidecar?
  - Does the sidecar respond with actions?
  - Does the Rust server return actions to Zed?
  - Are there any errors in the logs?

### Phase 2: Narrow Down the Failure Point (~30 min)

#### 2.1 Is the Rust server receiving the request?

- [ ] Check if the `code_action` handler in `server/src/server.rs` is being called
- [ ] Check if the bridge request to the sidecar succeeds or errors

#### 2.2 Is the sidecar returning actions?

- [ ] Add a direct test: call `bridge.codeActions()` with a URI pointing to a file containing `fun add(a: Int, b: Int) = a + b`, at the cursor position on `fun`
- [ ] Verify the response contains the "Convert to block body" action
- [ ] Check if the issue is position-dependent (line/column mismatch between LSP 0-based and sidecar 1-based)

#### 2.3 Is the Rust server parsing the response correctly?

- [ ] Check `parse_code_actions()` in `server.rs` — does it correctly map the sidecar's JSON to LSP CodeAction objects?
- [ ] Check `parse_workspace_edits()` — are edit ranges being converted correctly (1-based → 0-based)?
- [ ] Check if `CodeActionKind` values match what Zed expects

#### 2.4 Is Zed filtering out the actions?

- [ ] Check if Zed sends a `context.only` filter in CodeActionParams that excludes `refactor` kind actions
- [ ] Check if Zed requires `isPreferred` or other fields that the server doesn't set
- [ ] Check the server's capability advertisement — `CodeActionProviderCapability::Simple(true)` might be too minimal; Zed might need `CodeActionOptions` with `codeActionKinds` listed

### Phase 3: Fix (~1-2 hours depending on root cause)

#### If the sidecar isn't returning actions:

- [ ] **3.1** Debug the cursor position → PSI element resolution in `codeActions()`
- [ ] **3.2** The method walks up the PSI tree from the cursor position to find a `KtNamedFunction`. Check if the line/column mapping is correct
- [ ] **3.3** Check if the function body detection logic (lines 1112-1217) correctly identifies expression bodies vs block bodies for the test case

#### If the Rust server isn't forwarding correctly:

- [ ] **3.4** Fix `parse_code_actions()` to correctly transform the sidecar response
- [ ] **3.5** Fix any line number conversion issues (off-by-one between 0-based LSP and 1-based sidecar)
- [ ] **3.6** Ensure `WorkspaceEdit` is structured correctly

#### If Zed is filtering:

- [ ] **3.7** Upgrade capability advertisement from `Simple(true)` to `CodeActionOptions` with explicit `codeActionKinds`:
  ```rust
  CodeActionProviderCapability::Options(CodeActionOptions {
      code_action_kinds: Some(vec![
          CodeActionKind::QUICKFIX,
          CodeActionKind::REFACTOR,
          CodeActionKind::SOURCE_ORGANIZE_IMPORTS,
      ]),
      ..Default::default()
  })
  ```
- [ ] **3.8** Check if Zed requires `diagnostics` in the code action response for quickfix actions
- [ ] **3.9** Check if Zed needs `data` field for lazy resolution

### Phase 4: Enhance the Conversion Actions (~1 hour)

Once actions are showing up, polish the expression/block body conversion:

- [ ] **4.1** Verify block → expression handles multi-statement blocks correctly (should only offer when block has a single `return` statement)
- [ ] **4.2** Verify expression → block adds `return` only when return type is non-Unit
- [ ] **4.3** Verify indentation is preserved correctly in generated code
- [ ] **4.4** Test edge cases:
  - Function with no explicit return type (`fun foo() = 42`)
  - Function with when/if expression body
  - Suspend functions
  - Extension functions
  - Functions with type parameters

### Phase 5: Add More Refactoring Actions (~2 hours, optional)

Once the pipeline works, consider adding IntelliJ-style refactoring actions:

- [ ] **Convert property initializer to getter** (`val x = expr` → `val x get() = expr`)
- [ ] **Convert to/from string template** (`"Hello, " + name` ↔ `"Hello, $name"`)
- [ ] **Add/remove braces from when entry** (single expression vs block)
- [ ] **Convert to/from named arguments** (`foo(1, "a")` ↔ `foo(x = 1, s = "a")`)

### Phase 6: Test and Verify (~30 min)

- [ ] **6.1** Run existing code action integration tests
- [ ] **6.2** Add new tests for expression/block body conversion
- [ ] **6.3** Visual verification in `my-zed`:
  - Place cursor on `fun add(a: Int, b: Int) = a + b`
  - Trigger code actions → see "Convert to block body"
  - Apply action → verify result is `fun add(a: Int, b: Int): Int { return a + b }`
  - Trigger again → see "Convert to expression body"
  - Apply action → verify round-trip back to `= a + b`

## Key Files

| File | What to check/modify |
|------|---------------------|
| `server/src/server.rs` ~line 1237 | `code_action` handler — request forwarding |
| `server/src/server.rs` ~line 1799 | `parse_code_actions` / `parse_workspace_edits` — response parsing |
| `server/src/server.rs` ~line 355 | Capability advertisement — may need `CodeActionOptions` |
| `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` ~line 882 | `codeActions()` — action generation |
| `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` ~line 1112 | Block/expression body conversion logic |
| `sidecar/src/test/kotlin/dev/kouros/sidecar/CompilerBridgeIntegrationTest.kt` | Existing code action tests |

## Debugging Checklist

Quick protocol-level debugging steps:

```bash
# 1. Start my-zed and watch logs
my-zed /path/to/kotlin/project

# 2. Look for these log lines:
#    → textDocument/codeAction  (Zed sending request)
#    → codeActions              (server forwarding to sidecar)
#    ← { actions: [...] }       (sidecar response)
#    ← textDocument/codeAction  (server response to Zed)

# 3. If no codeAction request appears, Zed isn't requesting them
# 4. If request appears but empty response, check sidecar logic
# 5. If sidecar returns actions but Zed shows nothing, check parsing
```
