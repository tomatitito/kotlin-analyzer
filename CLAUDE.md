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
| [docs/development-commands.md](docs/development-commands.md) | Build, test, smoke-test, release-sync, and local Zed extension setup commands |
| [docs/github-cli.md](docs/github-cli.md) | Preferred `gh` commands for PRs, issues, releases, CI, and repository automation |
| [docs/zed-cli.md](docs/zed-cli.md) | `zed`/`my-zed` command-line usage for editor and LS debugging |
| [docs/common-workflows.md](docs/common-workflows.md) | Peekaboo-driven UI verification workflows |
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

Use normal Git commands for branch sync and commit transport, including `git push`, `git fetch`, and `git pull`.

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

Use `gh` for GitHub platform operations like PRs, issues, releases, workflow runs, and repo metadata. Use `git push` for pushing commits and branches. See [docs/github-cli.md](docs/github-cli.md) for the command reference and usage guidance.

## Verification Commands

See [docs/development-commands.md](docs/development-commands.md) for build, test, smoke-test, release-sync, and local Zed extension setup commands.

## Zed CLI

See [docs/zed-cli.md](docs/zed-cli.md) for `zed`/`my-zed` command-line usage.

## Common Workflows

See [docs/common-workflows.md](docs/common-workflows.md) for Peekaboo-based Zed verification flows.
