# Sensors

A **sensor** is a runnable observability probe for kotlin-analyzer. Sensors are not normal tests: they measure behavior, latency, throughput, queueing, or correctness signals and present the result in a form that is useful during development.

The first planned sensor is the completion latency sensor from `ka-rw1x`.

## Command Shape

The developer entrypoint is the `sensors` binary built from `tools/sensors/`. Two flavours of sensor live under it:

- **Rust sensors** are subcommands of the binary (currently `completion-burst`, see below). Run as `sensors <name> [flags]`.
- **Script sensors** live as Python files under `<repo-root>/.sensors/<name>.py` and are dispatched by `sensors run <name>`. The `Run` subcommand walks up from cwd to find `.sensors/`, then shells out to `python3` with any trailing args forwarded verbatim. Override the discovered root with `--repo-root`.

```bash
sensors completion-burst --help            # Rust sensor
sensors run log-analysis                   # script sensor
sensors run log-analysis --json            # trailing args forward to the script
```

The binary needs to be on `PATH`. Symlink it the same way as the LSP server:

```bash
ln -sf "$(pwd)/tools/sensors/target/release/sensors" ~/.local/bin/sensors
```

Until the symlink is in place, run via cargo:

```bash
cargo run --release --manifest-path tools/sensors/Cargo.toml -- run log-analysis
```

A legacy Gradle entrypoint also exists for the older Kotlin-based completion benchmark:

```bash
cd sidecar && ./gradlew benchmark
cd sidecar && ./gradlew benchmark --args="--warmups 3 --iterations 30 --jsonl-output build/completion-sensor.jsonl"
```

### completion-burst (ka-g342)

`completion-burst` is the first Rust-based sensor and lives at `tools/sensors/`. It drives the real `kotlin-analyzer` LSP binary over stdin/stdout (the same path Zed uses) with configurable typing-burst loads and reports the user-visible completion latency under supersession pressure introduced by ka-rr3u.

The sensor enables `completionMetrics` via `initializationOptions`, parses the LSP server's `tracing` stderr for `target=completion_metrics` lines, and emits per-burst JSONL plus a terminal table summary. Output is two layers: a one-line per-scenario table summary, and (if `--jsonl-output` is set) one JSON object per measured burst written to disk.

#### Building

Run from the repository root:

```bash
# 1) Build the LSP server once. The sensor auto-discovers this binary.
(cd server && cargo build --release)

# 2a) Quickest invocation — runs cargo each time:
cargo run --manifest-path tools/sensors/Cargo.toml -- completion-burst --help

# 2b) Or build the sensor binary once and call it directly:
(cd tools/sensors && cargo build --release)
./tools/sensors/target/release/sensors completion-burst --help
```

#### Running against a real Kotlin file

```bash
./tools/sensors/target/release/sensors completion-burst \
    --project-root /path/to/your/kotlin/project \
    --target-file  /path/to/your/kotlin/project/src/main/kotlin/Foo.kt \
    --line 42 --character 8 \
    --scenario tight-burst \
    --jsonl-output /tmp/tight-burst.jsonl
```

`--line` and `--character` are 0-based (LSP convention). Pick a position where completion is meaningful — typically just after a `.` on an expression.

#### Useful flags

| Flag | What it does |
|------|-------------|
| `--scenario tight-burst\|medium-burst\|loose-burst` | Named preset. Tight = 5 ms gap (queues faster than the writer drains), medium = 25 ms, loose = 200 ms. |
| `--burst-size N` / `--burst-count M` / `--inter-request-gap-ms` / `--inter-burst-gap-ms` | Ad-hoc scenario knobs (mutually exclusive with `--scenario`). |
| `--warmup-bursts N` | Bursts to run before measurement (default 1). A single readiness-warmup completion always fires regardless. |
| `--jsonl-output PATH` | Per-burst raw records, one JSON object per line. |
| `--regression-guard` | Asserts the tight-burst invariant (`burst_size - 1` supersessions per burst); exits non-zero on violation. |
| `--lsp-binary PATH` | Override the LSP binary. Otherwise uses `KA_BIN` env var, then `<repo>/server/target/release/kotlin-analyzer` (built on demand). |

Run `... completion-burst --help` for the full list.

#### LSP binary discovery order

1. `--lsp-binary <path>` flag
2. `KA_BIN` environment variable
3. `<repo>/server/target/release/kotlin-analyzer` (built on demand via `cargo build --release` in `server/` if missing)

Structured editor-run completion metrics are opt-in:

```bash
KOTLIN_ANALYZER_COMPLETION_METRICS=1 cargo run -- --log-level info
```

