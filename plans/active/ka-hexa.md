---
ticket: ka-hexa
parent: ka-36lk
deps: [ka-kyvv, ka-rr3u]
status: draft
created: 2026-05-07
author: planning agent
tags: [perf, completion, scheduling, sidecar, lsp]
baseline_tag: 2026-05-07-post-hexa
---

# Prioritize interactive requests over background analysis

## 1. Problem (verified against current code)

The sidecar event loop is strictly sequential. `AnalysisServer.run()`
(`sidecar/src/main/kotlin/dev/kouros/sidecar/AnalysisServer.kt:19-42`) loops
on `transport.readRequest()` (a blocking read with `Content-Length` framing,
`JsonRpc.kt:49-73`) and invokes `handleRequest` (`AnalysisServer.kt:44-87`)
synchronously per message. Every semantic request — `analyze`, `completion`,
`hover`, `definition`, `codeActions`, … — runs to completion on the same
thread that drives stdin. `$/cancelRequest` is a documented no-op
(`AnalysisServer.kt:78-80`).

The Kotlin Analysis API requires single-threaded access (see
`docs/lsp-protocol.md` section 6 and `docs/architecture.md` section 3),
so the serial dispatch model is non-negotiable. The bottleneck is therefore
*ordering*, not parallelism.

### What the metrics say

`.logs/kotlin-analyzer-2026-05-07T12-54-07Z.log` (outfit-genie,
`ComplementaryItemsService.kt` line 40) recorded two completions:

| metric                              | completion-4 (cold) | completion-6 (warm) |
|-------------------------------------|---------------------|---------------------|
| `rust_total_ms`                     | 1678 ms             | 1154 ms             |
| `bridge_response_wait_ms`           | 1677 ms             | 1153 ms             |
| `sidecar.stages.sidecar_queue_wait` | **1495 ms**         | **1120 ms**         |
| `sidecar_dispatch_total`            | 177 ms              | 28 ms               |
| `superseded_before_send_total`      | 1                   | 1                   |
| `finalItems`                        | 1246                | 1244                |

`sidecar_queue_wait` is the wall-clock between the Rust bridge writing the
request to the sidecar's stdin and the sidecar's `handleCompletion` taking
its `dispatchStart = System.nanoTime()` snapshot
(`AnalysisServer.kt:204` and the
`(System.currentTimeMillis()*1000 - rustSentAtEpochMicros)/1000.0`
calculation at `AnalysisServer.kt:222-225`). The completion took ~89-97% of
its user-visible time *waiting for the sidecar to start dispatching it*.

ka-rr3u's latest-wins fix is firing (`superseded_before_send_total=1` on
both completions), which means the queue is **not** held up by older
completions of the same kind. The only other large-grained Rust→sidecar
request that fires periodically is `analyze` (the debounced diagnostics
path, `server/src/server.rs:875-887`). That is the request blocking
completion.

### Where the ordering goes wrong today

There is already a Rust-side defer mechanism:

- `KotlinLanguageServer::interactive_epoch: Arc<AtomicU64>` (`server.rs:459`).
- `note_interactive_request()` bumps it (`server.rs:499-501`); it is called
  on entry to every interactive handler — completion (`:2006`), hover
  (`:2143`), definition (`:2211`), implementation (`:2262`), references
  (`:2314`), code actions (`:2442`), etc.
- The debounce loop captures the epoch at schedule time and re-checks it
  before dispatching (`server.rs:836-846`):

  ```rust
  if let Some((uri, epoch_at_schedule)) = pending.take() {
      let current_epoch = interactive_epoch.load(Ordering::Relaxed);
      if current_epoch != epoch_at_schedule {
          // defer
          pending = Some((uri, current_epoch));
          continue;
      }
      // … else send analyze …
  }
  ```

This already prevents *new* analyze requests from starting while the user is
typing. **What it does not do** is:

1. **Cancel an analyze that is already enqueued in the bridge writer
   channel** (`bridge.rs:438-471`). Once `bridge.request("analyze", …)` has
   put a `QueuedMessage::Plain` onto the channel, the writer task will
   eventually drain it to stdin, regardless of subsequent interactive
   activity.
2. **Cancel an analyze that has already been written to the sidecar's stdin
   and is being dispatched.** This is genuinely hard given the Analysis API
   constraint and is treated as out of scope (see §10).
