# Kotlin Extension for Zed — Research

## 1. Problem Statement

Build a Kotlin extension for the Zed editor. The extension includes:
1. **Tree-sitter grammar integration** — syntax highlighting, outline, brackets, indentation, injections
2. **Language server** — a custom LSP server (Rust frontend + JVM sidecar) for semantic features
3. **Zed extension glue** — Rust/WASM extension code to register the language, download/manage the LSP binary, and configure settings

---

## 2. Existing Kotlin Extension for Zed

### 2.1 zed-extensions/zed-kotlin (official community extension)

- **Repository**: https://github.com/zed-extensions/zed-kotlin
- **Authors**: evrsen, cholwell
- **Stats**: 131k downloads, 74 stars, 45 commits, 16 contributors
- **Language mix**: Rust 52.5%, Tree-sitter Query 47.5%

### 2.2 What it provides

**Tree-sitter queries** (using `fwcd/tree-sitter-kotlin`):
- `highlights.scm` — syntax highlighting
- `brackets.scm` — bracket matching
- `outline.scm` — code outline/structure
- `indents.scm` — auto-indentation
- `injections.scm` — embedded language support
- `overrides.scm` — scope-specific settings

**Language config** (`config.toml`):
- File extensions: `.kt`, `.kts`
- Line comments: `// `
- Block comments: `/* */`, `/** */`
- Auto-close brackets, quotes

**Two language server integrations**:
1. `kotlin-language-server` (fwcd) — downloads `server.zip` from GitHub releases
2. `kotlin-analyzerp` (JetBrains) — downloads platform-specific binary from JetBrains CDN

### 2.3 Extension architecture

```
extension.toml          — metadata, grammar ref, language server declarations
Cargo.toml              — Rust cdylib, depends on zed_extension_api 0.5.0
src/kotlin.rs           — Extension trait impl, dispatches to language server modules
src/language_servers/
  mod.rs                — Re-exports
  kotlin_language_server.rs  — Downloads/manages fwcd LSP
  kotlin_lsp.rs              — Downloads/manages JetBrains LSP
languages/kotlin/
  config.toml           — Language definition
  highlights.scm        — Syntax highlighting queries
  brackets.scm          — Bracket matching queries
  outline.scm           — Code outline queries
  indents.scm           — Auto-indent queries
  injections.scm        — Language injection queries
  overrides.scm         — Scope overrides
```

### 2.4 What's missing / what we can improve

- **Missing tree-sitter queries**: No `textobjects.scm` (vim-style text objects), no `runnables.scm` (run buttons for tests/main)
- **No custom LSP**: Relies on two external LSPs that both have issues — fwcd is deprecated, JetBrains is pre-alpha and heavyweight (bundles full JRE)
- **No compiler flag support**: Existing LSPs do not honor compiler flags from `build.gradle`. Kotlin features enabled via `-X` flags (e.g. `-Xcontext-parameters`) produce false-positive diagnostics — the `context` keyword (used e.g. with Arrow's `raise` DSL) shows error squiggles even when the project compiles fine. This is a concrete, current pain point.
- **No multi-dollar string interpolation support**: Kotlin 2.1's multi-dollar syntax (`$$"${app.kafka.consumer-topic}"`) is not handled correctly. This syntax is common in Spring Boot projects for referencing `application.yml` config values — the `$$` prefix makes `${...}` a literal string rather than Kotlin interpolation. Requires both correct tree-sitter highlighting and compiler-level support (flag `-Xmulti-dollar-interpolation` or Kotlin 2.1+ where it's stable).
- **No formatting integration**: No built-in ktfmt/ktlint support
- **Limited configuration**: Basic settings passthrough, no extension-specific settings UI

---

## 3. Our Extension Scope

### What we're building

