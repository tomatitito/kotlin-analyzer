#!/usr/bin/env python3
"""log-analysis: aggregate kotlin-analyzer completion_metrics log files.

Two modes:

  sensors run log-analysis
      Read every `kotlin-analyzer-*.log` under .logs/ and print a
      per-metric table where rows are runs (latest first) and columns
      are min/avg/median/max/p99 across the records of that run. Each
      cell carries an arrow indicating whether it moved up or down
      compared to the same cell in the next-older run.

  sensors run log-analysis --path <file>
      Original single-file summary: per-session totals + warm/queued
      latency split. Add -v for the per-record trace, --json for the
      machine-readable JSON shape used since the first version.

`--json` works in both modes.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


# Field-extraction regex for the prefix (everything before " sidecar=").
KV_PATTERN = re.compile(r"(\w+)=(\S+)")
# Marker that splits the prefix (key=value pairs) from the embedded sidecar
# JSON blob. The JSON itself may contain '=' so we anchor on the field name.
SIDECAR_MARKER = " sidecar="
# Filename: kotlin-analyzer-YYYY-MM-DDTHH-MM-SSZ.log → captured timestamp.
LOG_FILENAME_RE = re.compile(r"kotlin-analyzer-(\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z)\.log$")
# Startup-stamp line (introduced alongside server build.rs commit embedding).
# Example:
#   2026-05-07T20:00:05.070993Z  INFO kotlin-analyzer session start version="0.4.3" commit="59633a9bdcfc" dirty="true" pid=40922
SESSION_START_RE = re.compile(r"kotlin-analyzer session start\b")
QUOTED_KV_RE = re.compile(r'(\w+)="([^"]*)"')
# "Warm" = the sidecar wasn't queue-bound when it picked up this request.
# 50 ms is comfortably above the noise floor of the queue_wait calculation
# while still well below typical Analysis API call durations (~150 ms warm).
WARM_QUEUE_WAIT_MS_THRESHOLD = 50.0
# Metrics surfaced in the aggregate table, in the order they should appear.
# `cumulative=True` flags counters that monotonically grow within a session
# (the per-record stats describe the growth curve; max ≈ total events).
AGGREGATE_METRICS: list[tuple[str, bool]] = [
    ("bridge_response_wait_ms", False),
    ("sidecar_queue_wait_ms", False),
    ("supersededAtDispatch", True),
    ("superseded_in_flight_total", True),
    ("superseded_before_send_total", True),
    ("analyze_pending_cancelled_total", True),
]


@dataclass
class Record:
    correlation_id: str
    uri: str
    supersession_seq: int
    bridge_response_wait_ms: float
    sidecar_queue_wait_ms: float
    superseded_before_send_total: int
    superseded_in_flight_total: int
    analyze_pending_cancelled_total: int
    superseded_at_dispatch_cumulative: int | None
    final_items: int | None

    @property
    def is_warm(self) -> bool:
        return self.sidecar_queue_wait_ms < WARM_QUEUE_WAIT_MS_THRESHOLD

    def metric_value(self, name: str) -> float | None:
        mapping: dict[str, float | int | None] = {
            "bridge_response_wait_ms": self.bridge_response_wait_ms,
            "sidecar_queue_wait_ms": self.sidecar_queue_wait_ms,
            "superseded_before_send_total": self.superseded_before_send_total,
            "superseded_in_flight_total": self.superseded_in_flight_total,
            "supersededAtDispatch": self.superseded_at_dispatch_cumulative,
            "analyze_pending_cancelled_total": self.analyze_pending_cancelled_total,
        }
        v = mapping.get(name)
        return float(v) if v is not None else None


@dataclass
class Summary:
    log_path: Path
    timestamp: str | None
    records: list[Record] = field(default_factory=list)
    parse_errors: int = 0
    # Source revision the binary was built from. Either:
    #   - "embedded": startup line carried it (server build.rs path)
    #   - "git-mtime": derived from `git log --before=<file mtime> -1`
    #   - "unknown":   no startup stamp + git lookup failed
    commit: str | None = None
    commit_dirty: bool | None = None
    commit_source: str = "unknown"
    server_version: str | None = None

    def per_uri_seqs(self) -> dict[str, list[int]]:
        per: dict[str, list[int]] = {}
        for r in self.records:
            per.setdefault(r.uri, []).append(r.supersession_seq)
        for seqs in per.values():
            seqs.sort()
        return per

    def commit_label(self) -> str:
        """Short display label: '59633a9bdcfc+' (dirty), '~59633a9bdcfc' (git
        fallback), 'unknown', etc."""
        if self.commit is None:
            return "unknown"
        prefix = "~" if self.commit_source == "git-mtime" else ""
        suffix = "+" if self.commit_dirty else ""
        return f"{prefix}{self.commit[:12]}{suffix}"


def parse_record(line: str) -> Record | None:
    if " completion timing " not in line:
        return None
    if SIDECAR_MARKER not in line:
        return None
    prefix, sidecar_tail = line.split(SIDECAR_MARKER, 1)

    fields: dict[str, str] = {m.group(1): m.group(2) for m in KV_PATTERN.finditer(prefix)}

    sidecar: dict[str, Any] | None
    try:
        sidecar = json.loads(sidecar_tail.rstrip())
    except json.JSONDecodeError:
        sidecar = None

    def get_int(key: str, default: int = 0) -> int:
        try:
            return int(fields.get(key, default))
        except ValueError:
            return default

    def get_float(key: str, default: float = 0.0) -> float:
        try:
            return float(fields.get(key, default))
        except ValueError:
            return default

    counts = (sidecar or {}).get("counts") or {}
    stages = (sidecar or {}).get("stages") or {}

    queue_wait_from_sidecar = stages.get("sidecar_queue_wait")
    queue_wait = float(queue_wait_from_sidecar) if isinstance(queue_wait_from_sidecar, (int, float)) else 0.0

    return Record(
        correlation_id=fields.get("correlation_id", "?"),
        uri=fields.get("uri", "?"),
        supersession_seq=get_int("supersession_seq"),
        bridge_response_wait_ms=get_float("bridge_response_wait_ms"),
        sidecar_queue_wait_ms=queue_wait,
        superseded_before_send_total=get_int("superseded_before_send_total"),
        superseded_in_flight_total=get_int("superseded_in_flight_total"),
        analyze_pending_cancelled_total=get_int("analyze_pending_cancelled_total"),
        superseded_at_dispatch_cumulative=(
            int(counts["supersededAtDispatch"]) if "supersededAtDispatch" in counts else None
        ),
        final_items=int(counts["finalItems"]) if "finalItems" in counts else None,
    )


def load_summary(log_path: Path, *, repo_root: Path | None = None) -> Summary:
    summary = Summary(log_path=log_path, timestamp=extract_timestamp(log_path))
    with log_path.open("r", encoding="utf-8") as fp:
        for line in fp:
            line = line.rstrip("\n")
            if not line:
                continue
            if summary.commit is None and SESSION_START_RE.search(line):
                _populate_commit_from_startup_line(summary, line)
                continue
            try:
                rec = parse_record(line)
            except Exception:
                summary.parse_errors += 1
                continue
            if rec is not None:
                summary.records.append(rec)
    if summary.commit is None and repo_root is not None:
        _populate_commit_via_git_mtime(summary, repo_root)
    return summary


def _populate_commit_from_startup_line(summary: Summary, line: str) -> None:
    fields = {m.group(1): m.group(2) for m in QUOTED_KV_RE.finditer(line)}
    commit = fields.get("commit")
    if not commit or commit == "unknown":
        return
    summary.commit = commit
    summary.commit_dirty = fields.get("dirty", "false").lower() == "true"
    summary.commit_source = "embedded"
    summary.server_version = fields.get("version")


def _populate_commit_via_git_mtime(summary: Summary, repo_root: Path) -> None:
    """Approximate commit lookup for logs that predate the build-time stamp.

    Picks the most recent commit whose author time is `<=` the log file's
    mtime. Always wrong if you ran an older binary against a newer repo,
    so the result is rendered with a `~` prefix to flag the guess.
    """
    iso = datetime.fromtimestamp(summary.log_path.stat().st_mtime, tz=timezone.utc).isoformat()
    try:
        result = subprocess.run(
            [
                "git",
                "-C",
                str(repo_root),
                "log",
                f"--before={iso}",
                "-1",
                "--format=%h",
            ],
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return
    if result.returncode != 0:
        return
    commit = result.stdout.strip()
    if not commit:
        return
    summary.commit = commit
    summary.commit_dirty = None  # unknown — git can't say what was uncommitted
    summary.commit_source = "git-mtime"


def extract_timestamp(path: Path) -> str | None:
    m = LOG_FILENAME_RE.search(path.name)
    return m.group(1) if m else None


def discover_logs(repo_root: Path) -> list[Path]:
    """Return all non-empty kotlin-analyzer-*.log files, latest first.

    Sort key is the timestamp embedded in the filename when available,
    falling back to mtime so unparseable names still order somewhere.
    """
    logs_dir = repo_root / ".logs"
    if not logs_dir.is_dir():
        return []
    candidates = [p for p in logs_dir.glob("kotlin-analyzer-*.log") if p.stat().st_size > 0]

    def sort_key(p: Path) -> tuple[int, str | float]:
        ts = extract_timestamp(p)
        if ts is not None:
            return (0, ts)
        return (1, p.stat().st_mtime)

    return sorted(candidates, key=sort_key, reverse=True)


# ----- stats -----

def percentile(values: list[float], q: float) -> float:
    """Nearest-rank percentile (matches the sensor's render_aggregate)."""
    if not values:
        return 0.0
    s = sorted(values)
    if len(s) == 1:
        return s[0]
    idx = round((len(s) - 1) * q)
    return s[idx]


def latency_stats(values: list[float]) -> dict[str, float]:
    if not values:
        return {"n": 0, "min": 0.0, "p50": 0.0, "p95": 0.0, "max": 0.0, "mean": 0.0}
    return {
        "n": len(values),
        "min": min(values),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "max": max(values),
        "mean": statistics.fmean(values),
    }


def aggregate_stats(values: list[float]) -> dict[str, float | None]:
    """min/avg/median/max/p99 over the per-record values. None if no data."""
    if not values:
        return {"min": None, "avg": None, "median": None, "max": None, "p99": None}
    return {
        "min": min(values),
        "avg": statistics.fmean(values),
        "median": statistics.median(values),
        "max": max(values),
        "p99": percentile(values, 0.99),
    }


def gap_count(seqs: list[int]) -> int:
    if len(seqs) < 2:
        return 0
    return (max(seqs) - min(seqs) + 1) - len(seqs)


# ----- aggregate (default) renderer -----

STAT_KEYS: tuple[str, ...] = ("min", "avg", "median", "max", "p99")


# Total visual width per stat column. Value is right-aligned in (CELL_WIDTH-2)
# characters, then a 1-char arrow, then a 1-char trailing pad. Picked to keep
# headers and cells in sync regardless of arrow presence.
CELL_WIDTH = 11


def _arrow(curr: float | None, prev: float | None) -> str:
    """Up/down arrow for a per-cell change. Single space when unchanged
    or when there's nothing to compare against (oldest run, or one of
    the runs lacks the metric)."""
    if curr is None or prev is None:
        return " "
    if curr > prev:
        return "↑"
    if curr < prev:
        return "↓"
    return " "


def _format_cell(value: float | None, arrow: str) -> str:
    width = CELL_WIDTH - 2  # leave one for arrow and one for trailing pad
    if value is None:
        text = "N/A".rjust(width)
    elif abs(value) >= 1000:
        text = f"{value:>{width}.0f}"
    elif abs(value) >= 10 or value == int(value):
        text = f"{value:>{width}.1f}"
    else:
        text = f"{value:>{width}.2f}"
    return f"{text}{arrow} "


def render_aggregate_text(summaries: list[Summary]) -> str:
    if not summaries:
        return "no log files found under .logs/"

    out: list[str] = []
    out.append(f"runs: {len(summaries)} log file(s) (latest at top)")
    out.append("commit annotations: '+' = dirty tree at build, '~' = approximate (git mtime fallback)")
    for s in summaries:
        ts = s.timestamp or s.log_path.name
        out.append(
            f"  {ts}  commit={s.commit_label():<14}  records={len(s.records):>4}  ({s.log_path.name})"
        )
    out.append("")

    for metric, cumulative in AGGREGATE_METRICS:
        suffix = " (cumulative — max ≈ session total)" if cumulative else ""
        out.append(f"=== {metric}{suffix} ===")
        out.append(_aggregate_table(metric, summaries))
        out.append("")

    return "\n".join(out).rstrip() + "\n"


def _aggregate_table(metric: str, summaries: list[Summary]) -> str:
    rows_stats: list[dict[str, float | None]] = []
    for s in summaries:
        values = [v for r in s.records if (v := r.metric_value(metric)) is not None]
        rows_stats.append(aggregate_stats(values))

    header_cells = "".join(f"{k:>{CELL_WIDTH - 1}} " for k in STAT_KEYS)
    header = f"  {'run':<22}  {'commit':<14}  {header_cells}"
    lines = [header, "  " + "-" * (len(header) - 2)]

    for i, (s, stats) in enumerate(zip(summaries, rows_stats)):
        prev = rows_stats[i + 1] if i + 1 < len(rows_stats) else None
        run_label = s.timestamp or s.log_path.name
        commit_label = s.commit_label()
        cells = "".join(
            _format_cell(stats[k], _arrow(stats[k], prev[k] if prev else None))
            for k in STAT_KEYS
        )
        lines.append(f"  {run_label:<22}  {commit_label:<14}  {cells}")
    return "\n".join(lines)


def render_aggregate_json(summaries: list[Summary]) -> str:
    payload: dict[str, Any] = {
        "runs": [
            {
                "timestamp": s.timestamp,
                "log_path": str(s.log_path),
                "record_count": len(s.records),
                "parse_errors": s.parse_errors,
                "commit": s.commit,
                "commit_dirty": s.commit_dirty,
                "commit_source": s.commit_source,
                "server_version": s.server_version,
            }
            for s in summaries
        ],
        "metrics": {},
    }
    for metric, cumulative in AGGREGATE_METRICS:
        rows: list[dict[str, Any]] = []
        prev_stats: dict[str, float | None] | None = None
        # Compute newest-first, but emit deltas comparing each row to the
        # next-older one (the row below it in the text table).
        per_run_stats = []
        for s in summaries:
            values = [v for r in s.records if (v := r.metric_value(metric)) is not None]
            per_run_stats.append(aggregate_stats(values))
        for i, (s, stats) in enumerate(zip(summaries, per_run_stats)):
            previous = per_run_stats[i + 1] if i + 1 < len(per_run_stats) else None
            deltas = {
                k: _delta_label(stats[k], previous[k] if previous else None) for k in STAT_KEYS
            }
            rows.append(
                {
                    "run": s.timestamp or s.log_path.name,
                    "log_path": str(s.log_path),
                    "stats": {k: stats[k] for k in STAT_KEYS},
                    "delta_from_previous": deltas,
                }
            )
        payload["metrics"][metric] = {"cumulative": cumulative, "rows": rows}
    return json.dumps(payload, indent=2, default=_json_default)


def _delta_label(curr: float | None, prev: float | None) -> str | None:
    if curr is None or prev is None:
        return None
    if curr > prev:
        return "up"
    if curr < prev:
        return "down"
    return "equal"


def _json_default(obj: Any) -> Any:
    if isinstance(obj, Path):
        return str(obj)
    raise TypeError(f"unsupported type for JSON: {type(obj).__name__}")


# ----- single-file (--path) renderer -----

def render_single_text(summary: Summary, *, verbose: bool) -> str:
    out: list[str] = []
    records = summary.records
    out.append(f"log file: {summary.log_path}")
    out.append(f"records:  {len(records)} successful completions logged")
    out.append(f"commit:   {summary.commit_label()} (source: {summary.commit_source})")
    if summary.parse_errors:
        out.append(f"warning:  {summary.parse_errors} unparseable lines")

    if not records:
        out.append("(no completion-timing records found)")
        return "\n".join(out)

    per_uri = summary.per_uri_seqs()

    last = records[-1]
    out.append("")
    out.append("=== session totals (cumulative at last record) ===")
    out.append(f"  superseded_before_send_total : {last.superseded_before_send_total}")
    out.append(f"  superseded_in_flight_total   : {last.superseded_in_flight_total}")
    if last.superseded_at_dispatch_cumulative is not None:
        d = last.superseded_at_dispatch_cumulative
        out.append(f"  supersededAtDispatch (sidecar): {d}")
        if last.superseded_in_flight_total > 0:
            ratio = d / last.superseded_in_flight_total
            out.append(
                f"  ratio dispatch / in_flight   : {ratio:.2f}x"
                f"  (ser-leba ticket target: > 5x)"
            )
    else:
        out.append("  supersededAtDispatch (sidecar): N/A — sidecar payload predates ser-leba")
    out.append(f"  analyze_pending_cancelled    : {last.analyze_pending_cancelled_total}")

    out.append("")
    out.append("=== per-URI activity ===")
    for uri, seqs in sorted(per_uri.items()):
        gaps = gap_count(seqs)
        seen = len(seqs)
        out.append(
            f"  {Path(uri).name}: {seen} returned, {gaps} dropped between seq {min(seqs)}–{max(seqs)}"
        )

    out.append("")
    out.append("=== latency (bridge_response_wait_ms = user-visible) ===")
    all_resp = [r.bridge_response_wait_ms for r in records]
    warm_resp = [r.bridge_response_wait_ms for r in records if r.is_warm]
    queued_resp = [r.bridge_response_wait_ms for r in records if not r.is_warm]
    out.append(f"  all     : {format_stats(latency_stats(all_resp))}")
    out.append(f"  warm    : {format_stats(latency_stats(warm_resp))}  (queue_wait < {WARM_QUEUE_WAIT_MS_THRESHOLD:.0f} ms)")
    out.append(f"  queued  : {format_stats(latency_stats(queued_resp))}")

    out.append("")
    out.append("=== sidecar_queue_wait (time spent in OS pipe before dispatch) ===")
    qw = [r.sidecar_queue_wait_ms for r in records]
    out.append(f"  all     : {format_stats(latency_stats(qw))}")

    if verbose:
        out.append("")
        out.append("=== per-record trace ===")
        prev_dispatch = 0
        prev_in_flight = 0
        prev_before_send = 0
        for r in records:
            d = r.superseded_at_dispatch_cumulative or 0
            di = r.superseded_in_flight_total
            db = r.superseded_before_send_total
            d_delta = d - prev_dispatch
            di_delta = di - prev_in_flight
            db_delta = db - prev_before_send
            prev_dispatch, prev_in_flight, prev_before_send = d, di, db
            warm = "warm" if r.is_warm else "queued"
            out.append(
                f"  seq={r.supersession_seq:>3}  "
                f"resp={r.bridge_response_wait_ms:>7.1f}ms  "
                f"queue={r.sidecar_queue_wait_ms:>7.1f}ms  "
                f"items={(r.final_items if r.final_items is not None else '-'):>4}  "
                f"d@disp+={d_delta:>2} (={d:>2})  "
                f"in_flight+={di_delta:>2} (={di:>3})  "
                f"before_send+={db_delta:>2} (={db:>2})  "
                f"{warm:>6}"
            )

    return "\n".join(out)


def format_stats(s: dict[str, float]) -> str:
    if s["n"] == 0:
        return "n=0"
    return (
        f"n={int(s['n']):>3}  "
        f"min={s['min']:>7.1f}  "
        f"p50={s['p50']:>7.1f}  "
        f"p95={s['p95']:>7.1f}  "
        f"max={s['max']:>7.1f}  "
        f"(mean={s['mean']:.1f}) ms"
    )


def render_single_json(summary: Summary) -> str:
    records = summary.records
    per_uri = summary.per_uri_seqs()
    last = records[-1] if records else None
    payload: dict[str, Any] = {
        "log_path": str(summary.log_path),
        "record_count": len(records),
        "parse_errors": summary.parse_errors,
        "commit": summary.commit,
        "commit_dirty": summary.commit_dirty,
        "commit_source": summary.commit_source,
        "server_version": summary.server_version,
        "per_uri": {
            uri: {
                "returned": len(seqs),
                "dropped": gap_count(seqs),
                "min_seq": min(seqs),
                "max_seq": max(seqs),
            }
            for uri, seqs in per_uri.items()
        },
        "session_totals": (
            {
                "superseded_before_send_total": last.superseded_before_send_total,
                "superseded_in_flight_total": last.superseded_in_flight_total,
                "superseded_at_dispatch_cumulative": last.superseded_at_dispatch_cumulative,
                "analyze_pending_cancelled_total": last.analyze_pending_cancelled_total,
            }
            if last
            else None
        ),
        "latency_ms": {
            "bridge_response_wait_all": latency_stats([r.bridge_response_wait_ms for r in records]),
            "bridge_response_wait_warm": latency_stats(
                [r.bridge_response_wait_ms for r in records if r.is_warm]
            ),
            "bridge_response_wait_queued": latency_stats(
                [r.bridge_response_wait_ms for r in records if not r.is_warm]
            ),
            "sidecar_queue_wait": latency_stats([r.sidecar_queue_wait_ms for r in records]),
        },
    }
    return json.dumps(payload, indent=2)


# ----- CLI -----

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument(
        "--path",
        type=Path,
        help="Single log file to summarise (enables the original per-session view).",
    )
    ap.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Single-file mode: include the per-record trace table.",
    )
    ap.add_argument(
        "--json",
        action="store_true",
        help="Emit machine-readable JSON instead of the text rendering.",
    )
    ap.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="Repo root used to discover .logs/ in aggregate mode.",
    )
    args = ap.parse_args(argv)

    if args.path is not None:
        if not args.path.is_file():
            print(f"error: not a file: {args.path}", file=sys.stderr)
            return 2
        summary = load_summary(args.path, repo_root=args.repo_root)
        if args.json:
            print(render_single_json(summary))
        else:
            print(render_single_text(summary, verbose=args.verbose))
        return 0

    # Aggregate mode (default).
    if args.verbose:
        print(
            "warning: --verbose is only meaningful with --path; ignoring in aggregate mode",
            file=sys.stderr,
        )
    logs = discover_logs(args.repo_root)
    if not logs:
        print(
            f"error: no kotlin-analyzer-*.log files under {args.repo_root}/.logs/",
            file=sys.stderr,
        )
        return 2
    summaries = [load_summary(p, repo_root=args.repo_root) for p in logs]
    if args.json:
        print(render_aggregate_json(summaries))
    else:
        print(render_aggregate_text(summaries))
    return 0


if __name__ == "__main__":
    sys.exit(main())
