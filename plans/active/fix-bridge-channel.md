# Fix Hover (and All Bridge Requests) — RESOLVED

## Context

Hover does not work — neither via mouse nor `g h` in vim mode. The root cause is a bug in `server/src/bridge.rs` where `self.request_tx` points to a dead channel (receiver dropped in the constructor). This silently breaks **all** post-initialization bridge requests: hover, completion, go-to-definition, references, notifications, etc. A secondary issue exists in the sidecar where `virtualFiles` is never consulted by analysis methods, but that is out of scope for this fix.

## Bugs Found and Fixed

### Bug 1 — Dead channel (original plan target)

`Bridge::new()` creates a channel and immediately drops the receiver. `Bridge::start()` creates a new channel wired to the sidecar, but never updates `self.request_tx`. Result: all post-initialization requests fail with `BridgeError::Crashed`.

**Fix:** Wrapped `request_tx` in `Mutex<mpsc::Sender<Request>>` for interior mutability. `start()` now swaps in the live sender. `request()`, `notify()`, and `shutdown()` lock before sending.

### Bug 2 — Child process killed on drop

`Bridge::start()` spawns a JVM child process, but the `Child` handle is a local variable. When `start()` returns, `kill_on_drop(true)` kills the JVM immediately after successful initialization.

**Fix:** Added `child: Mutex<Option<tokio::process::Child>>` field to `Bridge` struct. `start()` stores the handle after taking stdin/stdout.

### Bug 3 — tower-lsp deadlock in `initialized` handler

`send_request::<WorkDoneProgressCreate>` in the `initialized` notification handler blocks indefinitely if the client doesn't respond. tower-lsp processes notifications sequentially on one task, so this deadlocks the entire server.

**Fix:** Moved the entire `initialized` handler body into `tokio::spawn`. Wrapped `send_request` and `register_capability` in `tokio::time::timeout(Duration::from_secs(5), ...)`.

### Bug 4 — Sidecar JAR not found via symlink

`std::env::current_exe()` on macOS returns the invocation path without resolving symlinks. When the binary is symlinked from `~/.local/bin/kotlin-analyzer`, the relative path calculation lands in the wrong directory.

**Fix:** Added `std::fs::canonicalize(&exe)` in `find_sidecar_jar()` to resolve symlinks before computing relative paths.

### Bug 5 — JVM `--add-opens` flags after `-jar`

The `--add-opens` JVM flags were placed after `-jar` in the command builder, causing them to be treated as program arguments instead of JVM options.

**Fix:** Moved `--add-opens` arguments before `-jar` in `Bridge::start()`.

## Files Modified

| File | Change |
|------|--------|
| `server/src/bridge.rs` | Wrap `request_tx` in `Mutex`, add `child` field, swap sender in `start()`, lock in `request()`/`notify()`/`shutdown()`, fix `--add-opens` ordering, add test |
| `server/src/server.rs` | Move `initialized` body to `tokio::spawn`, add timeouts for `send_request`/`register_capability`, add `canonicalize` in `find_sidecar_jar()` |

## Verification

- All 31 unit tests pass (`cargo test`)
- Python LSP test client: sidecar initializes in ~600ms, "Ready" progress reported
- Zed: sidecar starts and stays alive after language server restart
- Hover doesn't show content yet (sidecar's analysis API doesn't consult `virtualFiles` — separate issue)
