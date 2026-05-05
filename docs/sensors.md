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

Structured editor-run completion metrics are opt-in:

```bash
KOTLIN_ANALYZER_COMPLETION_METRICS=1 cargo run -- --log-level info
```

When enabled, each Rust completion request sends a correlation id to the sidecar. Rust logs include request receipt, document lookup, bridge enqueue/send, bridge response wait, response conversion, and total timing. Sidecar metrics include dispatch/queue timing when observable plus completion implementation stages and candidate counts.

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
