# Build, Package, and Distribution

This document describes how kotlin-analyzer is built, packaged, and distributed to end users through the Zed extension registry.

---

## 1. Target Platforms

| Target | Rust Triple |
|--------|-------------|
| macOS ARM | `aarch64-apple-darwin` |
| Linux x64 | `x86_64-unknown-linux-gnu` |

Both targets are built and tested on every CI run.

---

## 2. Release Artifact Structure

Each release produces one archive per platform:

```
kotlin-analyzer-{version}-{target}.tar.gz
```

Archive contents:

```
kotlin-analyzer-0.1.0-aarch64-apple-darwin/
├── kotlin-analyzer          # Rust binary (platform-specific)
└── sidecar-runtimes/        # Launcher + versioned JVM runtime payloads
    ├── 2.2.0-RC2/
    └── 2.2.21/
```

Each runtime directory contains a `manifest.json`, a small launcher jar, and a payload classpath with the sidecar implementation plus the Kotlin compiler and Analysis API artifacts for that Kotlin line. Build this layout with `./gradlew assembleRuntimePayloads`.

The runtime manifest now also records:

- `kotlinVersion`
- `mainClass`
- `analyzerVersion`
- `targetPlatform`
- `classpath`

`analyzerVersion` and `targetPlatform` are used during runtime discovery to reject cached payloads built for a different kotlin-analyzer release or platform.

Do **not** embed the runtime payloads inside the Rust binary via `include_bytes!()`. They are large and versioned independently; embedding them would bloat the binary, slow compilation, and prevent runtime selection per project. Ship them alongside the binary in the archive.

### Runtime Cache

At runtime, the server also maintains a local sidecar cache outside the release archive. The default cache root is platform-specific:

- macOS: `~/Library/Caches/kotlin-analyzer/runtimes/<analyzer-version>/<target-platform>/`
- Linux: `${XDG_CACHE_HOME:-~/.cache}/kotlin-analyzer/runtimes/<analyzer-version>/<target-platform>/`
- Windows: `%LOCALAPPDATA%\\kotlin-analyzer\\runtimes\\<analyzer-version>\\<target-platform>\\`

The cache root can be overridden with `KOTLIN_ANALYZER_RUNTIME_CACHE_DIR`.

When a project requests a Kotlin version that is not currently bundled but is available from a provision source, the server copies that runtime into the cache and reuses it offline on later startups. Cache installs are staged into a temporary directory and then atomically renamed into place so partial installs are not treated as valid runtimes.

Provision source directories can be supplied with `KOTLIN_ANALYZER_RUNTIME_SOURCE_DIRS` as an OS path list. In development, the server also checks `sidecar/build/runtime/` automatically when launched from the source tree.

### Runtime Selection UX

At startup, the server logs the requested Kotlin version, the selected runtime version, and the selection reason. When the selected runtime is not an exact match for the project, the server also sends one `window/showMessage` warning:

- same-minor fallback: exact patch unavailable, using the newest available runtime from the same minor line
- cross-minor fallback: exact line unavailable, using the newest bundled runtime and warning that analysis may be inaccurate

Runtime-selection observability currently ships as structured log fields with counter names such as `runtime_selection.exact_match`, `runtime_selection.same_minor_fallback`, `runtime_selection.cross_minor_fallback`, `runtime_selection.default_bundled`, and `runtime_provision.failure`.

---

## 3. JVM Requirement

The JVM sidecar requires **JDK 17 or later**. Kotlin 2.x targets JVM 17 as its minimum bytecode version, and `kotlin-compiler-embeddable` itself requires a JDK 17+ runtime.

### JVM Discovery Order

The Rust binary locates a JVM in this order:

1. `KOTLIN_LS_JAVA_HOME` environment variable (explicit override)
2. `JAVA_HOME` environment variable (standard JDK location)
3. `java` on `PATH` (system default)

At each step, the binary verifies the JVM version is 17+ by running `java -version` and parsing the output.

### When No JVM Is Found

If no suitable JVM is found:

- The LSP server starts normally (Rust binary is independent of JVM)
- An LSP `window/showMessage` notification is sent to Zed with a clear error: `"kotlin-analyzer: JDK 17+ required but not found. Set JAVA_HOME or KOTLIN_LS_JAVA_HOME."`
- Diagnostics are published with a single workspace-level warning explaining the missing JVM
- All semantic features (diagnostics, completion, hover, go-to-def) are unavailable
- Tree-sitter features (highlighting, outline, brackets) continue to work via the extension

### Future Consideration

Bundling a JVM (as JetBrains does with their LSP) would eliminate this requirement. This is deferred — it adds 100-200 MB to the download and complicates the build. Revisit if JVM-not-found becomes a frequent user complaint.

---

## 4. Zed Extension Packaging