3. **Avoid scheduling analyze in tight bursts where the editor sends a flurry
   of `didChange` followed quickly by a `completion`.** The 300 ms
   `debounce_duration` (`server.rs:824`) is short enough that, in a slow
   typing session, an analyze can fire just before the user pauses long
   enough for Zed to ask for a completion — e.g. one keystroke, 350 ms
   pause, completion-trigger keystroke at 400 ms → analyze enqueued at
   300 ms, completion arrives at 400 ms behind it.

## 2. Approach

We adopt **Option A (Rust-side scheduling)** with an explicit, narrow
extension into the bridge layer for cases where analyze has already been
enqueued. We reject Option B (sidecar peeking the queue) and Option C
(functional `$/cancelRequest` for in-flight analyze) as outlined below.

### Rejected: Option B — sidecar peeks the input pipe

The sidecar event loop calls `transport.readRequest()` synchronously
(`AnalysisServer.kt:23`). To peek subsequent messages while an analyze
runs, we would need a second JVM thread reading stdin and a thread-safe
queue between it and the dispatcher. The dispatcher would then have to
poll that queue at safe points inside `analyze(ktFile) { collectDiagnostics(…) }`
(`BridgeDiagnostics.kt:58-66`). The Analysis API exposes no checkpoint
hooks within `collectDiagnostics`, which is the bulk of the cost. The work
to add a second reader is non-trivial and the win on top of Option A is
small (only the rare in-progress analyze is shortened, and only at safe
points we cannot guarantee exist). Risk of subtle Analysis API races is
real. Skipped.

### Rejected: Option C — functional `$/cancelRequest` for in-flight analyze

Same single-threaded constraint as Option B for the in-flight case: the
sidecar cannot react to `$/cancelRequest` while it is mid-`collectDiagnostics`.
For the *queued* case, we already have a better mechanism in the Rust
bridge (drop the `QueuedMessage::Plain` before stdin write — generalise the
`Supersedable` path from ka-rr3u). Adding a sidecar-side cancel would only
duplicate that. We do leave the door open for a future ka- ticket that
implements the Rust-side queued-request cancel from `lsp-protocol.md`
section 5 step 1 (see §10).

### Chosen: Option A, plus per-bucket supersession for `analyze`

Two layers of defence, both in Rust:

1. **Interactive cooldown in the debouncer.** Extend the existing
   `interactive_epoch` mechanism so the debouncer does not just re-check
   the epoch at schedule time, but also enforces a `post_interactive_quiet_window`
   (default 500 ms) before allowing analyze to fire. While any interactive
   request was issued within the last 500 ms, the next analyze cycle is
   pushed out further. This prevents the dominant case where analyze is
   enqueued just before a completion arrives.

2. **Generalise `request_with_supersession` into a `cancel_pending` API
   for analyze.** Today `analyze` uses `bridge.request("analyze", …)`
   (`server.rs:875-887` and `:1685-1691`), which sends `QueuedMessage::Plain`.
   Switch to a supersession-tagged variant whose bucket is the analyze URI
   (or a single "background-analyze" bucket; we use per-URI to avoid one
   open file's analyze killing another file's analyze). Add a Rust-side
   helper `bridge.cancel_pending_analyze(uri)` that bumps the
   `latest_supersession_seq` for that bucket so any queued-but-not-yet-
   written analyze for the same URI is dropped at the writer task's
   `filter_queued_message` checkpoint (`bridge.rs:966-1009`). The completion
   handler invokes this before its own `request_with_supersession` call.

The two layers together cover the three failure modes from §1:

| failure mode                                    | covered by                     |
|-------------------------------------------------|--------------------------------|
| analyze about to be scheduled when user types   | Layer 1 (cooldown)             |
| analyze already in writer queue, not yet sent   | Layer 2 (cancel_pending)       |
| analyze already mid-`collectDiagnostics`         | out of scope (see §10)         |

### Why not just rely on Layer 1?

Layer 1 alone would still leave a race between "debouncer fires analyze at
T-1ms" and "completion handler runs at T+1ms". The analyze is already in
the bridge channel, the writer drains it, the sidecar starts processing it,
and the completion waits ~50-180 ms behind it for the typical warm case
(per the 28-177 ms `sidecar_dispatch_total` we measured). Layer 2 plugs
that hole at the writer-task checkpoint.

### Why not just rely on Layer 2?

Layer 2 alone would let the sidecar churn through analyze→cancel→analyze
cycles when the user types in bursts. That wastes JVM CPU (compiler runs,
result is discarded at writer queue) and battery. Layer 1 is the cheap
"don't even ask the sidecar" path. Layer 2 is the "if we did ask, drop it
quickly" path.

