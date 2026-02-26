#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

cargo build --target wasm32-wasip2 "$@"
cp target/wasm32-wasip2/debug/kotlin_analyzer.wasm extension.wasm
echo "extension.wasm updated"
