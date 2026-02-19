# Changelog

All notable changes to kotlin-analyzer will be documented in this file.

## [0.1.0] - 2026-02-19

Initial release.

### Added

- Zed extension with tree-sitter Kotlin grammar (`fwcd/tree-sitter-kotlin`)
- Tree-sitter queries: highlights, outline, indents, brackets, injections, text objects, runnables
- Rust LSP server with tower-lsp 0.20
- JVM sidecar using Kotlin Analysis API 2.1.20 (standalone, FIR-backed)
- JSON-RPC bridge between Rust server and JVM sidecar
- Diagnostics (errors and warnings) with real-time publish
- Hover with type information and KDoc documentation
- Go-to-definition for local variables, functions, and classes
- Type-aware code completion (member and scope completions)
- Signature help with active parameter highlighting
- File-local find references
- Formatting via ktfmt or ktlint (external tool shell-out)
- Gradle project model extraction (classpath, compiler flags, source roots)
- Maven project model extraction
- Manual project configuration via `.kotlin-analyzer.json`
- Stdlib-only fallback for projects without build systems
- Sidecar health check with automatic restart on crash
- Debounced document analysis (300ms)
- Cross-platform support: macOS (ARM/x64), Linux (x64/ARM)
- GitHub Actions CI with tag-triggered releases
- Apache 2.0 license
