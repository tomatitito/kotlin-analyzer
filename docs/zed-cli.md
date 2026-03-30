# Zed CLI

`zed` can be invoked from the command line to open files, directories, and diffs.

```bash
zed [OPTIONS] [PATHS]...
```

| Flag | Description |
|------|-------------|
| `-w, --wait` | Wait for all opened files to be closed before the CLI exits |
| `-n, --new` | Open paths in a new workspace window |
| `-a, --add` | Add paths to the currently focused workspace |
| `-r, --reuse` | Reuse an existing window, replacing its current workspace |
| `--diff <OLD> <NEW>` | Open a diff view comparing two files |
| `--foreground` | Run Zed in the foreground, keeping the terminal attached |

Files can be opened at a specific position with `zed file.txt:line:column`. Pass `-` as the path to read from stdin.

## `my-zed` — Development Build

For kotlin-analyzer development, use `my-zed` instead of `zed`. This is a custom Zed build that prints language server logs and JSON-RPC messages to the console, making it much easier to debug LSP interactions with the sidecar.

```bash
my-zed /path/to/kotlin/project    # opens project and streams LSP logs to the terminal
```

Always prefer `my-zed` when:
- Debugging diagnostics, completions, hover, or go-to-definition behavior
- Verifying end-to-end LSP communication (initialize, `textDocument/*`, shutdown)
- Investigating sidecar startup, crash recovery, or classpath issues
- Performing the visual verification tasks in the v1 roadmap