| Component | Description | Language |
|-----------|-------------|----------|
| **Tree-sitter queries** | Improved/complete query files including textobjects and runnables | Tree-sitter Query |
| **Language server binary** | Custom lightweight LSP (Rust frontend + JVM sidecar) | Rust, Kotlin |
| **Zed extension** | Rust/WASM glue to register language, download LSP, configure settings | Rust |

### What we're NOT building

- A new tree-sitter grammar — we'll use `fwcd/tree-sitter-kotlin` (same as the existing extension)
- A standalone editor-agnostic LSP (though our LSP will speak standard LSP protocol)

---

## 4. Architecture Overview

```
┌─────────────────────────────────────────────┐
│                 Zed Editor                  │
│                                             │
│  tree-sitter-kotlin ──► highlighting        │
│  (grammar)              outline             │
│                         folding             │
│                         brackets            │
│                         indentation         │
│                         text objects         │
│                                             │
│  Rust/WASM extension ──► downloads LSP      │
│  (extension glue)        configures LSP     │
│                          registers language  │
│                                             │
│  LSP client ◄──────────► kotlin-analyzer (our LSP)│
│              JSON-RPC                       │
│              stdin/stdout                   │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│            kotlin-analyzer (Rust binary)          │
│                                             │
│  LSP protocol handler (tower-lsp)           │
│  Document state management                  │
│  Request debouncing/cancellation            │
│  JVM sidecar lifecycle management           │
│         │                                   │
│         │ JSON-RPC over stdin/stdout        │
│         ▼                                   │
│  ┌───────────────────────────────────────┐  │
│  │    JVM Sidecar (kotlin-compiler-      │  │
│  │    embeddable)                        │  │
│  │                                       │  │
│  │    Parsing, resolution, type checking │  │
│  │    Diagnostics, completion, hover     │  │
│  │    References, rename, code actions   │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

---

## 5. Component 1: Zed Extension (Rust/WASM)

### 5.1 How Zed extensions work

- Extensions are Rust crates compiled to WASM (`cdylib`)
- Depend on `zed_extension_api`
- Implement the `Extension` trait (primarily `language_server_command`)
- Declare grammars, language servers, and language config in `extension.toml`
- Published as Git repositories; Zed builds the WASM from source

### 5.2 What our extension does

1. **Registers Kotlin language** with tree-sitter grammar and query files
2. **Downloads our LSP binary** — fetches the `kotlin-analyzer` Rust binary from GitHub releases (platform-specific: darwin-arm64, darwin-amd64, linux-amd64, etc.)
3. **Starts the LSP** via `language_server_command` returning the binary path
4. **Passes configuration** via `language_server_workspace_configuration`
5. **(Optional)** Customizes completion labels via `label_for_completion`

### 5.3 Extension file structure

```
zed-kotlin/
├── extension.toml
├── Cargo.toml
├── src/
│   └── lib.rs              # Extension trait impl, LSP download/launch
├── languages/kotlin/
│   ├── config.toml          # Language definition
│   ├── highlights.scm       # Syntax highlighting
│   ├── brackets.scm         # Bracket matching
│   ├── outline.scm          # Code outline
│   ├── indents.scm          # Auto-indentation
│   ├── injections.scm       # Language injections
│   ├── overrides.scm        # Scope overrides
│   ├── textobjects.scm      # Vim text objects (NEW)
│   └── runnables.scm        # Run buttons for tests/main (NEW)
└── LICENSE
```

---

## 6. Component 2: Language Server (Rust + JVM)

### 6.1 Why Rust + JVM sidecar

- **Rust frontend**: Handles LSP protocol (via `tower-lsp`), document sync, request management, process lifecycle. Instant startup. ~5 MB memory. No GC pauses. Same language as the Zed extension — only two languages in the entire project (Rust + Kotlin).
- **JVM sidecar**: Runs `kotlin-compiler-embeddable` for actual Kotlin analysis. Persistent process. ~300 MB memory.
- **Process isolation**: Compiler crashes don't kill the LSP connection; Rust restarts the sidecar.
- **Request management**: Rust debounces, cancels, and prioritizes requests before forwarding to the JVM.

### 6.2 Rust ↔ JVM communication

- **Protocol**: JSON-RPC over stdin/stdout (same as LSP itself — one mental model, human-readable for debugging)
- **Decision rationale**: See section 10.1

### 6.3 LSP features (prioritized)

**Phase 1: Core**

| Feature | LSP Method | Difficulty |
|---------|-----------|------------|
| Diagnostics | `textDocument/publishDiagnostics` | Medium |
| Go to definition | `textDocument/definition` | Medium |
| Hover (type info, KDoc) | `textDocument/hover` | Medium |
| Completion | `textDocument/completion` | High |
| Signature help | `textDocument/signatureHelp` | Medium |
| Formatting | `textDocument/formatting` (shell to ktfmt) | Low |

**Phase 2: Navigation & Refactoring**

| Feature | LSP Method | Difficulty |
|---------|-----------|------------|
| Find references | `textDocument/references` | High |
| Rename | `textDocument/rename` | High |
| Code actions | `textDocument/codeAction` | High |
| Workspace symbols | `workspace/symbol` | Medium |
| Inlay hints | `textDocument/inlayHint` | Medium |

**Phase 3: Advanced**

| Feature | LSP Method | Difficulty |
|---------|-----------|------------|
| Semantic tokens | `textDocument/semanticTokens` | Medium |
| Call hierarchy | `textDocument/callHierarchy` | High |
| Type hierarchy | `textDocument/typeHierarchy` | High |
| Code lens | `textDocument/codeLens` | Medium |

### 6.4 Rust Libraries

| Library | Purpose | Status |
|---------|---------|--------|
| `tower-lsp` | LSP server framework | Mature, MIT, used by rust-analyzer, taplo, etc. |
| `lsp-types` | LSP 3.17 protocol types | Mature, MIT, de facto standard |
| `tokio` | Async runtime | Stable, industry standard |
| `serde` / `serde_json` | JSON serialization | Stable, industry standard |

### 6.5 JVM Sidecar

- **Artifact**: `org.jetbrains.kotlin:kotlin-compiler-embeddable`
- **Key classes**: `KotlinCoreEnvironment`, `AnalysisResult`, `BindingContext`, `DeclarationDescriptor`
- **Persistent process**: Maintains compiler environment across requests for incremental analysis
- **Classpath resolution**: Gradle Tooling API (primary), Maven `dependency:build-classpath`, manual `.kotlin-analyzer.json` fallback
- **Compiler flag extraction**: Must parse `freeCompilerArgs` / `kotlinOptions` from `build.gradle(.kts)` and apply them when constructing the `KotlinCoreEnvironment`. This includes experimental flags like `-Xcontext-parameters`, `-Xcontext-receivers`, `-Xvalue-classes`, `-Xmulti-dollar-interpolation`, etc. Without this, any Kotlin feature gated behind a compiler flag will produce false-positive diagnostics. This is a hard requirement — existing LSPs fail here.

### 6.6 LSP binary project structure

```
kotlin-analyzer/
├── Cargo.toml
├── src/
│   ├── main.rs                 # Entry point
│   ├── server.rs               # LSP method implementations (tower-lsp Backend)
│   ├── bridge.rs               # JVM sidecar JSON-RPC client + lifecycle
│   ├── project.rs              # Gradle/Maven classpath resolution
│   ├── state.rs                # Document state store
│   └── jsonrpc.rs              # JSON-RPC transport to sidecar
├── sidecar/                    # Kotlin/JVM analysis server
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       └── org/kotlinls/
│           ├── Main.kt
│           ├── AnalysisServer.kt
│           └── CompilerBridge.kt
```

---

## 7. Component 3: Tree-sitter Queries

### 7.1 Grammar

Using `fwcd/tree-sitter-kotlin` — the de facto standard Kotlin tree-sitter grammar, already used by the existing Zed extension, Neovim, Helix, and others.

**Kotlin 2.x syntax coverage**: Verify that `fwcd/tree-sitter-kotlin` correctly parses newer Kotlin syntax, in particular multi-dollar string interpolation (`$$"${...}"`), context parameters, and other 2.x additions. If the grammar lacks support, we may need to contribute upstream or maintain a patched fork.

### 7.2 Queries to write/improve

| File | Status in existing extension | Our plan |
|------|------------------------------|----------|
| `highlights.scm` | Exists | Review and improve coverage |
| `brackets.scm` | Exists | Keep as-is |
| `outline.scm` | Exists | Review — ensure classes, functions, properties, objects, enums all appear |
| `indents.scm` | Exists | Review |
| `injections.scm` | Exists | Keep as-is |
| `overrides.scm` | Exists | Keep as-is |
| `textobjects.scm` | **Missing** | Write — function.around/inside, class.around/inside, comment.around, parameter.inside |
| `runnables.scm` | **Missing** | Write — detect `fun main()` and `@Test` functions for run buttons |

---

## 8. Existing LSP Landscape

### 8.1 fwcd/kotlin-language-server (DEPRECATED)

- Written in Kotlin, embeds compiler via internal APIs
- Features: completion, hover, go-to-def, references, symbols, diagnostics
- Deprecated — JetBrains now has an official server
- Lesson: compiler internals work but break across Kotlin versions

### 8.2 JetBrains/kotlin-analyzerp (OFFICIAL, PRE-ALPHA)

- Written in Kotlin, built on IntelliJ IDEA codebase
- Pre-alpha (created April 2025, latest release Dec 2025)
- Features: highlighting, navigation, completion, diagnostics, rename, code actions, hover
- Bundles full JRE, 1-2 GB memory, "no stability guarantees"
- Lesson: even JetBrains reuses IntelliJ internals

### 8.3 Why build our own?

| | JetBrains LSP | fwcd LSP | Our LSP |
|---|---|---|---|
| Status | Pre-alpha | Deprecated | New |
| Runtime | IntelliJ platform + JRE | JVM | Rust + JVM sidecar |
| Memory | 1-2 GB+ | ~500 MB | ~300 MB |
| Startup | 10-30s | 5-15s | Rust instant, JVM background |
| Crash resilience | Process dies | Process dies | Rust survives, restarts sidecar |
| Zed integration | Generic | Generic | Zed-first |

---

## 9. Kotlin Compiler Reference

- **Repository**: https://github.com/JetBrains/kotlin
- **Spec + grammar**: https://github.com/Kotlin/kotlin-spec (ANTLR4 grammar in `grammar/`)

| Directory | Purpose |
|---|---|
| `compiler/frontend/` | Old frontend (parsing, semantic analysis) |
| `compiler/fir/` | FIR — new frontend (Frontend Intermediate Representation) |
| `compiler/ir/` | Backend IR |
| `compiler/cli/` | Command-line interface |
| `compiler/daemon/` | Persistent compiler daemon |
| `analysis/` | Analysis APIs |
| `idea/` | IntelliJ Kotlin plugin (reference for IDE features) |

---

## 10. Decided Questions

### 10.1 Rust ↔ JVM bridge: JSON-RPC over stdin/stdout

- **JSON-RPC**: Same protocol as LSP itself. One mental model. Human-readable wire format for debugging. No code generation step. Lightweight dependencies.
- **gRPC**: Typed contracts, streaming, binary serialization. But: heavy setup (protoc, plugins), HTTP/2 overkill for local IPC, binary format hard to debug, heavy dependencies (Netty on JVM).
- **Decision**: JSON-RPC over stdin/stdout. The sidecar is local, message volumes are modest, debuggability matters early on. Can migrate to protobuf later if needed.

### 10.2 LSP frontend language: Rust over Go

- **Rust**: Same language as the Zed extension (only 2 languages in the project). Mature LSP ecosystem (`tower-lsp`, `lsp-types`). No GC, lower memory than Go. Potential to share types/code between extension and LSP.
- **Go**: Would add a third language to the project. No meaningful advantage over Rust for this use case — both have instant startup, low memory, and good concurrency. Go's LSP ecosystem is less mature (no equivalent to `tower-lsp`).
- **Decision**: Rust. Reduces project language count from 3 to 2, leverages battle-tested `tower-lsp` framework, and aligns with the Zed ecosystem.

### 10.3 Compiler API: use compiler internals directly

- No public stable Kotlin Analysis API exists for standalone (non-IntelliJ) use.
- **Decision**: Use `kotlin-compiler-embeddable` internal APIs directly. Abstract behind our own stable interface layer so we can swap to a public API if JetBrains ships one.

### 10.4 Incremental analysis: file-level dependency graph

- **File-level**: Track which files import/reference which other files. When file A changes, re-analyze A and all transitive dependents. Simple to build from compiler resolution data. May over-analyze (changing a private function re-analyzes all dependents), but pragmatic for v1.
- **Declaration-level**: Track at function/class granularity. More precise, significantly more complex. Overkill until performance demands it.
- **Decision**: File-level dependency graph, re-analyze transitive dependents on save. Declaration-level granularity as a future optimization.

### 10.5 Fork vs fresh start: fork existing extension

- The existing `zed-extensions/zed-kotlin` extension has solid tree-sitter queries and a working extension scaffold.
- **Decision**: Fork `zed-extensions/zed-kotlin`. Replace the language server integration with our own. Improve and extend tree-sitter queries (add `textobjects.scm`, `runnables.scm`).

### 10.6 Distribution: extension downloads binary

- **Decision**: The Zed extension downloads the `kotlin-analyzer` Rust binary (with bundled sidecar JAR) from GitHub releases. One platform-specific archive per target (darwin-arm64, darwin-amd64, linux-amd64, etc.).

---

### 10.7 Multi-module Gradle projects: phased approach

**v1 — single-environment with compiled dependency JARs:**
- Treat the root/active module's sources as the primary source roots.
- Add compiled classes of dependency modules (from Gradle's `build/classes/` output) to the classpath.
- Cross-module go-to-definition lands on decompiled stubs (not source).
- No module visibility enforcement (`api` vs `implementation` scoping is ignored).
- Pragmatic: works for most navigation and diagnostics without modeling module boundaries.

**v2 — full module-aware analysis (target architecture):**

1. **Project model from Gradle Tooling API** — extract the complete module dependency DAG, dependency scoping (`api` vs `implementation` vs `compileOnly`), source roots per module, classpath per module, and compiler flags per module.
2. **Per-module `KotlinCoreEnvironment`** — each module gets its own compiler environment configured with its own source roots, compiled classes (or ABI stubs) of dependency modules, correctly scoped external JARs, and its own compiler flags. The Rust frontend routes requests based on a file-path → module mapping built from source roots.
3. **Cross-module source navigation** — when go-to-def resolves to a class in a dependency module's compiled JAR, map the FQN back to the source file via a per-module FQN → source file index.
4. **ABI-aware incremental re-analysis** — when a file in `:core` changes: re-analyze `:core`, diff its public ABI (declarations, signatures, supertypes) against the previous snapshot. If the ABI didn't change, stop. If it changed, re-analyze direct dependents in topological order.
5. **Dependency scope enforcement** — `internal` symbols produce diagnostics when used across modules; `implementation` dependencies of `:core` are invisible to `:app`'s completion.

**Hard parts of v2:** ABI diffing is non-trivial (need to serialize/compare the public API surface). Memory scales with module count (~300 MB × N environments; mitigate with lazy loading for modules without open files). Must analyze in topological order on changes.

- **Decision**: Ship v1 (single environment + compiled JARs). Design the sidecar interface so that v2 can be added without breaking changes.

---

### 10.8 Target platforms: JVM only (Kotlin/JS and Kotlin/Native deferred)

- **Kotlin/JS** compiles Kotlin to JavaScript (browser/Node.js). **Kotlin/Native** compiles to native machine code via LLVM (iOS, macOS, Linux, etc.). Both are part of **Kotlin Multiplatform (KMP)**, which uses `expect`/`actual` declarations and multiple source sets (`commonMain`, `jvmMain`, `jsMain`, `nativeMain`).
- Supporting JS/Native would require handling different target configurations, different standard libraries, multiple source sets with different visibility rules, and `expect`/`actual` resolution. This roughly doubles project model complexity.
- **Decision**: JVM only for now. KMP/JS/Native support is a future feature. Don't actively break on KMP projects — files in `commonMain` are mostly valid Kotlin and should get partial analysis.

---

## 12. Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Kotlin compiler internal APIs break | Sidecar breaks on Kotlin updates | Pin compiler version; abstract behind stable interface |
| JVM sidecar startup latency | No semantic features until JVM ready | Start sidecar eagerly; queue requests; show "initializing" status |
| Classpath resolution complexity | Can't resolve symbols without deps | Start with simple Gradle projects; manual config fallback |
| Sidecar crashes | Temporary loss of semantic features | Rust auto-restarts sidecar with backoff; Zed shows degraded state |
| Zed extension API changes | Extension breaks on Zed updates | Pin `zed_extension_api` version; follow Zed release notes |
| JetBrains LSP matures | Our project becomes redundant | Differentiate on lightweight footprint and crash resilience |

---

## 13. Implementation Roadmap

### Milestone 1: Extension Skeleton
- [ ] Zed extension scaffold (`extension.toml`, `Cargo.toml`, `src/lib.rs`)
- [ ] Language registration with tree-sitter-kotlin grammar
- [ ] Tree-sitter queries (port/improve from existing extension)
- [ ] Write `textobjects.scm` and `runnables.scm`
- [ ] Verify syntax highlighting, outline, indentation work in Zed

### Milestone 2: LSP Walking Skeleton
- [ ] Rust project scaffold (`Cargo.toml`, `src/main.rs`)
- [ ] `tower-lsp` server with initialize/shutdown lifecycle
- [ ] Document open/close/change synchronization
- [ ] Extension downloads and launches the Rust binary

### Milestone 3: JVM Sidecar
- [ ] Kotlin sidecar with `kotlin-compiler-embeddable`
- [ ] JSON-RPC over stdin/stdout (Rust ↔ JVM)
- [ ] Sidecar lifecycle management (start, restart, health check)
- [ ] Persistent `KotlinCoreEnvironment`

### Milestone 4: Project Model
- [ ] Gradle project detection and classpath extraction
- [ ] Extract compiler flags (`freeCompilerArgs`, `kotlinOptions`) from `build.gradle(.kts)` and pass to `KotlinCoreEnvironment`
- [ ] Maven project detection
- [ ] File watching for build file changes

### Milestone 5: Core Semantic Features
- [ ] Diagnostics (compile errors, warnings)
- [ ] Go-to-definition
- [ ] Hover (type info, KDoc)
- [ ] Completion (type-aware, auto-imports)
- [ ] Signature help
- [ ] Formatting (shell to ktfmt/ktlint)

### Milestone 6: Navigation & Refactoring
- [ ] Find references
- [ ] Rename symbol
- [ ] Code actions / quickfixes
- [ ] Workspace symbols
- [ ] Inlay hints

### Milestone 7: Polish & Release
- [ ] Semantic tokens
- [ ] Performance optimization (incremental analysis, debouncing)
- [ ] CI/CD: build Rust binary for all platforms, package extension
- [ ] Publish to Zed extension registry
