# LSP Protocol and Bridge Design

## 1. LSP Server Framework

**Decision**: Vendor `tower-lsp` 0.20.0 as an internal crate.

### 1.1 Evaluation Summary

| Dimension | `tower-lsp` 0.20.0 | `lsp-server` 0.7.9 |
|---|---|---|
| Model | Async (Tokio-native) | Synchronous (crossbeam-channel) |
| Maintenance | Unmaintained since Aug 2023; sole maintainer unresponsive | Actively maintained by the rust-analyzer team (2-4 releases/year) |
| API surface | High-level async `LanguageServer` trait (2 required methods, 61 optional) | Low-level message loop with manual `match` dispatch |
| Boilerplate | Minimal — implement trait, override methods | Substantial — rust-analyzer builds ~1000 lines of dispatch infrastructure on top |
| Concurrency | Built-in (up to 4 concurrent handlers) | DIY — full control, full responsibility |
| Cancellation | Implemented but has panic edge cases (issue #417) | DIY |
| LSP 3.17 | Full coverage via `lsp-types` 0.94 | Protocol-agnostic; pairs with any `lsp-types` version |
| Tokio integration | Native — handlers are async fns | Impedance mismatch — requires crossbeam↔tokio bridge layer |
| Codebase | ~2,945 lines across 15 files | ~769 lines across 6 files |
| Downloads | 3.7M total (crates.io) | 8M total (crates.io) |
| Notable users | taplo, dprint, sway-lsp, uiua, ~2,089 dependents | rust-analyzer, slint-lsp, cairo-lang-lsp, nu-lsp, ~many more |

### 1.2 Rationale

**Async alignment is the deciding factor.** kotlin-analyzer uses Tokio for the JVM bridge,
process management, debounce timers, backpressure channels, and health checks. The
entire architecture is async.

With `lsp-server`, every LSP handler would need to bridge from the synchronous
crossbeam event loop into the async Tokio runtime to communicate with the JVM sidecar.
This requires a dedicated OS thread for the crossbeam loop, translation code between
`crossbeam-channel` and `tokio::sync::mpsc`, and the loss of `tokio::select!` for
multiplexing LSP events with sidecar responses, timers, and channels. The result is
two channel ecosystems, more code, and more surface area for bugs — all to use a
framework whose main advantage (architectural freedom) is offset by the integration
cost.

With `tower-lsp`, handlers are async fns that can directly `.await` bridge calls to the
JVM sidecar. `tokio::select!` works naturally across LSP requests, sidecar responses,
debounce timers, and cancellation signals. One runtime, one mental model.

**Vendoring neutralizes the maintenance risk.** The upstream `tower-lsp` crate has been
unmaintained since August 2023, with 28 open issues and an unresponsive maintainer. A
community fork (`tower-lsp-server` 0.23.0) exists but uses its own `ls-types` crate
instead of the standard `lsp-types`, creating an ecosystem divergence we want to avoid.

At 2,945 lines, `tower-lsp` is small enough to vendor as a workspace crate. Once
vendored, we can:

- Remove the `async-trait` dependency (Rust now supports async traits natively).
- Update to `lsp-types` 0.97 and `tower` 0.5.
- Fix the concurrent handler state-drift issue (#284) by serializing handler execution,
  which aligns with our architecture — the JVM sidecar processes requests sequentially.
- Fix the cancellation panic (#417).
- Drop the `Send` requirement (#386) where unnecessary.

### 1.3 Known Issues to Fix After Vendoring

| Issue | Description | Fix |
|-------|-------------|-----|
| #284 | Concurrent handler execution causes state drift between server and client | Serialize handler execution per-direction; concurrent execution between client→server and server→client |
| #417 | `$/cancelRequest` can panic on future cancellation | Guard against double-cancellation in the router |
| #386 | `Send` bound on handlers forces `Arc<Mutex<>>` even when unnecessary | Relax bounds or make configurable |
| #431 | Uses `#[async_trait]` macro despite Rust having native async trait support | Replace with native async traits, remove dependency |
| — | Pinned to `lsp-types` 0.94.1, `tower` 0.4 | Update to `lsp-types` 0.97, `tower` 0.5 |

### 1.4 Fallback Plan

If vendoring `tower-lsp` proves more maintenance than expected, adopt the community
fork `tower-lsp-server` (crates.io) and accept the `ls-types` dependency. The API
surface is nearly identical, so migration would be straightforward.

## 2. LSP Features

### Phase 1 (MVP)

| Feature | LSP Method |
|---------|-----------|
| Diagnostics | `textDocument/publishDiagnostics` |
| Go to definition | `textDocument/definition` |
| Hover | `textDocument/hover` |
| Completion | `textDocument/completion` |
| Signature help | `textDocument/signatureHelp` |
| Formatting | `textDocument/formatting` |

### Phase 2 (User Demand)

| Feature | LSP Method |
|---------|-----------|
| Find references | `textDocument/references` |
| Rename | `textDocument/rename` |
| Code actions | `textDocument/codeAction` |
| Workspace symbols | `workspace/symbol` |
| Inlay hints | `textDocument/inlayHint` |

### Future

| Feature | LSP Method |
|---------|-----------|
| Semantic tokens | `textDocument/semanticTokens` |
| Call hierarchy | `callHierarchy/incomingCalls`, `callHierarchy/outgoingCalls` |
| Type hierarchy | `typeHierarchy/subtypes`, `typeHierarchy/supertypes` |
| Code lens | `textDocument/codeLens` |

## 3. Rust-JVM Bridge Protocol

The bridge protocol is JSON-RPC 2.0 over stdin/stdout between the Rust process and
the JVM sidecar. This mirrors the LSP transport itself, providing a single mental
model and human-readable wire format for debugging.

### 3.1 Method Table

| Method | Direction | Parameters | Response |
|--------|-----------|------------|----------|
| `initialize` | Rust -> JVM | `{ projectRoot, classpath, compilerFlags, jdkHome }` | `{ success, kotlinVersion }` |
| `textDocument/didOpen` | Rust -> JVM | `{ uri, version, text }` | -- (notification) |
| `textDocument/didChange` | Rust -> JVM | `{ uri, version, text }` | -- (notification) |
| `textDocument/didClose` | Rust -> JVM | `{ uri }` | -- (notification) |
| `analyze` | Rust -> JVM | `{ uri, version }` | `{ diagnostics[] }` |
| `completion` | Rust -> JVM | `{ uri, line, character }` | `{ items[] }` |
| `hover` | Rust -> JVM | `{ uri, line, character }` | `{ contents }` |
| `definition` | Rust -> JVM | `{ uri, line, character }` | `{ locations[] }` |
| `references` | Rust -> JVM | `{ uri, line, character, includeDeclaration }` | `{ locations[] }` |
| `formatting` | Rust -> JVM | `{ uri, options }` | `{ edits[] }` |
| `shutdown` | Rust -> JVM | -- | `{ success }` |
| `ping` | Rust -> JVM | -- | `{ pong }` |
| `$/cancelRequest` | Rust -> JVM | `{ id }` | -- (notification) |

### 3.2 Message Framing

Messages use HTTP-style `Content-Length` framing, identical to LSP:

```
Content-Length: 74\r\n
\r\n
{"jsonrpc":"2.0","id":1,"method":"hover","params":{"uri":"file:///a.kt","line":10,"character":5}}
```

### 3.3 Request ID Management

The Rust bridge maintains its own monotonic request ID counter for outbound requests to
the sidecar. These IDs are independent of the LSP request IDs from the editor. The
bridge maps between the two to support cancellation and response routing.

## 4. Backpressure

Outbound requests from Rust to the JVM flow through a bounded `tokio::sync::mpsc`
channel. When the channel is full, the bridge applies supersession: it drops the
oldest pending request of the same method type.

For example, if a new `completion` request arrives while an older `completion` request
is still queued, the older one is removed and its corresponding LSP request receives a
`RequestCancelled` error. This prevents a slow JVM from creating unbounded request
queues while keeping the LSP event loop responsive.

The channel capacity is set to a small fixed value (e.g., 32). This provides natural
backpressure without blocking the Rust process.

## 5. Cancellation

When the editor sends `$/cancelRequest(id)`:

1. **Check the outbound queue.** If the corresponding request has not yet been sent to
   the JVM, remove it from the queue and immediately respond with
   `ErrorCode::RequestCancelled`.

2. **Forward to the sidecar.** If the request has already been dispatched to the JVM,
   send a `$/cancelRequest` notification to the sidecar with the bridge-side request
   ID.

3. **JVM-side handling.** Cancellation in the JVM is best-effort. The sidecar checks
   `Thread.interrupted()` at natural checkpoints during analysis (e.g., between
   resolution phases). Long-running analysis may not be immediately cancellable.

4. **Respond promptly.** The Rust side responds to the editor with
   `RequestCancelled` as soon as cancellation is initiated, even if the JVM is still
   processing. Any late-arriving JVM response for a cancelled request is discarded.

## 6. Concurrency Model

**JVM sidecar**: The Kotlin compiler is not thread-safe for concurrent mutations to the
same environment. The sidecar uses a single-threaded event loop for analysis with a
separate I/O thread for reading/writing JSON-RPC messages. Requests are processed
sequentially.

**Rust side**: tower-lsp handles concurrent request dispatch. Multiple LSP requests
can be in flight simultaneously within the Rust process.

**Bridge serialization**: A `tokio::sync::Semaphore` with a single permit serializes
analysis requests sent to the JVM. This ensures the sidecar receives one analysis
request at a time while allowing the Rust process to continue handling other LSP
messages (e.g., `textDocument/didChange` notifications, cancellations) concurrently.

Notifications (`didOpen`, `didChange`, `didClose`) bypass the semaphore and are sent
immediately to keep the sidecar's document state current.

## 7. Document Synchronization

### 7.1 Sync Kind

The server advertises `TextDocumentSyncKind::Full`. On every change, the editor sends
the entire document content. The Rust process stores the latest text and version in
`state.rs` and forwards it to the JVM.

Full sync is chosen over incremental sync for two reasons:

- The JVM sidecar needs the full document text to construct the compiler's in-memory
  file system. Incremental sync would require the JVM to apply deltas, adding
  complexity without reducing network traffic (both sides are local).
- The Rust process must store full text for crash recovery replay (see
  `architecture.md` section 3.3). Full sync keeps a single code path.

### 7.2 JVM Document Model

The JVM sidecar maintains an in-memory virtual file system that overlays the on-disk
file system. When the sidecar receives `textDocument/didOpen` or `textDocument/didChange`,
it updates the virtual file. When it receives `textDocument/didClose`, it removes the
virtual file and falls back to the on-disk version.

### 7.3 File Watching

The server registers for `workspace/didChangeWatchedFiles` notifications for the
following patterns:

| Pattern | Reason |
|---------|--------|
| `**/*.kt` | Kotlin source files changed on disk (outside the editor). |
| `**/*.kts` | Kotlin script files. |
| `**/build.gradle.kts` | Gradle build file changes trigger classpath re-resolution. |
| `**/build.gradle` | Groovy-syntax Gradle build files. |
| `**/pom.xml` | Maven build file changes trigger classpath re-resolution. |
| `**/gradle.properties` | Gradle property changes may affect the build. |
| `**/settings.gradle.kts` | Gradle settings changes (module additions/removals). |
| `**/settings.gradle` | Groovy-syntax settings files. |
| `**/.editorconfig` | EditorConfig changes affect formatting behavior (ktlint reads `.editorconfig` natively). |

On build file changes, the Rust process re-runs classpath resolution (via `project.rs`)
and sends an updated `initialize` message to the sidecar.

## 8. Debouncing Strategy

Not all events should be forwarded to the JVM immediately.

**Debounced**: `textDocument/didChange` notifications are debounced before triggering
analysis. The bridge waits 300 ms after the last change event before sending an
`analyze` request to the JVM. Each new change resets the timer. This prevents redundant
analysis during rapid typing.

**Not debounced**: User-initiated requests (`completion`, `definition`, `hover`,
`signatureHelp`, `references`, `formatting`) are forwarded immediately. These are
explicit user actions that expect a prompt response.

**Stale cancellation**: When a new `textDocument/didChange` arrives while a previous
`analyze` request is in flight, the in-flight request is cancelled (its results are
stale). The debounce timer restarts.

```
Editor types "f", "o", "o":

  didChange("f")  ──► start 300ms timer
  didChange("fo") ──► reset timer
  didChange("foo")──► reset timer
                      ... 300ms ...
                      ──► send analyze("foo") to JVM
```

## 9. Configuration Schema

Configuration is received via `workspace/didChangeConfiguration` and the
`initializationOptions` field of the `initialize` request.

```json
{
  "kotlin.javaHome": "/path/to/jdk",
  "kotlin.compiler.flags": ["-Xcontext-parameters"],
  "kotlin.formatting.tool": "ktfmt",
  "kotlin.formatting.style": "google",
  "kotlin.sidecar.maxMemory": "512m",
  "kotlin.trace.server": "verbose"
}
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `kotlin.javaHome` | `string` | `$JAVA_HOME` | Path to the JDK used for the sidecar and for resolving JDK classes. |
| `kotlin.compiler.flags` | `string[]` | `[]` | Additional compiler flags passed to `KotlinCoreEnvironment`. Typically extracted automatically from `build.gradle.kts`, but can be overridden here. |
| `kotlin.formatting.tool` | `string` | `"ktfmt"` | Formatting backend: `"ktfmt"` or `"ktlint"`. |
| `kotlin.formatting.style` | `string` | `"google"` | Formatting style (ktfmt: `"google"`, `"kotlinlang"`, `"meta"`; ktlint: `"standard"`, `"android"`). |
| `kotlin.sidecar.maxMemory` | `string` | `"512m"` | Maximum heap size for the JVM sidecar (`-Xmx` flag). |
| `kotlin.trace.server` | `string` | `"off"` | Log verbosity: `"off"`, `"messages"` (INFO), `"verbose"` (TRACE with full JSON-RPC bodies). |

The configuration struct uses `serde` with `#[serde(default)]` to provide sensible
defaults:

```rust
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Config {
    pub java_home: Option<String>,
    pub compiler_flags: Vec<String>,
    pub formatting_tool: FormattingTool,
    pub formatting_style: String,
    pub sidecar_max_memory: String,
    pub trace_server: TraceLevel,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            java_home: None,
            compiler_flags: Vec::new(),
            formatting_tool: FormattingTool::Ktfmt,
            formatting_style: "google".into(),
            sidecar_max_memory: "512m".into(),
            trace_server: TraceLevel::Off,
        }
    }
}
```

## 10. Capability Advertisement

The server advertises the following capabilities in the `initialize` response. This
list grows as features are implemented across phases.

### Phase 1

```json
{
  "capabilities": {
    "textDocumentSync": {
      "openClose": true,
      "change": 1,
      "save": { "includeText": false }
    },
    "completionProvider": {
      "triggerCharacters": [".", ":", "@"],
      "resolveProvider": false
    },
    "hoverProvider": true,
    "signatureHelpProvider": {
      "triggerCharacters": ["(", ","]
    },
    "definitionProvider": true,
    "diagnosticProvider": {
      "interFileDependencies": true,
      "workspaceDiagnostics": false
    },
    "documentFormattingProvider": true
  }
}
```

### Phase 2 (added incrementally)

```json
{
  "referencesProvider": true,
  "renameProvider": { "prepareProvider": true },
  "codeActionProvider": true,
  "workspaceSymbolProvider": true,
  "inlayHintProvider": true
}
```

## 11. Error Responses

When the bridge cannot fulfill a request, it returns standard LSP error codes:

| Condition | Error Code | Message |
|-----------|-----------|---------|
| Sidecar not ready (state is `Starting` or `Restarting`) | `-32002` (ServerNotInitialized) | `"Kotlin analysis server is starting"` |
| Request cancelled | `-32800` (RequestCancelled) | `"Request was cancelled"` |
| Sidecar returned malformed response | `-32603` (InternalError) | `"Analysis server returned an invalid response"` |
| Sidecar crashed during request | `-32603` (InternalError) | `"Analysis server crashed, restarting"` |
| Unknown method forwarded to sidecar | `-32601` (MethodNotFound) | `"Method not supported"` |
