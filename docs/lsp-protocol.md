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
| Go to implementation | `textDocument/implementation` |
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
| `hover` | Rust -> JVM | `{ uri, line, character }` | `{ contents, reason? }` |
| `definition` | Rust -> JVM | `{ uri, line, character }` | `{ locations[] }` |
| `implementation` | Rust -> JVM | `{ uri, line, character }` | `{ locations[] }` |
| `references` | Rust -> JVM | `{ uri, line, character, includeDeclaration }` | `{ locations[] }` |
| `formatting` | Rust -> JVM | `{ uri, options }` | `{ edits[] }` |
| `shutdown` | Rust -> JVM | -- | `{ success }` |
| `ping` | Rust -> JVM | -- | `{ pong }` |
| `$/cancelRequest` | Rust -> JVM | `{ id }` | -- (notification) |

`reason` is returned for semantic empty responses (currently `hover`) to help classify why no
user-facing payload was available without changing success/error status.

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
channel with a small fixed capacity (32). This provides natural backpressure without
blocking the Rust process: if the channel fills, the calling handler awaits room.

Completion requests additionally use **latest-wins supersession** (section 4.1) so
that typing-burst behavior in the editor does not pile stale work on the sidecar.

### 4.1 Latest-Wins Supersession (Completion)

Zed (and other editors) often emit several `textDocument/completion` requests in rapid
succession during typing — one per keystroke. Without intervention, the sidecar
processes them one by one and the popup the user actually wants waits behind older
requests for cursor positions they've already typed past. Real-project measurements
showed `bridge_response_wait_ms` of 1300–1500 ms during typing bursts, dominated by
the queue, not by the sidecar's own completion work.

The bridge addresses this with a per-URI supersession sequence. When the LSP completion
handler calls `bridge.request_with_supersession(uri, ...)`:

1. The bridge atomically bumps `latest_seq[uri]` and assigns this request `my_seq`.
2. The request is registered in the pending map and pushed onto the writer task's
   channel as a `QueuedMessage::Supersedable`.

The request then has to pass two checkpoints to be returned to the editor:

#### Checkpoint 1 — `BeforeSend`

The writer task pulls the message off the channel and re-checks `latest_seq[uri]` just
before writing to the sidecar's stdin. If `my_seq < latest_seq[uri]` (a newer request
has been registered while this one was sitting in the channel), the writer drops the
message. The pending oneshot resolves with `BridgeError::Superseded { stage: BeforeSend }`,
the LSP handler returns `Ok(None)`, and the counter `superseded_before_send` increments.
**The sidecar never sees this request — full sidecar CPU is saved.**

#### Checkpoint 2 — sidecar `SupersededAtDispatch`

If the writer wins the race and the request reaches the sidecar's stdin, the sidecar
runs a second supersession check before invoking the heavy Analysis API call. The
sidecar's dispatch loop reads completion requests from stdin, assigns each a
sidecar-local seq, and bumps `latestSeenSeq[bucket]` immediately at read time. When
multiple completions for the same URI pile up in the stdin buffer (the typing-burst
case the writer-side check rarely catches — see ka-rr3u's notes on why), the dispatcher
*lookahead-drains* the buffer so the latest seq is recorded before any of the older
requests in the burst is dispatched. Just before calling `BridgeCompletion.completion(...)`
the sidecar checks: if `my_seq < latestSeenSeq[bucket]`, it short-circuits with an
empty response carrying `reason="superseded-at-dispatch"` and `superseded=true`, and
the cumulative counter `metrics.counts.supersededAtDispatch` increments. **The sidecar
saves the Analysis API call entirely.** The Rust bridge's InFlight check (Checkpoint 3
below) still runs on this short-circuited response — it's cheap and harmless.

This is the second checkpoint after the writer-task `BeforeSend` and is the one that
catches the bursts ka-rr3u's `BeforeSend` cannot — by the time the writer would re-check
`latest_seq[uri]`, the older request is already past it; only a sidecar-side check sees
the newer arrivals.

The check happens at a safe point — between requests, before the Analysis API call —
so the single-threaded contract of the K2 Analysis API is preserved. Mid-flight
cancellation of an in-progress `KaSession` call remains out of scope (see §5).

The sidecar's dispatcher uses the same bucket convention as Rust (`"<uri>"` for
completion, disjoint from `"analyze:<uri>"`), so the two supersession streams do not
interfere with each other.

#### Checkpoint 3 — Rust `InFlight`

