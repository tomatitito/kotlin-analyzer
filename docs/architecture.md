# System Architecture

## 1. High-Level Architecture

kotlin-analyzer is a Kotlin language server built as a Rust frontend with a JVM sidecar. The
Rust process owns the LSP connection, document state, and request lifecycle. The JVM
process runs the Kotlin compiler for semantic analysis. The two communicate over
JSON-RPC on stdin/stdout.

```
┌──────────────────────────────────────────────────────────────┐
│                        Zed Editor                            │
│                                                              │
│  tree-sitter-kotlin ──► syntax highlighting, outline,        │
│                         folding, brackets, indentation,      │
│                         text objects, runnables               │
│                                                              │
│  Rust/WASM extension ──► downloads kotlin-analyzer binary          │
│                          configures LSP, registers language   │
│                                                              │
│  LSP client ◄───────────► kotlin-analyzer                          │
│               JSON-RPC     (Rust binary)                     │
│               stdin/stdout                                   │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                   kotlin-analyzer (Rust process)                    │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  LSP Server (tower-lsp, vendored)                      │  │
│  │  - Handles initialize/shutdown lifecycle               │  │
│  │  - Dispatches textDocument/* requests                  │  │
│  └──────────────────────┬─────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴─────────────────────────────────┐  │
│  │  Request Manager                                       │  │
│  │  - Document state store (full text, version tracking)  │  │
│  │  - Debouncing (didChange → analyze)                    │  │
│  │  - Cancellation ($/cancelRequest forwarding)           │  │
│  │  - Backpressure (bounded channel, supersession)        │  │
│  └──────────────────────┬─────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴─────────────────────────────────┐  │
│  │  Bridge (bridge.rs)                                    │  │
│  │  - JVM process spawn + lifecycle (start/restart/kill)  │  │
│  │  - JSON-RPC framing over stdin/stdout                  │  │
│  │  - Health checks (ping, EOF detection)                 │  │
│  │  - State machine: Starting → Ready → Degraded          │  │
│  └──────────────────────┬─────────────────────────────────┘  │
│                         │ JSON-RPC over stdin/stdout         │
│                         ▼                                    │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  JVM Sidecar (kotlin-compiler-embeddable)              │  │
│  │                                                        │  │
│  │  - KotlinCoreEnvironment (persistent)                  │  │
│  │  - Parsing, name resolution, type checking             │  │
│  │  - Diagnostics, completion, hover, go-to-def           │  │
│  │  - References, formatting (ktfmt)                      │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

## 2. Why Rust + JVM Sidecar

The architecture splits responsibilities between a Rust frontend and a JVM backend for
four reasons:

**Instant startup.** The Rust binary starts in milliseconds. The editor gets an LSP
connection immediately. Semantic features (diagnostics, completion, hover) arrive once
the JVM sidecar finishes initializing. During that window the server returns partial
results rather than blocking.

**Crash isolation.** The Kotlin compiler uses internal APIs that may crash on
unexpected input. When the sidecar crashes, the Rust process stays alive, the LSP
connection remains open, and the sidecar restarts automatically. The editor never sees
a disconnection.

**Lower memory baseline.** The Rust process uses approximately 5 MB. The JVM sidecar
uses approximately 300 MB. Together this is significantly less than a monolithic
IntelliJ-based server (1-2 GB). Memory stays constant when the sidecar is idle.

**Language count.** The Zed extension is also Rust (compiled to WASM). Using Rust for
the LSP frontend keeps the project at two languages total: Rust and Kotlin. This
simplifies the build, CI, and contributor onboarding.

## 3. Process Lifecycle

### 3.1 Sidecar State Machine

The bridge tracks the JVM sidecar through four states:

```
Starting ──► Ready ──► Degraded ──► Restarting ──► Starting
                 │                                    ▲
                 └────────────────────────────────────┘
                        (crash / hang detected)
