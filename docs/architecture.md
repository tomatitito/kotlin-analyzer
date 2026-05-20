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

## 8. Interactive vs Background Request Scheduling

The Kotlin Analysis API requires single-threaded access — every `KaSession` call
must execute on the same thread. The JVM sidecar therefore runs a strictly serial
dispatch loop (`AnalysisServer.run()`), and parallelism is not available as a
performance lever. The lever the Rust LSP server pulls instead is **ordering**:
keep interactive requests (completion, hover, definition, references, code actions)
ahead of background work (debounced `analyze` for diagnostics, project-wide
analysis on open).

Two cooperating signals on `KotlinLanguageServer` make this possible. They are
the foundation that the supersession mechanisms in
[lsp-protocol.md §4.1](lsp-protocol.md#41-latest-wins-supersession-completion)
and [lsp-protocol.md §4.2](lsp-protocol.md#42-background-analyze-supersession)
build on top of.

### 8.1 The `interactive_epoch` sentinel

```rust
struct KotlinLanguageServer {
    interactive_epoch: Arc<AtomicU64>,   // monotonically increasing
    last_interactive_at: Arc<Mutex<Option<Instant>>>,
    // …
}
```

Every interactive request handler calls `note_interactive_request()` on entry,
which atomically increments `interactive_epoch` and writes the current `Instant`
into `last_interactive_at`. The bumps happen in the same async call so the two
signals stay consistent.

The numeric value of `interactive_epoch` carries no meaning. Only the **change**
between two snapshots matters. Background work captures the value when it begins
waiting and reads it again before doing the expensive thing — if the value
changed, an interactive request happened in between, and the background task
defers or aborts.

A monotonically increasing counter is used instead of a boolean because every
interactive request must be observable as a unique tick. With a boolean the
reset would itself race with new interactive requests; with a counter no reset
is needed. `Ordering::Relaxed` is sufficient — a stale read at worst causes one
extra background cycle that the next check cancels.

The signal has two consumers today:

1. **The debounced `analyze` loop** (`start_debounce_loop`) captures the epoch
   when a `didChange` schedules an analyze, then re-checks just before
   dispatch. If different → defer to the next cycle.
2. **The project-wide background analysis loop** captures the epoch at the
   start of a multi-file scan, re-checks between files, and aborts the whole
   scan if the user starts interacting partway through.

### 8.2 Post-interactive cooldown

The epoch check answers "did anyone interactive happen *during* my wait?" but
not "was there interactive activity *recently*?" — a completion that arrived
just before the debouncer's epoch snapshot leaves the epoch unchanged at
dispatch time, so an analyze fires immediately even though the user is clearly
still active.

The cooldown closes that hole. After the epoch check passes, the debouncer also
consults `last_interactive_at`:

```text
const POST_INTERACTIVE_QUIET_WINDOW: Duration = Duration::from_millis(500);
const MAX_DEFER: Duration = Duration::from_secs(5);
```

If `since_last_interactive < POST_INTERACTIVE_QUIET_WINDOW`, the loop sleeps for
the remaining window, then re-reads `interactive_epoch` once more. If the user
kept typing during the sleep, the epoch will have moved and the cycle defers as
normal. The `MAX_DEFER` ceiling caps the cumulative delay so non-stop typing
cannot starve diagnostics indefinitely; under sustained interaction analyze
still fires within ~5.3 s of the first activity.

```text
t=0     didChange (no epoch bump — only request handlers bump)
        schedule debouncer for t=300 with epoch_at_schedule=N

t=200   completion request
        epoch → N+1, last_interactive_at → 200

t=300   debouncer wakes; epoch N+1 ≠ N → DEFER (existing behaviour)
        reschedule with epoch_at_schedule = N+1

t=600   debouncer wakes; epoch matches N+1
        BUT since_last_interactive = 400 ms < 500 ms
        → sleep 100 ms, then re-check epoch

t=700   epoch still N+1 → fire analyze
        (user has been quiet 500 ms — likely an actual pause)
```

### 8.3 Bridge-level supersession

Even with the cooldown, an `analyze` can already be sitting in the bridge
writer channel by the time a completion request arrives. The bridge layer
provides `Bridge::cancel_pending(bucket)` for this case: it bumps the
per-bucket supersession sequence so the writer task drops the queued message
before stdin. The completion handler invokes
`cancel_pending("analyze:{uri}")` immediately after `note_interactive_request()`
to flush any analyze still pending for the same file.

This is the same machinery completion supersession uses (introduced in
ka-rr3u), generalised to be invokable explicitly from anywhere in the server.
Disjoint bucket namespaces (`"<uri>"` for completion, `"analyze:<uri>"` for
analyze) keep the two supersession streams from interfering. See
[lsp-protocol.md §4.1](lsp-protocol.md#41-latest-wins-supersession-completion)
and [§4.2](lsp-protocol.md#42-background-analyze-supersession) for the
wire-level details and the observability surface
(`analyze_pending_cancelled_total` on the per-completion metrics line).

A second supersession checkpoint runs inside the sidecar's `AnalysisServer`
dispatch loop (introduced in ser-leba) — it short-circuits stale completions
already past Rust's writer-side `BeforeSend` check before they reach the
Analysis API. The sidecar uses the same `"<uri>"` bucket convention so the
checkpoints compose cleanly. See
[lsp-protocol.md §4.1, Checkpoint 2](lsp-protocol.md#41-latest-wins-supersession-completion)
for the rationale and the new `metrics.counts.supersededAtDispatch` counter.

The same Checkpoint 2 also runs cross-request-type (ka-p741): when a completion
is observed at dispatch, any queued analyze (any URI) is short-circuited with a
synthetic empty-diagnostics response and counted under
`metrics.counts.analyzeSupersededByCompletion`. See
[lsp-protocol.md §4.1, Checkpoint 2](lsp-protocol.md#41-latest-wins-supersession-completion).

### 8.4 What is deliberately not addressed

Mid-flight cancellation of an `analyze` already running inside the sidecar is
out of scope. The Kotlin Analysis API exposes no cooperative cancellation
hooks inside `collectDiagnostics`, and hard-killing the analysis thread would
corrupt the session. If the JetBrains team adds checkpoint hooks to the K2
Analysis API in a future release, this becomes feasible; until then the
two-layer Rust-side defence (cooldown + queue-level supersession) is the
ceiling of what we can do without sidecar architectural changes.
