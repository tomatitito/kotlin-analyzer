#!/usr/bin/env bash
# Drive the completion-burst sensor across all three scenarios for a set of
# completion positions in a real Kotlin project, saving per-burst JSONL plus
# a manifest under perf/baselines/<tag>/<project>/.
#
# Usage:
#   scripts/sensor-baseline.sh <project-root> <project-name> [--tag YYYY-MM-DD-label] [--positions N]
#
# Example:
#   scripts/sensor-baseline.sh /Volumes/sourcecode/.../outfit-genie outfit-genie --tag 2026-05-06-pre-aa4k
#
# Re-run with the same project name and the same --positions-seed (default
# "kotlin-analyzer-perf-baseline") to sample exactly the same positions for
# a before/after comparison.

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

project_root=""
project_name=""
tag="$(date -u +%Y-%m-%d)"
positions_count=12
positions_seed="kotlin-analyzer-perf-baseline"
burst_count=10
warmup_bursts=1

usage() {
    sed -n '1,/^set -euo/p' "$0" | sed -n 's/^# \?//p'
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --tag)               tag="$2"; shift 2 ;;
        --positions)         positions_count="$2"; shift 2 ;;
        --positions-seed)    positions_seed="$2"; shift 2 ;;
        --burst-count)       burst_count="$2"; shift 2 ;;
        --warmup-bursts)     warmup_bursts="$2"; shift 2 ;;
        -h|--help)           usage ;;
        *)
            if [ -z "$project_root" ]; then
                project_root="$1"
            elif [ -z "$project_name" ]; then
                project_name="$1"
            else
                echo "error: unexpected arg: $1" >&2; usage
            fi
            shift
            ;;
    esac
done

[ -z "$project_root" ] && { echo "error: missing <project-root>" >&2; usage; }
[ -z "$project_name" ] && { echo "error: missing <project-name>" >&2; usage; }
[ -d "$project_root" ] || { echo "error: not a directory: $project_root" >&2; exit 1; }

# 1) Build the LSP server and the sensor binary up front so per-run
# discovery doesn't shell out to cargo every time.
echo "[baseline] building LSP server (release)..."
(cd server && cargo build --release --quiet)
echo "[baseline] building sensors (release)..."
(cd tools/sensors && cargo build --release --quiet)

lsp_binary="$repo_root/server/target/release/kotlin-analyzer"
sensor_binary="$repo_root/tools/sensors/target/release/sensors"
export KA_BIN="$lsp_binary"

# 2) Pick positions deterministically.
out_dir="$repo_root/perf/baselines/$tag/$project_name"
mkdir -p "$out_dir"
positions_file="$out_dir/positions.tsv"

echo "[baseline] picking $positions_count positions in $project_root..."
python3 scripts/sensor-pick-positions.py \
    "$project_root" \
    --count "$positions_count" \
    --seed "$positions_seed" \
    > "$positions_file"

actual_positions=$(grep -cv '^#' "$positions_file" || true)
echo "[baseline] picked $actual_positions positions; manifest at $positions_file"

# 3) Capture run metadata.
git_sha="$(git -C "$repo_root" rev-parse HEAD)"
git_status="$(git -C "$repo_root" status --porcelain | wc -l | tr -d ' ')"
project_git_sha=""
if git -C "$project_root" rev-parse HEAD >/dev/null 2>&1; then
    project_git_sha="$(git -C "$project_root" rev-parse HEAD)"
fi
sensor_version="$("$sensor_binary" --help >/dev/null 2>&1 && echo unknown || echo unknown)"

cat > "$out_dir/meta.json" <<JSON
{
    "tag": "$tag",
    "project_name": "$project_name",
    "project_root": "$project_root",
    "project_git_sha": "$project_git_sha",
    "kotlin_analyzer_git_sha": "$git_sha",
    "kotlin_analyzer_dirty_files": $git_status,
    "positions_count": $actual_positions,
    "positions_seed": "$positions_seed",
    "burst_count": $burst_count,
    "warmup_bursts": $warmup_bursts,
    "scenarios": ["tight-burst", "medium-burst", "loose-burst"],
    "host": "$(uname -n)",
    "os": "$(uname -s)-$(uname -r)",
    "ran_at_utc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
JSON
echo "[baseline] meta written to $out_dir/meta.json"

# 4) Loop over scenario x position.
slugify() {
    local p="$1"
    p="${p%.kt}"
    p="$(basename "$p")"
    # Replace anything not [A-Za-z0-9-] with -.
    echo "$p" | tr -c 'A-Za-z0-9-' '-' | sed 's/--*/-/g; s/^-//; s/-$//'
}

scenarios=(tight-burst medium-burst loose-burst)
total_runs=$(( ${#scenarios[@]} * actual_positions ))
run_idx=0
start_epoch=$(date +%s)

for scenario in "${scenarios[@]}"; do
    scenario_dir="$out_dir/$scenario"
    mkdir -p "$scenario_dir"

    pos_idx=0
    while IFS=$'\t' read -r abs_path line char rel_path; do
        # Skip comment / header lines.
        case "$abs_path" in '#'*|'') continue ;; esac
        pos_idx=$((pos_idx + 1))
        run_idx=$((run_idx + 1))
        slug="$(slugify "$rel_path")"
        out_jsonl="$scenario_dir/$(printf 'pos%02d' "$pos_idx")-${slug}-L${line}C${char}.jsonl"

        elapsed=$(( $(date +%s) - start_epoch ))
        echo "[baseline] [$run_idx/$total_runs] elapsed=${elapsed}s scenario=$scenario pos=$pos_idx file=$rel_path L${line}C${char}"

        if ! "$sensor_binary" completion-burst \
                --project-root "$project_root" \
                --target-file  "$abs_path" \
                --line "$line" \
                --character "$char" \
                --scenario "$scenario" \
                --burst-count "$burst_count" \
                --warmup-bursts "$warmup_bursts" \
                --jsonl-output "$out_jsonl" \
                > "$scenario_dir/$(printf 'pos%02d' "$pos_idx")-${slug}-L${line}C${char}.stdout" \
                2> "$scenario_dir/$(printf 'pos%02d' "$pos_idx")-${slug}-L${line}C${char}.stderr"; then
            echo "  [warn] sensor exited non-zero (see .stderr); continuing"
        fi
    done < "$positions_file"
done

elapsed=$(( $(date +%s) - start_epoch ))
echo "[baseline] done in ${elapsed}s; outputs under $out_dir"