```

| State | Behavior |
|-------|----------|
| `Starting` | JVM process spawned, waiting for `initialize` response. Semantic requests are queued. Syntactic features (from tree-sitter) remain available. |
| `Ready` | Sidecar responded to `initialize`. All requests forwarded normally. |
| `Degraded` | Health check failed or abnormal behavior detected. Requests still forwarded but the server logs warnings and prepares for restart. |
| `Restarting` | Sidecar is being stopped and re-spawned. Semantic requests are queued (same as `Starting`). |

The sidecar is started during the LSP `initialize` handler. The `initialize` response
is returned to the editor immediately without waiting for the JVM to become ready.

### 3.2 Graceful Shutdown

1. Rust receives the LSP `shutdown` request from the editor.
2. Rust sends a `shutdown` JSON-RPC request to the sidecar with a 5-second timeout.
3. If the sidecar does not respond within the timeout: send `SIGTERM`, wait 2 seconds, then send `SIGKILL`.
4. Rust responds to the LSP `shutdown` request.
5. Rust exits when it receives the LSP `exit` notification.

### 3.3 Crash Recovery

When the sidecar crashes (detected via stdout EOF or a failed health check):

1. Log the crash event at `ERROR` level.
2. Transition to `Restarting` state.
3. Spawn a new JVM process with exponential backoff (1s, 2s, 4s, 8s, capped at 30s).
4. On successful restart, replay:
   - Project configuration (classpath, compiler flags, JDK home).
   - All currently open document contents (full text).
5. Transition back to `Starting`, then `Ready` once the sidecar responds.

The Rust process is the single source of truth for document state. This is a design
invariant. The JVM sidecar can be killed and restarted at any time without data loss.

### 3.4 Health Checks

- **Heartbeat**: Send a `ping` JSON-RPC request every 30 seconds. If no `pong` response
  arrives within 10 seconds, mark the sidecar as `Degraded`.
- **EOF detection**: Monitor the sidecar's stdout for EOF. An EOF means the process has
  exited. This provides immediate crash detection without waiting for the next heartbeat.
- **Hung detection**: If a `Degraded` sidecar does not recover after one additional
  heartbeat cycle, attempt graceful shutdown (SIGTERM + 2s + SIGKILL) and restart.

### 3.5 Orphan Prevention

The bridge implements `Drop` to kill the child JVM process. A shutdown handler is also
registered to cover abnormal Rust process termination. This prevents orphaned JVM
processes from accumulating.

## 4. Rust Project Structure

```
kotlin-analyzer/
├── Cargo.toml
├── src/
│   ├── main.rs              # Entry point, arg parsing, tracing setup
│   ├── server.rs            # LSP LanguageServer trait impl (tower-lsp)
│   ├── bridge.rs            # JVM sidecar JSON-RPC client + lifecycle
│   ├── project.rs           # Gradle/Maven classpath resolution
│   ├── state.rs             # Document state store (full text sync)
│   ├── jsonrpc.rs           # JSON-RPC transport (framing, serialization)
│   ├── config.rs            # Configuration schema + defaults
│   └── error.rs             # Error types (thiserror)
├── sidecar/
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       └── org/kotlinls/
│           ├── Main.kt
│           ├── AnalysisServer.kt
│           └── CompilerBridge.kt
├── docs/
│   ├── architecture.md
│   └── lsp-protocol.md
```

| Module | Responsibility |
|--------|---------------|
| `main.rs` | Parses CLI arguments (`--log-file`, `--log-level`), initializes `tracing`, starts the LSP server. Uses `anyhow` for top-level error handling. |
| `server.rs` | Implements the tower-lsp `LanguageServer` trait. Dispatches LSP requests to the bridge. Manages capability advertisement. |
| `bridge.rs` | Spawns and manages the JVM sidecar process. Implements the state machine (Starting/Ready/Degraded/Restarting). Sends JSON-RPC requests and matches responses by ID. Runs health checks. |
| `project.rs` | Detects Gradle/Maven projects. Extracts classpath, compiler flags, and JDK home via the Gradle Tooling API or Maven CLI. |
| `state.rs` | Stores the latest full text and version number for every open document. Serves as the source of truth for replay after sidecar restart. |
| `jsonrpc.rs` | JSON-RPC 2.0 framing: `Content-Length` header encoding/decoding, request/response/notification serialization. |
| `config.rs` | Defines the configuration schema with `serde` and `#[serde(default)]`. Handles `workspace/didChangeConfiguration`. |
| `error.rs` | Central error enum with `thiserror`: `BridgeError`, `ProtocolError`, `ProjectError`, `IoError`. |

### 4.1 Module Dependency Rules