## 3. Composition with prior art

- **ka-rr3u (`bridge.rs` supersession)**: Layer 2 reuses the
  `latest_supersession_seq: HashMap<String, u64>`,
  `QueuedMessage::Supersedable`, and `filter_queued_message` infrastructure
  almost verbatim. We add a public `cancel_pending(bucket: &str)` helper
  and ensure `analyze` uses the supersession path. Buckets stay
  string-typed; we adopt a convention `"analyze:{uri}"` to keep
  completion (`uri`) and analyze (`analyze:{uri}`) in disjoint namespaces.
- **ka-kyvv (no session rebuild during completion)**: closed; this plan
  does not touch `ensureSessionCurrent()` or the BridgeContext freshness
  model. We do depend on it being closed, because if completion still
  triggered a 1-3s session rebuild, fixing the queue ordering would not
  move the needle.
- **`docs/lsp-protocol.md` section 5 (Cancellation)**: documents the
  intended behaviour for editor `$/cancelRequest`. We do not implement
  client-driven cancellation in this ticket, but we leave the door open by
  exposing `bridge.cancel_pending(bucket)` as a public API the cancel path
  can later call.

## 4. File-by-file design

### 4.1 `server/src/bridge.rs`

**New public API:**

```rust
impl Bridge {
    /// Bumps the per-bucket supersession seq, causing any
    /// `QueuedMessage::Supersedable` for `bucket` still in the writer
    /// channel to be dropped at `filter_queued_message`. Returns the
    /// new latest seq.
    ///
    /// In-flight requests (already written to stdin) are not cancelled —
    /// see `docs/lsp-protocol.md` §5 for why mid-flight Analysis API
    /// cancellation is out of scope.
    pub async fn cancel_pending(&self, bucket: &str) -> u64;

    /// Convenience wrapper: `bridge.request_with_supersession` with the
    /// 60s default timeout used by `bridge.request`.
    pub async fn request_supersedable(
        &self,
        method: &str,
        params: Option<Value>,
        bucket: &str,
    ) -> Result<(Value, BridgeRequestTiming, u64), Error>;

    /// Returns the number of pending analyze requests dropped at the
    /// writer-task checkpoint due to `cancel_pending` calls.
    /// Cumulative; resets only on bridge restart.
    pub fn pending_cancelled_count(&self) -> u64;
}
```

**Implementation notes:**

- `cancel_pending` simply does the same `latest_supersession_seq` bump
  that `request_with_supersession` does internally
  (`bridge.rs:730-736`). That is sufficient: when the writer task next
  pulls a `Supersedable` for the same bucket whose `seq < latest`, it
  drops it (`bridge.rs:974-1006`). No interaction with the in-flight
  `pending` map for this bucket is needed beyond what `filter_queued_message`
  already does.
- `pending_cancelled_count` is a new `AtomicU64`. Increment it inside
  `filter_queued_message` whenever the drop reason traces back to an
  external `cancel_pending` call. We can distinguish this from "newer
  request arrived" by comparing the new latest against `tag.seq + 1`:
  if `latest > tag.seq + 1` and there is no pending request currently
  registered with `seq == latest`, the bump came from `cancel_pending`.
  An easier implementation: separate `external_bump_count` from the
  existing `superseded_before_send_count`. We will introduce a single
  combined counter for now and split later if needed; the ticket's
  acceptance criteria do not require splitting.

**Observability:**

When `filter_queued_message` drops a `Supersedable` whose bucket starts
with `"analyze:"`, log the reason at `target = "analyze_supersession"`,
mirroring the `target = "completion_supersession"` convention
(`bridge.rs:983-990`).

### 4.2 `server/src/server.rs`

