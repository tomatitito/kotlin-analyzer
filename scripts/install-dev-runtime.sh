#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

BIN_DIR="${HOME}/.local/bin"
SERVER_BIN="${PWD}/server/target/debug/kotlin-analyzer"
RUNTIME_SRC="${PWD}/sidecar/build/runtime"
RUNTIME_DST="${BIN_DIR}/sidecar-runtimes"

mkdir -p "${BIN_DIR}"

if [[ ! -x "${SERVER_BIN}" ]]; then
  echo "missing server binary: ${SERVER_BIN}"
  echo "build it first with: cargo build --manifest-path server/Cargo.toml"
  exit 1
fi

if [[ ! -d "${RUNTIME_SRC}" ]]; then
  echo "missing runtime layout: ${RUNTIME_SRC}"
  echo "build it first with: (cd sidecar && ./gradlew assembleRuntimePayloads)"
  exit 1
fi

ln -sfn "${SERVER_BIN}" "${BIN_DIR}/kotlin-analyzer"
rm -rf "${RUNTIME_DST}"
cp -R "${RUNTIME_SRC}" "${RUNTIME_DST}"

echo "installed:"
echo "  ${BIN_DIR}/kotlin-analyzer -> ${SERVER_BIN}"
echo "  ${RUNTIME_DST}"
