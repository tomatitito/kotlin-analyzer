#!/usr/bin/env python3
"""Pick a deterministic, distributed set of (file, line, character) positions
for the completion-burst sensor.

Output is TSV on stdout:
    abs_path<TAB>line_zero_based<TAB>character_zero_based<TAB>relative_path

Positions are completion-meaningful: just past a `.` that introduces a member
access on a non-comment, non-import line. One position per file by default
to maximise coverage. The seed parameter makes runs reproducible across
machines and across "before/after" baseline pairs (use the same seed to
sample the same positions).
"""

from __future__ import annotations

import argparse
import os
import random
import re
import sys


SKIP_DIR_NAMES = {
    "build",
    ".gradle",
    ".idea",
    ".git",
    ".kotlin",
    ".kotlin-analyzer",
    "node_modules",
    "out",
}


def is_under_test_source(path: str) -> bool:
    parts = path.replace("\\", "/").split("/")
    return any(p in {"test", "integrationTest", "androidTest"} for p in parts)


def is_skippable_line(stripped: str) -> bool:
    if not stripped:
        return True
    if stripped.startswith(("//", "*", "/*", "import ", "package ", "@")):
        return True
    return False


# Match a literal `.` that introduces an identifier and is not part of `?.`,
# `..`, `::` or a numeric literal like `3.14`.
DOT_RE = re.compile(r"(?<![?.:0-9])\.(?=[a-zA-Z_])")


def find_positions(path: str) -> list[tuple[int, int]]:
    """Return [(line_zero_based, character_zero_based), ...] for this file."""
    out: list[tuple[int, int]] = []
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.readlines()
    except (OSError, UnicodeDecodeError):
        return out
    for i, line in enumerate(lines):
        stripped = line.lstrip()
        if is_skippable_line(stripped):
            continue
        m = DOT_RE.search(line)
        if m is None:
            continue
        # Cursor goes immediately after the dot so completion sees the member
        # prefix that follows.
        col = m.start() + 1
        out.append((i, col))
    return out


def walk_kotlin_files(project_root: str, include_test: bool) -> list[str]:
    found: list[str] = []
    for root, dirs, files in os.walk(project_root):
        dirs[:] = [
            d for d in dirs if d not in SKIP_DIR_NAMES and not d.startswith(".")
        ]
        if not include_test and is_under_test_source(root):
            continue
        for f in files:
            if f.endswith(".kt"):
                found.append(os.path.join(root, f))
    return sorted(found)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("project_root")
    p.add_argument("-n", "--count", type=int, default=12,
                   help="number of positions to emit (default 12)")
    p.add_argument("--seed", default="kotlin-analyzer-perf-baseline",
                   help="random seed; same seed = same positions")
    p.add_argument("--include-test", action="store_true",
                   help="also sample from test source dirs")
    p.add_argument("--positions-per-file", type=int, default=1,
                   help="max positions to take from any single file (default 1)")
    args = p.parse_args()

    project_root = os.path.abspath(args.project_root)
    if not os.path.isdir(project_root):
        print(f"error: not a directory: {project_root}", file=sys.stderr)
        return 1

    files = walk_kotlin_files(project_root, args.include_test)
    if not files:
        print(f"error: no .kt files under {project_root}", file=sys.stderr)
        return 1

    rng = random.Random(args.seed)

    # Build (file, [positions]) pairs and shuffle file order deterministically.
    file_positions: list[tuple[str, list[tuple[int, int]]]] = []
    for path in files:
        positions = find_positions(path)
        if positions:
            file_positions.append((path, positions))

    if not file_positions:
        print("error: no completion-meaningful positions found",
              file=sys.stderr)
        return 1

    rng.shuffle(file_positions)

    print("# abs_path\tline\tcharacter\trelative_path")
    emitted = 0
    for path, positions in file_positions:
        if emitted >= args.count:
            break
        rng.shuffle(positions)
        take = positions[: args.positions_per_file]
        for line, char in take:
            if emitted >= args.count:
                break
            rel = os.path.relpath(path, project_root)
            print(f"{path}\t{line}\t{char}\t{rel}")
            emitted += 1

    if emitted < args.count:
        print(
            f"warning: only found {emitted} positions (asked for {args.count})",
            file=sys.stderr,
        )

    return 0


if __name__ == "__main__":
    sys.exit(main())
