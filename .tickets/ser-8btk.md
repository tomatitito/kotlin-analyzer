---
id: ser-8btk
status: closed
deps: []
links: []
created: 2026-05-07T20:30:22Z
type: feature
priority: 2
assignee: Jens Kouros
tags: [dx, sensors, observability, perf]
---
# sensors run dispatch + log-analysis script for completion-metrics logs

Manual triage of `.logs/kotlin-analyzer-*.log` files (the per-session
completion_metrics tracing output) was being done by hand every time
ser-leba evidence needed reading. Each log is dozens of `INFO completion
timing …` lines with an embedded sidecar JSON blob; eyeballing
supersession ratios, queue waits and latency distributions across runs
was unreproducible and slow.

Adds:

- `.sensors/log-analysis.py` — stdlib-only Python script with two modes:
  - default: aggregates every non-empty `.logs/kotlin-analyzer-*.log`
    file into a per-metric table (rows = runs, latest first; columns =
    min/avg/median/max/p99). Each cell carries an ↑/↓ arrow comparing
    to the same cell in the next-older run. Run-list preamble shows
    timestamp + record count per file.
  - `--path <file>`: original single-session view — totals,
    warm-vs-queued latency split, optional `-v` per-record trace.
  - Both modes accept `--json` for machine-readable output.

- `tools/sensors` Rust binary grew a `Run(name, ...)` subcommand that
  walks up from cwd to find `.sensors/`, then shells out to
  `python3 .sensors/<name>.py <forwarded-args…>`. `trailing_var_arg` +
  `allow_hyphen_values` keep `-v --json` etc. flowing through to the
  script. This is the entry point referenced by `docs/sensors.md` as
  the `sensors run <name>` UX.

Companion ticket sid-r0r1 covers the build-time commit stamping that
makes the `commit` column meaningful for runs going forward.

## Acceptance Criteria

- `sensors run log-analysis` reads every non-empty .logs/*.log and
  emits per-metric aggregate tables, latest at top, with arrows.
- `sensors run log-analysis --json` emits the same data as JSON whose
  shape includes per-row `delta_from_previous` markers (up/down/equal/null).
- `sensors run log-analysis --path <file>` reproduces the original
  single-session text summary; `--path <file> -v` adds the per-record
  trace; `--path <file> --json` emits the original JSON shape.
- `sensors run <unknown>` returns a clear "sensor script not found"
  error pointing at the expected `.sensors/<name>.py` path.
