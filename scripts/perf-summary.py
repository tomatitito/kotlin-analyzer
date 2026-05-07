#!/usr/bin/env python3
"""
Aggregate completion-burst sensor results across positions for a baseline tag.

The sensor (tools/sensors completion-burst) writes one JSONL per
(position, scenario) under perf/baselines/<tag>/<project>/<scenario>/.
Each JSONL has one burst record per line. The per-position .stdout next
to the JSONL is human-readable but only covers that one position.

This script pools bursts across all positions for each scenario and
prints a single aggregate table per baseline. Use --verbose to also
print a per-position breakdown.

Percentile convention matches the sensor's render_aggregate
(nearest-rank: idx = round((N-1) * q)) so a single-position aggregate
here matches the corresponding .stdout exactly.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

SCENARIOS = ("tight-burst", "medium-burst", "loose-burst")
DEFAULT_BASELINE_ROOT = Path("perf/baselines")
POSITION_RE = re.compile(r"^(pos\d+)-(.+)-L(\d+)C(\d+)\.jsonl$")


@dataclass
class Aggregate:
    bursts: int
    latest_p50: float
    latest_p95: float
    sc_queue_p95: float
    sc_compute_p95: float
    bf_send: int
    in_flight: int


def percentile(xs: list[float], q: float) -> float:
    """Nearest-rank percentile, matching tools/sensors stat_percentile.

    Rust's f64::round is half-away-from-zero, while Python's round is
    banker's rounding. Use floor(x + 0.5) so the indexing matches the
    sensor's terminal output exactly when both run on the same data.
    """
    if not xs:
        return 0.0
    sorted_xs = sorted(xs)
    idx = math.floor((len(sorted_xs) - 1) * q + 0.5)
    return sorted_xs[min(idx, len(sorted_xs) - 1)]


def aggregate_bursts(bursts: Iterable[dict]) -> Aggregate:
    bursts = list(bursts)
    latest_lat: list[float] = []
    queue: list[float] = []
    compute: list[float] = []
    bf_send = 0
    in_flight = 0

    for b in bursts:
        latest = b.get("latest")
        if latest is not None:
            if (v := latest.get("latency_ms")) is not None:
                latest_lat.append(v)
            if (v := latest.get("sidecar_queue_wait_ms")) is not None:
                queue.append(v)
            if (v := latest.get("sidecar_total_ms")) is not None:
                compute.append(v)
        bf_send += b.get("before_send_delta", 0) or 0
        in_flight += b.get("in_flight_delta", 0) or 0

    return Aggregate(
        bursts=len(bursts),
        latest_p50=percentile(latest_lat, 0.50),
        latest_p95=percentile(latest_lat, 0.95),
        sc_queue_p95=percentile(queue, 0.95),
        sc_compute_p95=percentile(compute, 0.95),
        bf_send=bf_send,
        in_flight=in_flight,
    )


def load_position(path: Path) -> list[dict]:
    bursts = []
    with path.open() as fh:
        for line_no, raw in enumerate(fh, start=1):
            raw = raw.strip()
            if not raw:
                continue
            try:
                bursts.append(json.loads(raw))
            except json.JSONDecodeError as e:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {e}")
    return bursts


def position_label(jsonl_path: Path) -> str:
    m = POSITION_RE.match(jsonl_path.name)
    if not m:
        return jsonl_path.stem
    pos, file_slug, line, char = m.groups()
    return f"{pos} {file_slug} L{line}C{char}"


def resolve_project(tag_dir: Path, project: str | None) -> Path:
    if project:
        p = tag_dir / project
        if not p.is_dir():
            raise SystemExit(f"project not found: {p}")
        return p
    candidates = sorted(p for p in tag_dir.iterdir() if p.is_dir())
    if not candidates:
        raise SystemExit(f"no projects under {tag_dir}")
    if len(candidates) > 1:
        names = ", ".join(p.name for p in candidates)
        raise SystemExit(
            f"multiple projects under {tag_dir}: {names}\n"
            f"  pass --project <name> to disambiguate"
        )
    return candidates[0]


def render_header(meta: dict, project_dir: Path, total_positions: int) -> str:
    sha = meta.get("kotlin_analyzer_git_sha", "?")[:10]
    proj_sha = meta.get("project_git_sha", "?")[:10]
    return (
        f"baseline   : {meta.get('tag', project_dir.parent.name)}\n"
        f"project    : {meta.get('project_name', project_dir.name)} "
        f"(git {proj_sha})\n"
        f"analyzer   : git {sha}"
        f"{' (dirty)' if meta.get('kotlin_analyzer_dirty_files') else ''}\n"
        f"host       : {meta.get('host', '?')} ({meta.get('os', '?')})\n"
        f"ran_at_utc : {meta.get('ran_at_utc', '?')}\n"
        f"positions  : {total_positions}\n"
    )


SCENARIO_HEADER = (
    f"{'Scenario':<14} {'bursts':>6} {'latest_p50':>10} {'latest_p95':>10} "
    f"{'sc_queue_p95':>13} {'sc_compute_p95':>14} {'bf_send':>7} {'in_flight':>9}"
)


def render_row(label: str, agg: Aggregate, label_width: int = 14) -> str:
    return (
        f"{label:<{label_width}} {agg.bursts:>6} "
        f"{agg.latest_p50:>9.1f}ms {agg.latest_p95:>9.1f}ms "
        f"{agg.sc_queue_p95:>11.1f}ms {agg.sc_compute_p95:>12.1f}ms "
        f"{agg.bf_send:>7} {agg.in_flight:>9}"
    )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("tag", help="baseline tag (subdir of --baseline-root)")
    ap.add_argument("--project", help="project name (required if tag has multiple)")
    ap.add_argument(
        "--baseline-root",
        type=Path,
        default=DEFAULT_BASELINE_ROOT,
        help=f"baseline root (default: {DEFAULT_BASELINE_ROOT})",
    )
    ap.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="also print per-position breakdown for each scenario",
    )
    args = ap.parse_args()

    tag_dir = args.baseline_root / args.tag
    if not tag_dir.is_dir():
        print(f"error: baseline tag not found: {tag_dir}", file=sys.stderr)
        return 1

    project_dir = resolve_project(tag_dir, args.project)

    meta_path = project_dir / "meta.json"
    meta = json.loads(meta_path.read_text()) if meta_path.is_file() else {}

    # Discover positions from the first present scenario directory.
    positions: list[str] = []
    for scenario in SCENARIOS:
        sdir = project_dir / scenario
        if sdir.is_dir():
            positions = sorted(p.name for p in sdir.glob("*.jsonl"))
            break
    if not positions:
        print(f"error: no .jsonl files under {project_dir}", file=sys.stderr)
        return 1

    print(render_header(meta, project_dir, len(positions)))
    print(SCENARIO_HEADER)

    per_scenario_positions: dict[str, list[tuple[Path, Aggregate]]] = {}
    for scenario in SCENARIOS:
        sdir = project_dir / scenario
        if not sdir.is_dir():
            continue
        per_position: list[tuple[Path, Aggregate]] = []
        pooled_bursts: list[dict] = []
        for jsonl in sorted(sdir.glob("*.jsonl")):
            bursts = load_position(jsonl)
            per_position.append((jsonl, aggregate_bursts(bursts)))
            pooled_bursts.extend(bursts)
        per_scenario_positions[scenario] = per_position
        print(render_row(scenario, aggregate_bursts(pooled_bursts)))

    if args.verbose:
        max_label = max(
            (len(position_label(p)) for plist in per_scenario_positions.values() for p, _ in plist),
            default=14,
        )
        for scenario, per_position in per_scenario_positions.items():
            print()
            print(f"-- {scenario} per-position --")
            print(
                f"{'position':<{max_label}} {'bursts':>6} "
                f"{'latest_p50':>10} {'latest_p95':>10} "
                f"{'sc_queue_p95':>13} {'sc_compute_p95':>14} "
                f"{'bf_send':>7} {'in_flight':>9}"
            )
            for jsonl, agg in per_position:
                print(render_row(position_label(jsonl), agg, label_width=max_label))

    return 0


if __name__ == "__main__":
    sys.exit(main())