**`analyze_document` (server.rs:525-620)** and **the debounce loop's analyze
call (server.rs:875-887)**: switch from `bridge.request("analyze", …)` to
`bridge.request_supersedable("analyze", …, &format!("analyze:{}", uri))`.
We discard the returned `my_seq` (we do not need it on the response side
for analyze, because the Rust caller has no UX equivalent of "show stale
results"; superseded analyze just publishes nothing).

The supersession-error case for analyze should not warn — superseded
analyze is the *expected* outcome when an interactive request preempted it.
Match `Err(Error::Bridge(BridgeError::Superseded { .. }))` and log at
`debug` with `target = "analyze_supersession"`.

**`start_debounce_loop` (server.rs:813-897)**:

Add a `post_interactive_quiet_window: Duration` constant (default 500 ms;
see §6 for tuning rationale). Track `last_interactive_epoch_observed`
across iterations. On every wake-up where the timer fires:

```rust
let current_epoch = interactive_epoch.load(Ordering::Relaxed);
if current_epoch != epoch_at_schedule {
    // existing defer path …
    pending = Some((uri, current_epoch));
    continue;
}

// NEW: enforce a quiet window after the most recent interactive bump.
let since_last_interactive = last_interactive_at
    .lock()
    .await
    .map(|t| t.elapsed())
    .unwrap_or(Duration::MAX);
if since_last_interactive < post_interactive_quiet_window {
    let extra = post_interactive_quiet_window - since_last_interactive;
    tokio::time::sleep(extra).await;
    // Re-check epoch after the sleep — typing may have continued.
    let new_epoch = interactive_epoch.load(Ordering::Relaxed);
    if new_epoch != epoch_at_schedule {
        pending = Some((uri, new_epoch));
        continue;
    }
}
// … existing dispatch path …
```

This requires a new field on `KotlinLanguageServer`:

```rust
last_interactive_at: Arc<Mutex<Option<Instant>>>,
```

`note_interactive_request()` is extended to also write `Some(Instant::now())`
to that mutex. Use a `tokio::sync::Mutex<Option<Instant>>` (cheap because
contention is low — interactive requests are not high-frequency) or an
`Arc<AtomicU64>` storing nanos since process start if we want to avoid the
mutex (see §7 risks). The plan picks `tokio::sync::Mutex` for clarity;
implementation may swap to atomics if `cargo bench` shows contention.

**Completion / hover / definition / signatureHelp / references / codeActions
/ implementation / rename / inlayHints handlers**: each calls
`note_interactive_request()` first (already in place). Add one more line
right after, *only* in the completion handler initially and then expanded
to the other interactive handlers in Phase 2 (see §5):

```rust
if let Some(bridge) = self.get_bridge().await {
    let bucket = format!("analyze:{}", uri.as_str());
    let _ = bridge.cancel_pending(&bucket).await;
}
```

Calling `cancel_pending` for buckets that have never been registered is a
no-op (the seq just goes from 0 to 1, and no `Supersedable` with `seq=0`
exists). So this is safe to call unconditionally on every interactive
handler.

### 4.3 `server/src/error.rs`

No changes — `BridgeError::Superseded` already exists with both stages,
and we only consume `BeforeSend` for analyze (since analyze does not
re-check after the response arrives — the writer-side drop is the only
sigil).

### 4.4 `sidecar/src/main/kotlin/dev/kouros/sidecar/AnalysisServer.kt`

No changes in Phase 1 or Phase 2. `$/cancelRequest` stays a no-op.
We could add a comment at line 78-80 noting that cancellation is now
handled at the Rust bridge layer for queued requests and remains
unimplemented for in-flight requests by design (Analysis API constraint).

### 4.5 `tools/sensors/`

No code changes. The existing `completion-burst` sensor already drives the
real LSP binary end-to-end and parses `target = completion_metrics`. Two
new metric fields will appear in the metrics line that ka-hexa emits in
Layer 2:

- `analyze_pending_cancelled_total` (cumulative counter from
  `bridge.pending_cancelled_count()`).

We add this to the existing `completion timing` log at server.rs:2060-2080
under the same target. The sensor's tolerant text parser already ignores
unknown fields, so no parser update is required to land the change. A
follow-up sensor enhancement can graph the new counter, but that is
deferred.

### 4.6 `docs/lsp-protocol.md`

Add a §4.2 "Background analyze supersession" subsection mirroring the
existing §4.1 "Latest-Wins Supersession (Completion)", documenting:

- The `analyze:{uri}` bucket convention.
- `Bridge::cancel_pending(bucket)` as the API the server uses.
- The two layers of defence (cooldown + cancel_pending) and which
  failure modes each covers (the table from §2 of this plan).
- That mid-flight Analysis API cancellation is explicitly not implemented
  and why.

## 5. Phasing

Phases are independently shippable. Phase 1 alone is expected to deliver
most of the win; Phase 2 closes the residual race; Phase 3 is the metrics
& docs polish.

### Phase 1 — Interactive cooldown in the debouncer

**Files**: `server/src/server.rs` only.

**Changes**:

- Add `last_interactive_at: Arc<Mutex<Option<Instant>>>` field to
  `KotlinLanguageServer` (initialise `None`).
- Update `note_interactive_request()` to also write `Some(Instant::now())`
  there.
- Add a `POST_INTERACTIVE_QUIET_WINDOW: Duration = Duration::from_millis(500)`
  constant near the existing `debounce_duration` at server.rs:824.
- Extend `start_debounce_loop` per §4.2 to honour the quiet window before
  dispatching analyze.

**Why this is independent**: it changes only Rust-side scheduling. Existing
behaviour without the new field is preserved (`Option<Instant>` defaults
to `None`, which yields `Duration::MAX` and bypasses the new check on the
very first analyze, exactly like today). All existing unit tests pass
unchanged.

**Tests** (in `server/src/server.rs` `mod tests` if it exists, otherwise
new `server/tests/` integration test):

1. `debounce_loop_defers_analyze_during_quiet_window` — schedule a
   `pending` analyze, fire `note_interactive_request()` 100 ms before the
   debounce timer expires, assert that the analyze does not fire until
   500 ms after the interactive bump.
2. `debounce_loop_fires_analyze_after_quiet_window` — same setup but no
   further interactive bumps; assert analyze fires at
   `interactive_at + quiet_window`.
3. `debounce_loop_resets_quiet_window_on_each_interactive_bump` — fire
   three interactive bumps spaced 200 ms apart; assert analyze does not
   fire until 500 ms after the third.

These tests need access to `interactive_epoch` and `last_interactive_at`
from a test fixture. The simplest path is a free function
`fn run_debounce_loop_for_testing(...)` extracted from
`start_debounce_loop`, parameterised on the quiet window; see §7 for
the test-seam risk.

**Manual validation**:

- Run `KOTLIN_ANALYZER_COMPLETION_METRICS=1` against outfit-genie in Zed,
  trigger 5-10 completions in `ComplementaryItemsService.kt`, verify
  `sidecar_queue_wait` drops below 200 ms p95 in the per-session metrics
  log. (The exact target is set in §6.)

### Phase 2 — `cancel_pending` for analyze + completion handler integration

**Files**: `server/src/bridge.rs`, `server/src/server.rs`.

**Changes**:

- Implement `Bridge::cancel_pending(bucket)` and
  `Bridge::request_supersedable` per §4.1.
- Add `pending_cancelled_count: Arc<AtomicU64>` field; increment it in
  `filter_queued_message` whenever a `Supersedable` is dropped because of
  a bumped `latest_supersession_seq` for an `analyze:` bucket.
- Switch `analyze_document` (server.rs:576-585) and the debounce-loop
  analyze call (server.rs:875-878) from `bridge.request` to
  `bridge.request_supersedable` with `bucket = format!("analyze:{}", uri)`.
- Match `Err(Error::Bridge(BridgeError::Superseded { .. }))` for analyze
  and log at debug (target `analyze_supersession`); do not publish
  diagnostics on supersession.
- In the completion handler (server.rs:2004-2140), immediately after
  `note_interactive_request()`, call
  `bridge.cancel_pending(&format!("analyze:{}", uri.as_str())).await`.

**Tests** (in `server/src/bridge.rs` `mod tests`):

1. `cancel_pending_drops_queued_supersedable` — register a pending
   `Supersedable` analyze, call `cancel_pending` for its bucket, then
   feed the message through `filter_queued_message`; assert it is dropped
   and `pending_cancelled_count` is 1.
2. `cancel_pending_no_op_for_unknown_bucket` — assert calling
   `cancel_pending` for a bucket that has never seen a request returns
   `seq=1` and does not error.
3. `cancel_pending_does_not_drop_other_buckets` — analogue of the
   existing `filter_does_not_drop_when_other_bucket_advances` test.
4. `analyze_and_completion_buckets_are_disjoint` — verify
   `cancel_pending("analyze:x")` does not affect a `Supersedable` with
   bucket `"x"` (URI without the prefix). This is the regression test
   for the convention.

**Why this is independent of Phase 1**: Phase 2 changes only the bridge
API and the analyze callsite. The debouncer cooldown from Phase 1 is
unrelated. Either phase shipped alone improves perf; together they cover
both failure modes from §1.

**Manual validation**:

- Same outfit-genie completion test. Now compare with Phase 1 alone by
  reverting the cooldown and observing the cancel path: the
  `analyze_pending_cancelled_total` counter (added in Phase 3 logging)
  should bump on every completion that arrived while an analyze was in
  the channel.

### Phase 3 — Metrics, logging, and docs

**Files**: `server/src/server.rs`, `docs/lsp-protocol.md`.

**Changes**:

- Add `analyze_pending_cancelled_total = bridge.pending_cancelled_count()`
  to the `target = "completion_metrics"` log at server.rs:2060-2080 (and
  to the supersession branch at server.rs:2103-2116).
- Add `target = "analyze_supersession"` log lines in the analyze callsite
  Err arm.
- Update `docs/lsp-protocol.md` with the new §4.2 per §4.6.
- Update `docs/sensors.md` `completion-burst` section to mention the new
  metric field (one sentence — the parser handles it transparently).

**Tests**:

- Snapshot one `target = completion_metrics` log line against a fixture in
  `server/tests/integration_test.rs` and verify the new field is present.
  (Soft test — if log format churn is too disruptive we drop this and rely
  on manual verification.)

**Why this is independent**: pure observability + docs. No behaviour change.

### Phase 4 (optional, deferred — see §10)

Implement `lsp-protocol.md` §5 step 1: when the editor sends
`$/cancelRequest(id)`, look up whether the corresponding bridge request is
still in the writer queue and, if so, cancel it via the same machinery as
`cancel_pending`. This requires a new `request_id → bucket` mapping the
bridge does not have today and is out of scope for ka-hexa.

## 6. Success criteria

### 6.1 Automated

- `cargo test -p server` passes (existing tests + new ones from §5).
- `cargo clippy -p server -- -D warnings` clean.
- `cargo fmt --check` clean.
- New bridge unit tests assert the cancel_pending invariants from §5.

### 6.2 Performance — `scripts/sensor-baseline.sh` against outfit-genie

Capture the baseline tag `2026-05-07-post-hexa` and compare against
`2026-05-06-pre-aa4k-t7gp-rd05` (existing) and a fresh
`2026-05-07-pre-hexa` capture (to be run on `main` immediately before
landing Phase 1).

The `tight-burst` sensor scenario does not exercise the head-of-line case
this ticket targets (it never enqueues an analyze), so the sensor numbers
will look very similar to the existing
`perf/baselines/2026-05-06-pre-aa4k-t7gp-rd05/outfit-genie/tight-burst/`
output (`latest_p95 ~3 ms`, `sc_queue_p95 ~0.8 ms`). **That is expected
and not a regression** — it confirms supersession from ka-rr3u still works.

The win lives in real Zed sessions where `didChange` and `analyze` are
interleaved with `completion`. We measure that via a manual outfit-genie
Zed session with `KOTLIN_ANALYZER_COMPLETION_METRICS=1`:

| metric                                       | pre-hexa baseline | post-hexa target |
|----------------------------------------------|-------------------|------------------|
| `sidecar_queue_wait` p95 (warm completions)  | ~1100 ms          | < 200 ms         |
| `sidecar_queue_wait` p95 (cold completions)  | ~1500 ms          | < 400 ms         |
| `bridge_response_wait_ms` p95 (warm)         | ~1150 ms          | < 250 ms         |
| `bridge_response_wait_ms` p95 (cold)         | ~1680 ms          | < 450 ms         |
| `analyze_pending_cancelled_total` per session | n/a              | > 0 in any typing session of >=10 chars  |

The cold-case target is looser because the cold-case latency includes
genuinely necessary first-time work (FIR construction, classpath
indexing) that ka-hexa does not address.

The procedure:

1. On `main`, capture `pre-hexa` evidence: open
   `outfit-genie/.../ComplementaryItemsService.kt` in Zed with
   `completionMetrics: true` and `completionMetricsDir: ~/code/kotlin-analyzer/.logs`.
   Type at line 40 column 13 enough to trigger 10+ completions, then
   archive the resulting log under
   `perf/manual-evidence/2026-05-07-pre-hexa.log`.
2. After landing Phase 1+2, repeat the same procedure and archive under
   `perf/manual-evidence/2026-05-07-post-hexa.log`. Compare p95 values
   against the table above.
3. Capture `scripts/sensor-baseline.sh outfit-genie outfit-genie --tag
   2026-05-07-post-hexa` for regression-tracking purposes (sensor numbers
   should remain at or below current `pre-aa4k` levels — this is a
   non-regression check for ka-rr3u).

### 6.3 Manual / behavioural

- Diagnostics still arrive after edits — confirmed by typing into a file
  with a deliberate compile error and observing the squiggly line within
  ~1 second of the last keystroke (the existing 300 ms debounce + the new
  500 ms quiet window = ~800 ms ceiling for diagnostics in a quiet
  session, which is acceptable).
- The cooldown does not starve analyze under continuous interaction. If
  the user types non-stop for 30 seconds, analyze should still fire as
  soon as they pause for 500 ms. No "diagnostics never appear" failure
  modes.
- `did_save` (server.rs:1984-2002) bypasses the debounce and calls
  `analyze_document` directly. Confirm save still produces fresh
  diagnostics within ~200 ms of save (no cooldown applied — saves are
  user-driven so are themselves "interactive" in spirit; we deliberately
  do not gate them).

## 7. Risks

### 7.1 Quiet window starves diagnostics under sustained typing

If the user types continuously, `last_interactive_at` keeps moving
forward and analyze never fires. This is partially mitigated by the fact
that completion / hover / etc. are user-initiated *requests*, not just
keystrokes — `did_change` does **not** call `note_interactive_request()`
today (only request handlers do, server.rs:2006/2143/2211/…). So sustained
typing without ever asking for a completion does not extend the cooldown.

The risk is real for the user who types and triggers completion every
second or two. We have two mitigations:

1. The quiet window is 500 ms. As soon as completion finishes (which is
   itself ~150 ms after Phase 2), if the user pauses, analyze fires
   500 ms later. Worst case: diagnostics lag by ~1 s during heavy
   interaction. That is acceptable.
2. We add a hard ceiling: if `last_interactive_at` is older than
   `MAX_DEFER = 5 s` we ignore it. This guarantees diagnostics within
   ~5.3 s even if the user is interacting nonstop. Implement as:
   `if since_last_interactive < quiet_window && since_last_interactive < MAX_DEFER`.

### 7.2 Mutex contention on `last_interactive_at`

Every interactive handler takes a `tokio::sync::Mutex` lock to write
`Some(Instant::now())`. Contention is low (interactive requests are not
parallel within a single user) but non-zero. Mitigation: switch to an
`AtomicU64` storing nanos-since-process-start if profiling shows
contention. The implementer can decide based on `cargo bench` results;
the API does not change.

### 7.3 `cancel_pending` racing the writer task

Sequence: completion handler calls `cancel_pending(bucket)` at T=0; the
writer task pulls the analyze message at T=1ms; `filter_queued_message`
re-reads `latest_supersession_seq`; sees the bumped seq; drops the
analyze. This is the intended path and is identical to the existing
ka-rr3u machinery — no new race surface.

The only subtle case: what if `cancel_pending` is called *after* the
writer has already written the analyze to stdin? Then `filter_queued_message`
runs on a different (later) message, the analyze is in flight in the
sidecar, and the response will eventually arrive. The Rust caller's
response future resolves with `Ok(diagnostics)` — *not* `Superseded`,
because we did not bump the seq inside `request_with_supersession`'s post-
response check (we did not call that method; we used `request_supersedable`
which does call it, but the response is for *that* `my_seq` which equals
the bumped seq, so the post-check passes).

The result: the analyze runs to completion and publishes diagnostics. That
is correct — the user's completion still got served while the sidecar was
busy (because by the time `cancel_pending` ran, the analyze was already in
flight, so cancellation was too late anyway and we just live with the wait
this one time). No correctness violation. This is the failure mode
explicitly out of scope (§10).

### 7.4 Test seam for the debounce loop

`start_debounce_loop` spawns a `tokio::spawn`-ed task that captures Arcs
by move (server.rs:822). It is hard to unit-test in isolation. Two
options:

- (a) Extract the loop body into a free async function
  `async fn debounce_loop_iteration(ctx: DebounceCtx) -> ControlFlow<()>`
  parameterised on a `DebounceCtx` struct holding all the Arcs and the
  quiet-window duration. Test the iteration deterministically.
- (b) Test end-to-end via an integration test that spawns the full
  server and asserts timing.

The plan recommends (a) for fast feedback; (b) is appropriate for the
manual-validation step in §6.

### 7.5 Supersession bucket namespace collision

If we accidentally use `bucket = "analyze:" + uri` for analyze and
`bucket = uri` for completion, they are disjoint — but a refactor that
unifies them would break completion supersession silently. Mitigation:
the bucket-disjoint test in Phase 2 (`analyze_and_completion_buckets_are_disjoint`)
catches this. Also document the convention in `docs/lsp-protocol.md` §4.2.

### 7.6 `did_save` analyze path is not supersedable

`analyze_document` is also called from `did_save` (server.rs:2001). With
Phase 2, this analyze is supersedable. If the user saves and then
immediately requests completion, the save's analyze can be cancelled. We
judge this acceptable — the next completion still triggers a fresh
analyze on the user's next pause. If we want save to be uncancellable, we
add a `analyze_force(uri)` path that uses `bridge.request` (no
supersession). The plan does not include this initially; we can add it if
manual validation shows save diagnostics regressing.

### 7.7 Health-check `ping` is `QueuedMessage::Plain`, not Supersedable

Confirmed by reading `bridge.rs:218` — health checks use Plain. They are
unaffected by `cancel_pending`. Good.

### 7.8 `restart` flow

`Bridge::start` (`bridge.rs:291-558`) creates a new request channel on
restart. The `latest_supersession_seq` map is **not** cleared. After a
sidecar crash + restart, an `analyze:foo.kt` bucket from before the crash
will remember its seq. This is harmless for correctness (seq monotonically
increases) but means the first post-restart analyze for that URI will
have `my_seq = old_seq + 1`. No one notices. We do **not** add a clear
step; if we ever do, it must be an explicit decision.

## 8. Out-of-scope (for next ticket conversation)

These came up during research but explicitly do not belong in ka-hexa:

- **Mid-flight Analysis API cancellation.** The biggest residual win
  would be cancelling an analyze that has already started inside the
  sidecar. This requires either (a) Analysis API cooperative checkpoints
  the JetBrains team has not exposed, or (b) hard-killing the analysis
  thread (which the Analysis API does not support — would corrupt the
  session). Defer indefinitely; revisit if the K2 Analysis API exposes a
  cancellation token.
- **Per-bucket queue depth limits.** Today the bridge channel is bounded
  at 32 (`bridge.rs:438`). If background analyze ever fires faster than
  the writer drains, we backpressure on the entire bridge, which can
  block didChange notifications. ka-hexa does not change channel size or
  add per-bucket caps. A future ticket should consider per-bucket caps
  if the sensor sees unexpected backpressure.
- **Richer prioritization beyond binary "interactive vs background".**
  Today everything user-initiated is "interactive". There is a case for
  ranking completion above hover above codeActions etc. (e.g. codeActions
  fires on every cursor move and is expensive; we may want it to yield to
  completion specifically). Out of scope.
- **Per-request budgets / soft timeouts.** Currently analyze has a 60 s
  default timeout (`bridge.rs:621`). A soft budget that converts an
  expensive analyze into "publish what we have so far" mid-flight would
  require sidecar streaming. Out of scope.
- **Functional `$/cancelRequest` from the editor (Phase 4 above).**
  Useful for the case where Zed cancels a completion the user no longer
  cares about (different from the supersession case, which is about
  newer completions superseding older ones from the same session). Worth
  doing as a follow-up; not necessary for ka-hexa's win.
- **Sidecar-side queue draining for in-flight analyze.** Option B from
  the ticket. Rejected per §2; revisit only if Analysis API gains
  checkpoint hooks.
- **Single-flight `analyze` per URI (deduplication).** A simpler model
  where there is at most one in-flight `analyze` per URI in the bridge
  pending list, and a second arrival simply waits on the first's
  oneshot. This would also help, but it composes awkwardly with
  cancellation (cancelling the in-flight one would orphan the second
  caller). Skipped in favour of supersession + cooldown.

## 9. Estimated effort

- Phase 1: ~150 LoC + 3 unit tests. ~half a day.
- Phase 2: ~120 LoC + 4 unit tests. ~half a day.
- Phase 3: ~30 LoC + docs. ~1-2 hours.
- Manual validation against outfit-genie + baseline capture: ~1 hour
  (including the ~22 min `sensor-baseline.sh` run).

Total: ~1.5 days for the implementer plus baseline capture.

## 10. Done definition

- All acceptance criteria from `.tickets/ka-hexa.md` are satisfied:
  - Interactive requests are not blocked behind in-flight background
    analysis (verified by p95 numbers in §6.2).
  - Diagnostics still arrive after edits (verified by §6.3 manual check).
  - No data races or concurrent Analysis API access (no sidecar changes
    were made; Phase 2's bridge changes are pure async-Rust).
- `perf/baselines/2026-05-07-post-hexa/` directory exists with a fresh
  outfit-genie `sensor-baseline.sh` capture.
- `perf/manual-evidence/2026-05-07-{pre,post}-hexa.log` archived for the
  before/after comparison.
- Ticket notes appended summarising the measured deltas.
- `docs/lsp-protocol.md` has the new §4.2 background analyze supersession
  section.
- `plans/active/ka-hexa.md` is moved to `plans/completed/` once
  acceptance criteria are met (per the project convention used by
  `plans/completed/research.md` etc.).