When the response arrives back at the bridge (whether it's the real result or the
sidecar's short-circuited `superseded-at-dispatch` shape), the bridge re-checks
`latest_seq[uri]` one more time. If `my_seq < latest_seq[uri]` (a newer request was
issued while we were waiting for the sidecar), the response is discarded and the bridge
returns `BridgeError::Superseded { stage: InFlight }`. The LSP handler returns
`Ok(None)`, and `superseded_in_flight` increments. **The user is spared seeing stale
completion items even if the sidecar's Checkpoint 2 also let one through.**

#### Summary

| Stage | What got saved |
|-------|---------------|
| `BeforeSend` (Rust writer) | Sidecar CPU + user-visible latency |
| `SupersededAtDispatch` (sidecar) | Sidecar Analysis API CPU + user-visible latency |
| `InFlight` (Rust bridge) | User-visible latency only |

#### The invariant

For a tight burst of N completions on the same URI (each request issued before the
previous returned), exactly **one** request — the latest, with `my_seq == N == latest_seq[uri]`
— passes all three checkpoints and returns items. The other **N-1** are caught at one
of the three checkpoints:

```
superseded_before_send + supersededAtDispatch + superseded_in_flight = N - 1
```

The split between stages depends on timing. Real-project measurement before ser-leba
landed under tight bursts (5 ms inter-request gap) saw all supersessions land in
`InFlight` because the writer task is fast enough to drain between each request and
the sidecar had no Checkpoint 2. ser-leba moves the bulk of those `InFlight` cases
to `SupersededAtDispatch` — same user-visible latency improvement, but the sidecar
no longer wastes Analysis API CPU on stale completions (ratio
`supersededAtDispatch / superseded_in_flight` is expected to be > 5x in typical
typing bursts).

#### Scope

Latest-wins is currently scoped to `completion` requests; other methods (`hover`,
`definition`, `references`, etc.) use plain queueing through `bridge.request()`. The
"bucket" that defines what supersedes what is the URI — supersession is per-document,
so a typing burst in `Foo.kt` does not affect a completion request in `Bar.kt`.

#### Observability

When `completionMetrics` is enabled, every completion request — including superseded
ones — emits a structured log under `target=completion_metrics` with `supersession_seq`,
`superseded_before_send_total`, and `superseded_in_flight_total`. The sidecar's
dispatcher Checkpoint 2 is surfaced as a cumulative counter
`metrics.counts.supersededAtDispatch` on every record (visible inside the
`sidecar=...` field of the Rust log line). Short-circuited responses additionally
carry `metrics.fields.supersededAtDispatch="true"` and
`metrics.fields.sidecarSeq` / `sidecarLatestSeq` so the per-request decision is
visible in the log. This makes the supersession effect directly visible in
`KOTLIN_ANALYZER_COMPLETION_METRICS=1` runs and is what the `completion-burst`
sensor (ka-g342) consumes.

### 4.2 Background Analyze Supersession

Background analyze requests (the debounced diagnostics path triggered by
`textDocument/didChange`) are now also supersedable through a bucket-based
cancellation mechanism. Unlike completion's latest-wins supersession, analyze
supersession is invoked explicitly by interactive request handlers.

When the user triggers an interactive request (e.g., completion), the Rust LSP
server calls `bridge.cancel_pending("analyze:{uri}")` immediately after resetting
the interactive debounce timer. This signals the bridge writer task to drop any
pending analyze request for that URI if it's still in the queue (i.e., hasn't been
sent to the sidecar yet).

The `analyze:{uri}` bucket convention ensures that each URI's background diagnostics
can be cancelled independently. Future expansion may add similar `cancel_pending`
calls for other request types (hover, definition, etc.) using their own bucket names.

#### Two Layers of Defence

The ka-hexa implementation provides two layers to reduce wasted sidecar CPU during
interactive bursts:

| Failure mode | Covered by |
|---|---|
| Analyze about to be scheduled when user types | Layer 1: post-interactive cooldown in the debouncer (`POST_INTERACTIVE_QUIET_WINDOW = 500 ms`, capped by `MAX_DEFER = 5 s`). Built on the `interactive_epoch` and `last_interactive_at` signals. |
| Analyze already in writer queue, not yet sent | Layer 2: `Bridge::cancel_pending("analyze:{uri}")` in the completion handler (drops queued analyze before it reaches sidecar stdin) |
| Analyze already mid-`collectDiagnostics` | Out of scope (no mid-flight cancellation) |

The full mechanism behind Layer 1 — the `interactive_epoch` sentinel, the cooldown
timing, and the relationship to the project-wide background analysis loop — is
documented in [architecture.md §8](architecture.md#8-interactive-vs-background-request-scheduling).
This section covers only the wire-level consequences for `analyze` requests.

**Mid-flight cancellation is explicitly not implemented.** The sidecar runs analysis
on a single thread with no checkpoint hooks in the Kotlin Analysis API. Cancelling
an in-flight analyze would require threading interruption state through the compiler,
which is brittle and offers marginal benefit — the interactive cooldown and
queue-based cancellation already cover the high-value cases.

#### Observability

The observable signal for this mechanism is the `analyze_pending_cancelled_total`
field on the `target=completion_metrics` log line. This cumulative counter increments
each time the bridge writer task drops a queued analyze request due to
`cancel_pending`. It resets only on bridge restart.

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

**Not debounced**: User-initiated requests (`completion`, `definition`, `implementation`,
`hover`, `signatureHelp`, `references`, `formatting`) are forwarded immediately. These are
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
  "javaHome": "/path/to/jdk",
  "compilerFlags": ["-Xcontext-parameters"],
  "formattingTool": "ktfmt",
  "formattingStyle": "google",
  "sidecarMaxMemory": "512m",
  "traceServer": "verbose",
  "completionMetrics": true
}
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `javaHome` | `string` | `$JAVA_HOME` | Path to the JDK used for the sidecar and for resolving JDK classes. |
| `compilerFlags` | `string[]` | `[]` | Additional compiler flags passed to `KotlinCoreEnvironment`. Typically extracted automatically from `build.gradle.kts`, but can be overridden here. |
| `formattingTool` | `string` | `"ktfmt"` | Formatting backend: `"ktfmt"` or `"ktlint"`. |
| `formattingStyle` | `string` | `"google"` | Formatting style (ktfmt: `"google"`, `"kotlinlang"`, `"meta"`; ktlint: `"standard"`, `"android"`). |
| `sidecarMaxMemory` | `string` | `"512m"` | Maximum heap size for the JVM sidecar (`-Xmx` flag). |
| `traceServer` | `string` | `"off"` | Log verbosity: `"off"`, `"messages"` (INFO), `"verbose"` (TRACE with full JSON-RPC bodies). |
| `completionMetrics` | `boolean` | `false` | Emit structured completion timing logs for real-project profiling. |

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
    pub completion_metrics: bool,
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
            completion_metrics: false,
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
  "implementationProvider": true,
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