When enabled, each Rust completion request sends a correlation id to the sidecar. Rust logs include request receipt, document lookup, bridge enqueue/send, bridge response wait, response conversion, and total timing. Sidecar metrics include dispatch/queue timing when observable plus completion implementation stages and candidate counts. The `analyze_pending_cancelled_total` cumulative counter is included in each completion timing record to track how often the ka-hexa cancel-pending path fires in real Zed sessions.

### Per-session metrics log files (ka-wkai, ka-dhkx)

When `completionMetrics` is enabled, the LSP server can write the structured `target=completion_metrics` lines to a fresh per-session file. The destination is configured explicitly — there is no auto-discovery — and is resolved in this order:

1. `KOTLIN_ANALYZER_METRICS_DIR` environment variable (wins if set in the inherited shell environment).
2. The Zed LSP setting `completionMetricsDir` (sibling of `completionMetrics`). The extension expands a leading `~` to `$HOME` and exports the result as `KOTLIN_ANALYZER_METRICS_DIR`, but only when `completionMetrics: true` is also set. Setting the directory without enabling `completionMetrics` is a no-op.

If neither is set, no file is written and the server runs normally. Each session writes to `<dir>/kotlin-analyzer-<UTC-timestamp>.log` with `YYYY-MM-DDTHH-MM-SSZ` so runs sort chronologically.

```jsonc
// Zed settings.json
{
  "lsp": {
    "kotlin-analyzer": {
      "initialization_options": {
        "completionMetrics": true,
        "completionMetricsDir": "~/code/kotlin-analyzer/logs"
      }
    }
  }
}
```

- Only the `completion_metrics` target lands in the file. General LSP logs continue to go to stderr (or the `--log-file` path, if any).
- Missing intermediate directories under the configured path are created via `std::fs::create_dir_all`. If creation fails (permissions, read-only filesystem, typo), the server logs `metrics file disabled: cannot create <path>: <reason>` once at info level and keeps running. There is no fallback path — typos surface this way instead of being silently rerouted.
- Files accumulate; there is no automatic rotation. The repo's `logs/` directory remains a sensible default that the user opts into via the setting, not via auto-discovery; `.gitkeep` keeps the path stable.
- **Multi-worktree caveat**: a single LSP server process can serve multiple worktrees. If two worktrees configure different `completionMetricsDir` values, the value from whichever worktree initialised the server first wins for the rest of the process lifetime.

### log-analysis (ser-8btk)

`log-analysis` is a script-based sensor that aggregates the per-session metrics log files produced by the section above. It is the read side of `completionMetrics` — the tool you run after typing in Zed to see what the supersession machinery actually did. Lives at `.sensors/log-analysis.py`, dispatched via the `sensors run` wrapper added in the same ticket.

#### Invocation

```bash
sensors run log-analysis                   # aggregate every .logs/*.log, latest first
sensors run log-analysis --json            # same data, machine-readable
sensors run log-analysis --path FILE       # original single-session view
sensors run log-analysis --path FILE -v    # adds the per-record trace
sensors run log-analysis --path FILE --json
```

The default mode reads every non-empty `kotlin-analyzer-*.log` under `<repo-root>/.logs/`. Pass `--repo-root` to point at a different `.logs/` location (the wrapper auto-discovers `.sensors/` by walking up from cwd, so most users never need this).

The script is stdlib-only Python 3 — no `pip install` step. The wrapper shells out to `python3` from `PATH`.

#### Aggregate-mode output

```
runs: 8 log file(s) (latest at top)
commit annotations: '+' = dirty tree at build, '~' = approximate (git mtime fallback)
  2026-05-07T15-38-46Z  commit=59633a9bdcfc+   records=   8  (kotlin-analyzer-...log)
  ...

=== bridge_response_wait_ms ===
  run                   commit              min       avg     median       max       p99
  --------------------------------------------------------------------------------------
  2026-05-07T15-38-46Z  59633a9bdcfc+    140.8↓   646.9↓    234.6↓    1862↓    1862↓
  2026-05-07T15-35-08Z  ~6947b02          2599↑    2599↑     2599↑    2599↑    2599↑
  ...
```

One table per metric. Rows are runs (latest at top); columns are `min`, `avg`, `median`, `max`, `p99` over the per-record values within that run. Each cell carries an arrow comparing it to the same cell in the row below (the next-older run): `↑` higher, `↓` lower, blank if equal or if either side has no value.

The commit column distinguishes three sources:
- `<sha>` or `<sha>+` — the binary self-stamped via `server/build.rs` on startup. Trustworthy. `+` means the build tree was dirty (uncommitted edits at compile time).
- `~<sha>` — fallback from `git -C <repo> log --before=<file mtime> -1 --format=%h`. **Approximate**: it picks the latest commit that existed when the log file was last written, which is wrong if you ran an old binary against newer code or forgot to rebuild. Useful for historical logs that predate the `build.rs` stamp.
- `unknown` — neither path produced a commit.

