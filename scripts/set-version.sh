#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cargo run --quiet --manifest-path "$ROOT_DIR/tools/version-sync/Cargo.toml" -- set "$@"