Dependencies between internal modules flow in one direction. Lower-level modules must
never import higher-level modules. The allowed dependency graph is:

```
main.rs ──► server.rs ──► bridge.rs ──► jsonrpc.rs
                │              │
                ├──► state.rs  ├──► state.rs
                │              │
                ├──► config.rs ├──► config.rs
                │              │
                └──► error.rs  └──► error.rs

            project.rs ──► config.rs
                │
                └──► error.rs

            state.rs ──► error.rs

            jsonrpc.rs ──► error.rs

            config.rs ──► error.rs

            error.rs ──► (no internal deps)
```

Allowed `use crate::` imports per module:

| Module | May depend on |
|--------|---------------|
| `main.rs` | `server`, `config`, `error` |
| `server.rs` | `bridge`, `state`, `config`, `error` |
| `bridge.rs` | `jsonrpc`, `state`, `config`, `error` |
| `project.rs` | `config`, `error` |
| `state.rs` | `error` |
| `jsonrpc.rs` | `error` |
| `config.rs` | `error` |
| `error.rs` | (none) |

Prohibited patterns (examples):
- `bridge.rs` importing `server` — the bridge must not know about the LSP layer.
- `jsonrpc.rs` importing `bridge` — the framing layer must not know about the sidecar
  lifecycle.
- `error.rs` importing anything — the error module is a leaf dependency.
- `state.rs` importing `bridge` or `server` — the document store is passive data.

These rules are enforced by an architecture test (see `testing.md` section 7). The test
scans `use crate::` statements in each source file and fails if any import violates
the allowed dependency table.

## 5. Rust Dependencies

| Library | Purpose | Notes |
|---------|---------|-------|
| `tower-lsp` (vendored) | LSP server framework | Vendored from 0.20.0 as an internal workspace crate. Provides an async `LanguageServer` trait with native Tokio integration. See `lsp-protocol.md` section 1 for the full evaluation and rationale. |
| `lsp-types` | LSP 3.17 protocol types | De facto standard crate for LSP type definitions. |
| `tokio` | Async runtime | Used for bridge I/O, process management, timers, channels, and the debounce loop. |
| `serde` / `serde_json` | JSON serialization | Serialization for JSON-RPC messages and configuration. |
| `thiserror` | Domain error types | Derives `std::error::Error` for `BridgeError`, `ProtocolError`, `ProjectError`, `IoError`. |
| `tracing` + `tracing-subscriber` | Structured logging | Outputs to stderr (stdout is reserved for LSP transport). |

## 6. Error Handling Strategy

All modules use `thiserror` to define typed error enums. The central `Error` type in
`error.rs` has four variants:

```rust
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("bridge: {0}")]
    Bridge(#[from] BridgeError),

    #[error("protocol: {0}")]
    Protocol(#[from] ProtocolError),

    #[error("project: {0}")]
    Project(#[from] ProjectError),

    #[error("io: {0}")]
    Io(#[from] std::io::Error),
}
```

Rules:

- **No `unwrap()` or `expect()` in production code paths.** These are reserved for
  tests and provably-safe invariants (e.g., a regex literal).
- **Malformed JVM responses**: Log at `WARN`, return an LSP error response to the
  editor. Do not crash.
- **JVM stdout closes unexpectedly**: Trigger the crash recovery flow (section 3.3).
- **`main.rs`** uses `anyhow::Result` for top-level error reporting. All other modules
  use typed errors.

## 7. Logging and Tracing

The server uses the `tracing` crate with `tracing-subscriber` for structured logging.

**Output target**: stderr. Stdout is reserved exclusively for LSP JSON-RPC transport.
An optional `--log-file <path>` flag redirects log output to a file.

**Log levels**:

| Level | Content |
|-------|---------|
| `ERROR` | Sidecar crashes, protocol violations, unrecoverable failures. |
| `WARN` | Degraded states, malformed JVM responses, timeout events. |
| `INFO` | Lifecycle events: server start, sidecar ready, configuration loaded, shutdown. |
| `DEBUG` | Request/response summaries: method name, duration, document URI. |
| `TRACE` | Full JSON-RPC message bodies (for wire-level debugging). |

The default level is `INFO`. This can be overridden via the `--log-level` CLI flag or
the `kotlin.trace.server` configuration setting (`"off"`, `"messages"`, `"verbose"`).