The Zed extension is a separate artifact from the LSP binary. It is a Rust crate compiled to WASM by Zed's extension build system.

### Extension Structure

```
zed-kotlin/
├── extension.toml       # Extension metadata, grammar ref, language server declaration
├── Cargo.toml           # Rust cdylib, depends on zed_extension_api
├── src/
│   └── lib.rs           # Extension trait implementation
└── languages/kotlin/
    ├── config.toml      # Language definition
    ├── highlights.scm
    ├── brackets.scm
    ├── outline.scm
    ├── indents.scm
    ├── injections.scm
    ├── overrides.scm
    ├── textobjects.scm
    └── runnables.scm
```

### extension.toml

Declares the language server with a download URL pattern pointing to GitHub releases:

```toml
[language_servers.kotlin-analyzer]
name = "kotlin-analyzer"
languages = ["Kotlin"]
```

### src/lib.rs

Implements the `Extension` trait:

- `language_server_command`: First checks for a `kotlin-analyzer` binary in `~/.local/bin`. If found, uses it directly (this allows developers and package managers to provide their own build). Otherwise, downloads the platform-appropriate archive from GitHub releases on first use, extracts it to Zed's extension work directory, and returns the path to the `kotlin-analyzer` binary.
- `language_server_workspace_configuration`: Passes user settings (java home, compiler flags, formatting preferences) to the LSP as JSON.

### Publishing

The extension is published as a Git repository to the Zed extension registry. Zed clones the repository and builds the WASM from source. The extension version in `extension.toml` tracks the LSP version.

---

## 5. CI/CD Pipeline

### GitHub Actions Workflow

A single workflow handles both continuous integration and releases.

#### Triggers

| Trigger | Action |
|---------|--------|
| Push to `main` | Build + test all platforms, no release |
| Pull request | Build + test all platforms, no release |
| Tag push (`v*`) | Build + test + create GitHub release with archives |

#### Build Matrix

```yaml
strategy:
  matrix:
    include:
      - target: aarch64-apple-darwin
        os: macos-latest
      - target: x86_64-unknown-linux-gnu
        os: ubuntu-latest
```

#### Build Steps

1. **Checkout** repository
2. **Install Rust** toolchain with target triple
3. **Build Rust binary** (`cargo build --release --target $TARGET`)
4. **Build sidecar runtimes** (`./gradlew :sidecar:assembleRuntimePayloads`) — once, on any runner
5. **Run tests** (`cargo test`, `./gradlew :sidecar:test`)
6. **Package archive** — combine Rust binary + `sidecar-runtimes/` into `.tar.gz`
7. **Upload artifact** — attach to GitHub Actions run (for CI) or GitHub release (for tags)

The packaged `sidecar-runtimes/` directory remains the primary bundled source. The local runtime cache is populated lazily on user machines and is not packaged into release archives.

#### Cross-Compilation

For the Linux target when building on macOS:

- Use `cross` (Docker-based cross-compilation) or `cargo-zigbuild` (uses Zig as a C cross-compiler)
- `cargo-zigbuild` is preferred — lighter weight, no Docker dependency, handles glibc version targeting

The sidecar runtimes are platform-independent. Build them once on any runner and include them in all platform archives.

---

## 6. License

**Apache License 2.0.**

This is compatible with all key dependencies:

| Dependency | License | Relationship |
|---|---|---|
| `zed-kotlin` | Apache 2.0 | Forked as the extension starting point |
| `kotlin-compiler-embeddable` | Apache 2.0 | Bundled in the sidecar fat JAR |
| `tower-lsp` (vendored) | MIT OR Apache-2.0 | Vendored as a workspace crate |
| `lsp-types` | MIT OR Apache-2.0 | Rust dependency |
| `tokio`, `serde`, `thiserror` | MIT OR Apache-2.0 | Rust dependencies |

Apache 2.0 includes an explicit patent grant, which is relevant when integrating with compiler internals. It also matches the upstream projects being forked and bundled, avoiding any license-direction compatibility concerns.

The `LICENSE` file in the repository root contains the full Apache 2.0 text. Each source file does not need a license header — the `LICENSE` file at the root applies to the entire repository.

---

## 7. Version Management

The Rust binary and sidecar runtimes share a single version number. This version is the source of truth and appears in:

- `Cargo.toml` (`version = "0.1.0"`)
- `sidecar/build.gradle.kts` (`version = "0.1.0"`)
- `extension.toml` (`version = "0.1.0"`)
- GitHub release tag (`v0.1.0`)
- Release archive filenames (`kotlin-analyzer-0.1.0-aarch64-apple-darwin.tar.gz`)

The pinned Kotlin compiler version (`2.3.10` for v1) is documented in release notes and in the sidecar's `build.gradle.kts`. Bumping the Kotlin compiler version is a deliberate act that requires testing.
