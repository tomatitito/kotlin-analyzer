---
id: ka-q5t5
status: in_progress
deps: []
links: [ka-rlvq, ka-2pai, ka-888e]
created: 2026-03-28T22:48:37Z
type: task
priority: 2
assignee: Jens Kouros
external-ref: TOM-71
parent: ka-7dbe
tags: [pebble, zed, tree-sitter, syntax-highlighting]
---
# Pebble: add correct tree-sitter grammar for Pebble templates

The current Zed extension points Pebble at the wrong grammar source. extension.toml references HarmonicLabs/pebble-tree-sitter, which is a different language named Pebble rather than the Pebble template language used by .peb/.pebble files. As a result, syntax highlighting is missing or incorrect, and Zed may fail to recognize Pebble templates consistently. Add or create the correct tree-sitter grammar for Pebble templates, wire it into the extension manifest, and provide the required query files for highlighting and basic editor behavior.

## Acceptance Criteria

- Zed recognizes .peb and .pebble files with a Pebble template grammar rather than the unrelated Pebble language grammar.
- Syntax highlighting works for common Pebble template constructs such as {{ ... }}, {% ... %}, and {# ... #}.
- The extension manifest references the correct grammar source and no longer depends on the unrelated HarmonicLabs grammar for template parsing.
- Basic query files exist for Pebble so highlighting/brackets/indent behavior work in Zed.
- Verified locally in my-zed with a Pebble template fixture.


## Notes

**2026-03-28T23:02:07Z**

Started implementation. Replacing HTML placeholder grammar with vendored Pebble template grammar covering output, comment, and tag syntax plus query files/tests.

**2026-03-28T23:11:05Z**

Implemented vendored Pebble tree-sitter grammar under vendor/tree-sitter-pebble, rewired extension manifest to that grammar source, replaced HTML-based Pebble queries/config, added Pebble fixture, generated grammars/pebble.wasm, and verified tree-sitter test plus query compilation. Attempted my-zed verification on tests/fixtures/pebble/BasicTemplate.peb; file opened but status bar still showed Unknown, so editor-side language registration/loading remains unresolved.

## Current Implementation Status

### Completed

All extension-side artifacts are in place:

| Artifact | Path | Status |
|----------|------|--------|
| Grammar source | `vendor/tree-sitter-pebble/grammar.js` | Complete — output, directive, comment syntax |
| Compiled WASM | `grammars/pebble.wasm` | Built (13.7 KB) |
| Extension manifest | `extension.toml` | Pebble registered, grammar points to vendor, LS attached |
| Language config | `languages/pebble/config.toml` | `name = "Pebble"`, `path_suffixes = ["peb", "pebble"]` |
| Highlights | `languages/pebble/highlights.scm` | Keywords, operators, literals, strings, comments |
| Brackets | `languages/pebble/brackets.scm` | Rainbow brackets for `{{ }}`, `{% %}`, `{# #}` |
| Indents | `languages/pebble/indents.scm` | Template delimiter indentation |
| Injections | `languages/pebble/injections.scm` | HTML injection into `(text)` nodes |
| Overrides | `languages/pebble/overrides.scm` | Comment/string/keyword override queries |
| Test fixtures | `tests/fixtures/pebble-spring-mvc/` | Multiple `.peb` template files |

### Remaining: Zed Language Registration

The extension builds and all query files compile, but Zed shows "Unknown" for `.peb` files. This is an editor-side loading/registration issue.

## Remaining Steps

### Step 1: Diagnose Zed extension loading

Check if the extension is properly loaded with Pebble support:
```bash
# Check extension symlink
ls -la ~/Library/Application\ Support/Zed/extensions/installed/kotlin-analyzer/

# Check Zed logs for grammar loading errors
grep -i "pebble\|grammar\|language" ~/Library/Logs/Zed/Zed.log | tail -30
```

### Step 2: Verify WASM binary is loadable

Ensure `grammars/pebble.wasm` is a valid WASI binary:
```bash
file grammars/pebble.wasm
wasm-tools validate grammars/pebble.wasm  # if available
```

### Step 3: Check extension manifest grammar reference

Verify `extension.toml` grammar section matches Zed's expected format. The grammar name in `extension.toml` must match the `grammar` field in `languages/pebble/config.toml`:
- `config.toml`: `grammar = "pebble"`
- `extension.toml`: grammar entry must be keyed as `pebble`

### Step 4: Rebuild and reinstall extension

```bash
# Rebuild WASM if needed
cd vendor/tree-sitter-pebble && tree-sitter generate
cd ../.. && tree-sitter build --wasm -o grammars/pebble.wasm vendor/tree-sitter-pebble

# Reinstall extension symlink
my-zed --reinstall-extension kotlin-analyzer  # or equivalent
```

### Step 5: Clear Zed cache and restart

```bash
# Clear grammar cache
rm -rf ~/.cache/zed/grammars/
# Restart Zed
my-zed tests/fixtures/pebble-spring-mvc/src/main/resources/templates/users/detail.peb
```

### Step 6: Verify in editor

- Open a `.peb` file — status bar should show "Pebble" not "Unknown"
- Verify syntax highlighting for `{{ }}`, `{% %}`, `{# #}` constructs
- Verify HTML content inside templates is highlighted via injection
- Verify bracket matching works for template delimiters

### Step 7: Document result

Update this ticket with the specific fix that resolved the language registration and any Zed version constraints.
