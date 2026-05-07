---
id: ka-lxum
status: closed
deps: []
links: [ka-g342, ka-wkai, ka-dhkx]
created: 2026-05-07T11:10:12Z
type: task
priority: 3
assignee: Jens Kouros
parent: ka-36lk  # Critical Kotlin analyzer performance
tags: [perf, observability, sensors, tooling]
---
# Aggregate completion-burst sensor results across positions

The `completion-burst` sensor (ka-g342) writes one JSONL file per `(position, scenario)` plus a per-position `.stdout` aggregate. For a real-project baseline that's already ~36 stdout files (12 positions × 3 scenarios). To get a sense of how the analyzer behaves on a project as a whole, you have to read every per-position summary and eyeball-merge them, which is awkward and discourages running the full baseline regularly.

Add a small script that pools bursts across positions for a given baseline tag and prints one aggregate row per scenario, with an opt-in per-position breakdown for spotting outliers.

## Settled decisions

- **Aggregation**: pool the `latest` blocks across all positions for each scenario and apply nearest-rank percentiles (matches the sensor's `stat_percentile` exactly so per-position numbers in `--verbose` mode line up with the existing `.stdout`).
- **Default view**: aggregate-only, one row per scenario. Per-position table is opt-in via `-v`/`--verbose`.
- **Inputs**: positional `<tag>` (subdir of `perf/baselines/`), optional `--project <name>` if the tag has more than one project subdir, optional `--baseline-root <path>` for non-default layouts.
- **Stdlib only**: no `pandas`/`numpy` dependency; the script lives at `scripts/perf-summary.py` and is run from the repo root.
- **Source of truth**: the JSONL records the sensor already writes. No new metric collection; this is purely a presentation layer.

## Acceptance Criteria

- `scripts/perf-summary.py <tag>` (run from repo root) prints a baseline header (project, analyzer SHA, host, run timestamp, position count) followed by a one-row-per-scenario aggregate table with columns `bursts / latest_p50 / latest_p95 / sc_queue_p95 / sc_compute_p95 / bf_send / in_flight`.
- `-v` adds a per-position breakdown for each scenario, sorted by position id, with the same columns plus a position-label column.
- Per-position numbers reproduce the existing `<scenario>/<position>.stdout` aggregates exactly (validated against `pos03 AppBottomSheetController` tight-burst → `p50=2.1ms p95=2.9ms`).
- Auto-detects the project subdir when there's only one; errors with a helpful message listing candidates when there are multiple.
- Aggregate `sc_compute` is sourced from `latest.sidecar_total_ms` to match the sensor's `render_aggregate` terminology, even though "compute" is technically queue+compute.
- Pure stdlib Python (`json`, `argparse`, `pathlib`, `math`, `re`, `dataclasses`).

## Out of scope

- Comparing two baselines (pre vs post). That's a sibling tool — `scripts/perf-diff.py` — better written once we have an actual post-aa4k or post-t7gp baseline to test it against. Filed separately if/when needed.
- Charts, HTML reports, or a notebook view. There's only one baseline today; presentation more elaborate than a terminal table is solving a problem we don't have yet.

## Linked

- ka-g342 [closed] Add completion-burst sensor (sensors metrics run completion-burst)
- ka-wkai [closed] Persist completion metrics to per-session log files in the analyzer repo
- ka-dhkx [closed] Configurable metrics log file location
