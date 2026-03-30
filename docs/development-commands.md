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
