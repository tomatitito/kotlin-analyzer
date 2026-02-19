# AGENTS.md

## Documentation

Read `docs/INDEX.md` for the full documentation index. Key references:

| Document | When to read |
|----------|-------------|
| [docs/INDEX.md](docs/INDEX.md) | Start here — project overview and doc index |
| [docs/architecture.md](docs/architecture.md) | Rust + JVM sidecar design, module layout, lifecycle |
| [docs/compiler-integration.md](docs/compiler-integration.md) | Analysis API, compiler flags, incremental analysis |
| [docs/lsp-protocol.md](docs/lsp-protocol.md) | LSP framework choice, bridge protocol, request handling |
| [docs/testing.md](docs/testing.md) | Test strategy, fixture format, CI pipeline |
| [docs/distribution.md](docs/distribution.md) | Cross-platform builds, packaging, release process |
| [plans/active/v1-roadmap.md](plans/active/v1-roadmap.md) | Current roadmap with task checklists |

## Git

Use the following git config for commits:

```
user.email=jens.kouros@posteo.de
user.name=Jens Kouros
user.signingkey=/Users/dusty/.ssh/id_ed25519_personal.pub
gpg.format=ssh
commit.gpgsign=true
```

## Architecture Constraints

### Dependency direction

Dependencies in the Rust LSP frontend flow in one direction, top to bottom:

```
main
 └─ server
      ├─ bridge
      │    └─ jsonrpc
      ├─ state
      ├─ project
      └─ config
error (cross-cutting)
```

No module may depend on a module above it. In particular, `bridge` must not depend on `state` or `project`. When the sidecar restarts and needs replay data (open documents, classpath, compiler flags), `server` orchestrates the replay by pulling from `state` and `project` and pushing the data down into `bridge`.

## GitHub CLI (`gh`)

Use `gh` for all interactions with the remote GitHub repository. Do not use raw `git push`, `curl` to the GitHub API, or the web UI for operations that `gh` supports.

### Common operations

```bash
gh pr create --title "..." --body "..."    # open a pull request
gh pr list                                 # list open PRs
gh pr view 42                              # view PR details
gh pr merge 42                             # merge a PR

gh release create v0.1.0 *.tar.gz          # create a release with assets
gh release list                            # list releases
gh release view v0.1.0                     # view release details

gh issue create --title "..." --body "..."  # file an issue
gh issue list                              # list open issues
gh issue view 17                           # view issue details

gh run list                                # list CI workflow runs
gh run view <run-id>                       # view a specific run
gh run watch <run-id>                      # live-tail a running workflow

gh repo view                               # show repo metadata
gh api repos/{owner}/{repo}/...            # arbitrary GitHub API calls
```

### When to use

- Creating and managing pull requests, issues, and releases
- Checking CI status and workflow run logs
- Uploading release artifacts
- Any GitHub API interaction (prefer `gh api` over raw `curl`)

## Verification Commands

### Rust LSP binary

```bash
cargo build                                # compile
cargo test                                 # unit tests
cargo test --features integration          # real sidecar integration tests
cargo run -- --log-level debug             # run the server on stdin/stdout
```

### JVM sidecar

```bash
cd sidecar && ./gradlew shadowJar          # build fat JAR
cd sidecar && ./gradlew test               # unit tests
```

### Zed extension

```bash
cargo build --target wasm32-wasip1         # build WASM extension

# symlink the extension into Zed's local extensions directory (macOS)
ln -sfn "$(pwd)" ~/Library/Application\ Support/Zed/extensions/installed/kotlin-analyzer
```

After symlinking, rebuild and restart Zed to pick up changes. The symlink only needs to be created once.

### Tree-sitter queries

```bash
tree-sitter test                           # run query tests
tree-sitter highlight tests/fixtures/kotlin/correct/BasicConstructs.kt
```

### Full smoke test

```bash
cargo build && (cd sidecar && ./gradlew shadowJar) && cargo test
```

## Zed CLI

`zed` can be invoked from the command line to open files, directories, and diffs.

```
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
