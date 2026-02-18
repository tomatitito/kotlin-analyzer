# kotlin-analyzer Documentation

A custom Kotlin language server for the Zed editor, built as a Rust frontend with a JVM sidecar running the Kotlin Analysis API (FIR-backed). The Rust binary handles LSP protocol, document state, and process lifecycle; the JVM sidecar performs semantic analysis using `kotlin-compiler-embeddable`. The two communicate via JSON-RPC over stdin/stdout.

The project also includes a Zed extension (Rust/WASM) that registers Kotlin language support, manages tree-sitter queries, and downloads/launches the LSP binary.

## Reference Docs

| Document | Description |
|----------|-------------|
| [Architecture](architecture.md) | Rust + JVM sidecar design, process lifecycle, state machine, shutdown/restart, error handling, logging |
| [Compiler Integration](compiler-integration.md) | Analysis API (K2/FIR), compiler flag extraction, incremental analysis, Compose/KAPT/KSP, Java interop, memory estimates |
| [LSP Protocol](lsp-protocol.md) | LSP framework choice, feature phases, bridge protocol schema, backpressure, cancellation, document sync, configuration |
| [Distribution](distribution.md) | Cross-platform builds, JVM requirement, JAR bundling, Zed extension packaging, CI/CD pipeline |
| [Testing](testing.md) | Unit, integration, end-to-end, and conformance testing strategy, test fixtures, CI pipeline |
| [Competitive Landscape](competitive-landscape.md) | JetBrains kotlin-lsp, fwcd LSP, positioning, differentiators, exit strategy |
| [Risks](risks.md) | Risk register with likelihood, impact, mitigations, and milestone-based review schedule |

## Spikes

| Spike | Result | Description |
|-------|--------|-------------|
| [Analysis API Standalone](../spike/analysis-api/FINDINGS.md) | **Go** | Validated that `StandaloneAnalysisAPISession` works outside IntelliJ. Diagnostics, compiler flags (`-Xcontext-parameters`), and JDK/stdlib classpath all functional. ~1.1s cold start, ~97 MB memory for small files. No blockers found. |

## Plans

| Plan | Status | Description |
|------|--------|-------------|
| [v1 Roadmap](../plans/active/v1-roadmap.md) | **Active** | 4-milestone plan: extension + tree-sitter, LSP + sidecar + diagnostics, core semantic features, polish + release |
| [Original Research](../plans/completed/research.md) | Completed | Initial research and architecture exploration (superseded by docs above) |
