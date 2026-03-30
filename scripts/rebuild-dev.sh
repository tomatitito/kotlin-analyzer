#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ROOT="$(pwd)"
ZED_EXT_DIR="${HOME}/Library/Application Support/Zed/extensions/installed/kotlin-analyzer"
LOCAL_BIN_DIR="${HOME}/.local/bin"
SERVER_BIN="${ROOT}/server/target/debug/kotlin-analyzer"

printf '==> Building sidecar runtime payloads\n'
(
  cd sidecar
  ./gradlew clean assembleRuntimePayloads
)

printf '\n==> Building Rust server\n'
(
  cd server
  cargo build
)

printf '\n==> Building Zed extension WASM\n'
bash scripts/build-extension.sh

printf '\n==> Installing dev symlinks\n'
mkdir -p "${LOCAL_BIN_DIR}"
ln -sfn "${SERVER_BIN}" "${LOCAL_BIN_DIR}/kotlin-analyzer"
mkdir -p "$(dirname "${ZED_EXT_DIR}")"
ln -sfn "${ROOT}" "${ZED_EXT_DIR}"

printf '\nDone.\n'
printf '  server:    %s\n' "${SERVER_BIN}"
printf '  binary:    %s\n' "${LOCAL_BIN_DIR}/kotlin-analyzer"
printf '  extension: %s\n' "${ZED_EXT_DIR}"
printf '\nNext: fully restart my-zed/Zed to pick up the rebuilt sidecar payloads.\n'