#### Metric legend

| Metric | Layer | What "up" means | Direction-of-good |
|---|---|---|---|
| `bridge_response_wait_ms` | Rust LSP | User-visible latency from request to response | down |
| `sidecar_queue_wait_ms` | Sidecar | Time the request sat in the OS pipe before dispatch | down |
| `superseded_before_send_total` † | Rust writer | Cumulative requests dropped before stdin write | up = better (cheap catch) |
| `supersededAtDispatch` † | Sidecar dispatcher | Cumulative requests short-circuited before the K2 Analysis API call (ser-leba Checkpoint 2) | up = better (saves ~150 ms each) |
| `superseded_in_flight_total` † | Rust bridge | Cumulative responses discarded after the sidecar already did the work | down = better (less wasted CPU) |
| `analyze_pending_cancelled_total` † | Rust bridge | Cumulative background analyze requests cancelled by interactive bursts (ka-hexa) | neutral — depends on typing |

† marks cumulative counters; their `max` within a row ≈ the session total. `min` / `avg` / `median` describe the growth curve and are mostly useful for spotting which point in the session the counter jumped.

For the supersession story end-to-end, see [`docs/lsp-protocol.md` §4.1](lsp-protocol.md#41-latest-wins-supersession-completion). The three counters together budget every typing-burst keystroke that gets dropped: each goes through exactly one of the three checkpoints, and the three counters sum to "total requests dropped per bucket".

#### Single-session output (`--path FILE`)

The original per-session summary: cumulative totals at the last record, per-URI activity breakdown (returned vs. dropped), and warm-vs-queued latency split (the `WARM_QUEUE_WAIT_MS_THRESHOLD = 50 ms` constant in the script defines the boundary). Add `-v` for a per-record trace that shows when each cumulative counter advanced.

Use this when you want to dig into a specific run; use the default aggregate mode when you want trend lines across runs.

#### JSON output

`--json` is supported in both modes. Aggregate mode emits:

```jsonc
{
  "runs": [{"timestamp": "...", "commit": "...", "commit_source": "embedded|git-mtime|unknown", "commit_dirty": true, "record_count": 8, ...}, ...],
  "metrics": {
    "bridge_response_wait_ms": {
      "cumulative": false,
      "rows": [
        {
          "run": "2026-05-07T15-38-46Z",
          "stats": {"min": 140.8, "avg": 646.9, "median": 234.6, "max": 1861.8, "p99": 1861.8},
          "delta_from_previous": {"min": "down", "avg": "down", ...}
        },
        ...
      ]
    },
    ...
  }
}
```

Single-session mode emits the original shape (per-URI map, session totals, latency stats split by warm/queued).

## Output Model

Sensors should produce two layers of output:

- A human-readable terminal table for day-to-day development.
- Structured raw output, such as JSONL, for before/after comparisons and CI artifacts.

For the completion latency sensor, the terminal view should summarize multiple completion scenarios across multiple files or features. The table should include:

- scenario name and source file;
- sample count;
- min, average, max, and preferably p95 latency;
- Rust-side timing total;
- sidecar queue/wait time when observable;
- sidecar completion compute time;
- candidate counts for member, scope, auto-import, and keyword stages;
- final completion item count.

Example shape:

```text
Completion Latency Sensor

Scenario              File                  N   min   avg   p95   max   rust   wait   sidecar   items
member-dot            Controller.kt        30   42ms  58ms  81ms  96ms  8ms    2ms    48ms      37
scope-prefix          Service.kt           30   51ms  74ms  99ms  121ms 9ms    1ms    64ms      112
auto-import-prefix    Handler.kt           30   88ms  133ms 190ms 240ms 10ms   3ms    120ms     24
```

The table is the TUI experience. It should be readable in a normal terminal, avoid noisy per-request logs by default, and make regressions obvious without requiring external tooling.

The JSONL raw output stores one object per scenario sample with total latency, final item count, candidate counts, and per-stage sidecar timings. This keeps before/after comparison machine-readable while preserving the terminal table for normal development.

## Real-Project Readiness

Sensors should be fixture-runnable first, but they should not bake in fixture-only assumptions. A sensor should accept enough project/scenario configuration that a follow-up ticket can run it against a real Kotlin project without rewriting the metrics pipeline.

For completion latency work, that split is:

- `ka-rw1x`: implement the completion latency sensor, metrics plumbing, structured output, and table renderer against fixtures/local paths.
- `ka-j4dc`: point the sensor at a real Kotlin project.
- `ka-xjd7`: record the real-project baseline and decide which bottlenecks to prioritize.
