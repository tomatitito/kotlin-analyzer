# Development Commands

## Rust LSP binary

```bash
cargo build                                # compile
cargo test                                 # unit tests
cargo test --features integration          # real sidecar integration tests
cargo run -- --log-level debug             # run the server on stdin/stdout
```

## JVM sidecar

```bash
cd sidecar && ./gradlew assembleRuntimePayloads   # build launcher + versioned runtime payloads
cd sidecar && ./gradlew test               # unit tests
```

`assembleRuntimePayloads` writes the `launcher/` + `payload/` layout under `sidecar/build/runtime/<kotlin-version>/` that the Rust LSP launches as its JVM subprocess. See [Distribution → Runtime Layout](distribution.md#runtime-layout-launcher--payload) for the layout and [Distribution → What `assembleRuntimePayloads` does](distribution.md#what-assembleruntimepayloads-does) for the step-by-step. Re-run this task after any change under `sidecar/src/` — `./gradlew jar` alone updates `build/libs/`, which the Rust LSP does not read.

## Sensors

Sensors are runnable observability probes. The intended top-level entrypoint is:

```bash
sensors run
```

Until the sensor CLI exists, run the completion latency sensor through Gradle:

```bash
cd sidecar && ./gradlew benchmark
cd sidecar && ./gradlew benchmark --args="--warmups 3 --iterations 30 --jsonl-output build/completion-sensor.jsonl"
```

The Rust-based `completion-burst` sensor (ka-g342) lives at `tools/sensors/`:

```bash
cargo run --manifest-path tools/sensors/Cargo.toml -- completion-burst --help
cargo run --manifest-path tools/sensors/Cargo.toml -- completion-burst \
    --project-root /path/to/project --target-file /path/to/project/src/Foo.kt \
    --line 42 --character 8 --scenario tight-burst --regression-guard
```

See [docs/sensors.md](sensors.md) for scenario presets and output shape.

For before/after performance baselines against a real Kotlin project,
the driver script captures all three burst scenarios across a
deterministically-sampled set of completion positions and writes the
results under `perf/baselines/<tag>/<project>/`:

```bash
# Capture once before touching kotlin-analyzer.
scripts/sensor-baseline.sh /path/to/project project-name --tag 2026-05-06-pre-aa4k

# Re-run with the same project + the same --positions-seed (default
# kotlin-analyzer-perf-baseline) after a perf-affecting change to get a
# directly-comparable after-baseline.
scripts/sensor-baseline.sh /path/to/project project-name --tag 2026-05-06-post-aa4k
```

See [perf/baselines/README.md](../perf/baselines/README.md) for layout, comparison hints, and the full flag list.

Editor completion timing logs are quiet by default. Enable structured Rust and sidecar completion timing with:

```bash
KOTLIN_ANALYZER_COMPLETION_METRICS=1 cargo run -- --log-level info
```

## Zed extension

```bash
bash scripts/build-extension.sh            # build WASM component (wasip2) and copy to extension.wasm

# symlink the extension into Zed's local extensions directory (macOS)
ln -sfn "$(pwd)" ~/Library/Application\ Support/Zed/extensions/installed/kotlin-analyzer

# symlink the server binary so the extension can find the built sidecar runtimes
ln -sf "$(pwd)/server/target/debug/kotlin-analyzer" ~/.local/bin/kotlin-analyzer
```

After symlinking, rebuild and restart Zed to pick up changes. The symlinks only need to be created once.

**Important**: The binary at `~/.local/bin/kotlin-analyzer` must be a symlink (not a copy) so that the server can resolve its path back to the source tree and find the versioned sidecar runtimes under `sidecar/build/runtime/`.

**Trust**: This dev build of Zed (`my-zed`) requires worktree trust before starting language servers. If the LS doesn't start automatically, use `Cmd+Shift+P` → "Restart Language Server" to trigger it manually.

## Tree-sitter queries

```bash
tree-sitter test                           # run query tests
tree-sitter highlight tests/fixtures/kotlin/correct/BasicConstructs.kt
```

## Full smoke test

```bash
cargo build && (cd sidecar && ./gradlew assembleRuntimePayloads) && cargo test
```

## Release/version sync

```bash
./scripts/set-version.sh 0.5.0
./scripts/check-version-sync.sh
./scripts/check-version-sync.sh --include-generated-runtime-manifests
```

These shell entrypoints delegate to the Rust helper under `tools/version-sync/`. `VERSION` is the single source of truth for release versioning.
