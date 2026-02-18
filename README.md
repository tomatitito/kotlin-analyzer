# kotlin-analyzer

A Kotlin language server for the [Zed](https://zed.dev) editor.

kotlin-analyzer provides IDE-like features for Kotlin development in Zed, including diagnostics, code completion, go-to-definition, hover information, and more. It is built as a **Rust frontend** paired with a **JVM sidecar** that runs the Kotlin compiler's Analysis API for semantic analysis.

## Architecture

The system consists of three components:

- **Zed extension** (Rust/WASM) — Registers Kotlin language support, manages tree-sitter queries for syntax highlighting, and downloads/launches the LSP binary.
- **Rust LSP server** — Handles the LSP protocol, document state management, and sidecar process lifecycle. Communicates with Zed via the standard LSP protocol.
- **JVM sidecar** — Runs the Kotlin Analysis API (K2/FIR-backed) using `kotlin-compiler-embeddable` for semantic analysis (diagnostics, completion, navigation). Communicates with the Rust server via JSON-RPC over stdin/stdout.

This split architecture provides instant startup (Rust binary starts in milliseconds while the JVM warms up in the background), crash isolation (a sidecar crash doesn't take down the LSP server), and a clean separation of concerns.

## Planned Features

**v1 targets single-module Gradle projects on macOS and Linux.**

- Syntax highlighting via tree-sitter
- Diagnostics (errors and warnings)
- Go-to-definition
- Hover with type information and KDoc
- Type-aware code completion with auto-import
- Signature help (parameter hints)
- Formatting via external tool (ktfmt or ktlint)
- Kotlin-specific support (Compose compiler plugin, KAPT, KSP)

See [plans/active/v1-roadmap.md](plans/active/v1-roadmap.md) for the full roadmap.

## Requirements

- **JDK 17** or later
- **Zed** editor

## Project Structure

```
kotlin-analyzer/
├── docs/           # Architecture, protocol, testing, and other design docs
├── plans/          # Development roadmap and completed plans
├── spike/          # Proof-of-concept validations (Analysis API standalone mode)
└── AGENTS.md       # Development guide and build commands
```

## Documentation

| Document | Description |
|----------|-------------|
| [Documentation Index](docs/INDEX.md) | Overview and links to all design documents |
| [Architecture](docs/architecture.md) | System design, module structure, process lifecycle |
| [Compiler Integration](docs/compiler-integration.md) | Analysis API usage, compiler flags, version strategy |
| [LSP Protocol](docs/lsp-protocol.md) | LSP framework choice, bridge protocol, backpressure |
| [Distribution](docs/distribution.md) | Cross-platform builds, packaging, CI/CD |
| [Testing](docs/testing.md) | Testing strategy, fixtures, CI pipeline |
| [v1 Roadmap](plans/active/v1-roadmap.md) | Milestone plan with task checklists |

## Status

This project is in active development and is **pre-release**. The core technical risk — whether the Kotlin Analysis API works standalone outside IntelliJ — has been [validated](spike/analysis-api/FINDINGS.md) and no blockers were found.

## License

Apache 2.0
