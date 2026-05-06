# Performance baselines

Captured runs of the `completion-burst` sensor (ka-g342) against real
Kotlin projects, used to compare user-visible completion latency before
and after performance work in the kotlin-analyzer.

## Layout

```
perf/baselines/<tag>/<project>/
├── meta.json           # SHA, sensor version, hostname, position seed, run params
├── positions.tsv       # the (file, line, character) tuples that were measured
├── tight-burst/
│   ├── pos01-<file-slug>-L<line>C<char>.jsonl     # per-burst raw records
│   ├── pos01-<file-slug>-L<line>C<char>.stdout    # sensor's terminal table
│   ├── pos01-<file-slug>-L<line>C<char>.stderr    # LSP server log
│   └── ...
├── medium-burst/
└── loose-burst/
```

`<tag>` follows `YYYY-MM-DD-<label>` so chronological ordering matches
filesystem ordering. The `<label>` should describe what's about to change
(e.g. `pre-aa4k`, `post-aa4k`, `post-t7gp`).

`<project>` is the short name of the Kotlin project under test.

## Reproducing

The driver script lives at `scripts/sensor-baseline.sh`. It builds the
LSP server and the `sensors` binary in release mode, deterministically
samples completion positions from the project, and runs all three
scenarios (`tight-burst`, `medium-burst`, `loose-burst`) against each
position.

```bash
scripts/sensor-baseline.sh \
    /path/to/your/kotlin/project \
    short-project-name \
    --tag 2026-05-06-pre-aa4k \
    --positions 12
```

Re-run with the **same project name and the same `--positions-seed`**
(default `kotlin-analyzer-perf-baseline`) to sample exactly the same
positions for a fair before/after comparison. The seed is recorded in
`meta.json` so you can verify it.

If you want to lock in a specific position set independent of the
sampler, edit the generated `positions.tsv` after the first run, then
copy it into the next-tag directory before re-running so the script
picks it up. (Currently the script always regenerates `positions.tsv`
via the picker; if you need to override it, run the picker yourself and
pass the produced file when we add `--positions-file` support.)

## Reading the results

The sensor writes per-burst JSONL — one JSON object per measured burst,
with the latency and supersession-outcome of each individual completion
request. The terminal table (in `*.stdout`) is the human-readable
summary; the JSONL is the source of truth for any downstream analysis.

For quick comparisons across runs, focus on the per-scenario aggregate
in the `*.stdout`:

- `latest_p50` / `latest_p95` — user-visible latency for the completion
  the editor actually shows.
- `bf_send` / `in_flight` — supersession counters; both should be high
  for `tight-burst` (proves the latest-wins fix is working) and zero for
  `loose-burst`.
- `sc_queue_p95` / `sc_compute_p95` — sidecar queue wait vs actual
  compute, taken from the latest completion's `completion_metrics`
  stderr line.

For deeper analysis, parse the JSONL with `jq` or pandas. Each burst
record has a `requests` array with per-request latency, outcome, and
`my_seq` (the supersession sequence assigned by the bridge), plus a
`latest` block with the bridge/sidecar timing breakdown for the request
that actually returned items.

## Cost

Each `(scenario × position)` run takes ~25-30 s wall clock (LSP cold
start + JVM sidecar warmup + 10 bursts × 2 s inter-burst gap +
measurements). For 12 positions × 3 scenarios that's ~15-18 minutes.
