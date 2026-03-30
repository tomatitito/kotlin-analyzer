# Common Workflows

## Peekaboo (macOS UI Automation)

`peekaboo` is a CLI tool for controlling macOS applications. Use it to launch Zed, interact with its UI, and verify that the kotlin-analyzer extension behaves correctly during development.

### Common workflows

```bash
# Launch Zed and open a Kotlin project
peekaboo app --action launch --name Zed
peekaboo open /path/to/kotlin/project

# Capture a screenshot to inspect current UI state
peekaboo see --app Zed --annotate

# Interact with UI elements (use element IDs from `see` output)
peekaboo click --on <element-id>
peekaboo type "some text"
peekaboo hotkey cmd+shift+p          # open command palette

# List windows and check app state
peekaboo list
```

### When to use

- Verify LSP features (diagnostics, completions, hover) render correctly in Zed after building and installing the extension.
- Debug UI-level issues by capturing annotated screenshots with `peekaboo see --annotate`.
- Automate repetitive editor interactions during testing (open files, trigger completions, navigate diagnostics).

### Reference

Run `peekaboo --help` for all commands or `peekaboo learn` for the full AI agent guide.
