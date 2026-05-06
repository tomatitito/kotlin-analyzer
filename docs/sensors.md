# Sensors

A **sensor** is a runnable observability probe for kotlin-analyzer. Sensors are not normal tests: they measure behavior, latency, throughput, queueing, or correctness signals and present the result in a form that is useful during development.

The first planned sensor is the completion latency sensor from `ka-rw1x`.

## Command Shape

The intended developer entrypoint is:

```bash
sensors run
```

Future implementations may support selecting a specific sensor:

```bash
sensors run completion-latency
```

and selecting an input project:

```bash
sensors run completion-latency --project-root /path/to/project
```

The command should be easy to run from the repository root. It may delegate to Gradle, Cargo, or a small wrapper binary internally, but the user-facing vocabulary should remain "sensor" rather than "benchmark script" once the CLI exists.

The current implementation exposes the completion latency sensor through Gradle until a top-level `sensors` binary exists:

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

When enabled, each Rust completion request sends a correlation id to the sidecar. Rust logs include request receipt, document lookup, bridge enqueue/send, bridge response wait, response conversion, and total timing. Sidecar metrics include dispatch/queue timing when observable plus completion implementation stages and candidate counts.

### Per-session metrics log files (ka-wkai)

When `KOTLIN_ANALYZER_COMPLETION_METRICS=1` is set (the Zed extension exports it automatically when `completionMetrics: true` is in LSP settings), the LSP server writes the structured `target=completion_metrics` lines to a fresh per-session file under `<kotlin-analyzer-repo>/logs/`. Each file is named `kotlin-analyzer-<UTC-timestamp>.log` (`YYYY-MM-DDTHH-MM-SSZ`) so that runs sort chronologically and survive across sessions for perf-history review.

- Only the `completion_metrics` target lands in the file. General LSP logs continue to go to stderr (or the `--log-file` path, if any).
- The repo root is discovered by canonicalising the running binary's path and walking upward until a directory containing `extension.toml` is found. Release artefacts installed outside the source tree silently skip file creation; the server keeps running and structured metrics still appear on stderr.
- Files accumulate; there is no automatic rotation. The `logs/` directory is committed to git (via `.gitkeep`) so the perf history is visible in PRs.

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
