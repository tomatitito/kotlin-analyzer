# kotlin-analyzer

A Kotlin language server for the [Zed](https://zed.dev) editor, providing semantic analysis powered by the Kotlin Analysis API.

## Features

- **Diagnostics** - Real-time error and warning highlighting from the Kotlin compiler
- **Hover** - Type information and KDoc documentation on hover
- **Go to Definition** - Navigate to declarations of variables, functions, and classes
- **Completion** - Type-aware completions for members, scope variables, and keywords
- **Signature Help** - Parameter hints when typing function arguments
- **Find References** - Locate usages of a symbol within the current file
- **Formatting** - Code formatting via ktfmt or ktlint
- **Syntax Highlighting** - Tree-sitter based highlighting for Kotlin 2.x syntax
- **Text Objects** - Function, class, comment, and parameter text objects
- **Runnables** - Detect `fun main()` and `@Test` annotated functions

## Requirements

- **Zed** editor ([zed.dev](https://zed.dev))
- **JDK 17+** - Required for the analysis sidecar

## Installation

### From GitHub Releases (current)

Download the [latest release](https://github.com/tomatitito/kotlin-analyzer/releases) and install manually:

```bash
tar -xzf kotlin-analyzer-v0.2.0-macos-aarch64.tar.gz
cp kotlin-analyzer ~/.local/bin/
cp sidecar.jar ~/.local/bin/
```

Then symlink the Zed extension:

```bash
git clone https://github.com/tomatitito/kotlin-analyzer.git
ln -sfn "$(pwd)/kotlin-analyzer" \
  ~/Library/Application\ Support/Zed/extensions/installed/kotlin-analyzer
```

Restart Zed to load the extension.

### From Zed Extension Registry (planned)

Once published to the Zed extension registry:

1. Open Zed
2. Open the Extensions panel (`cmd+shift+x`)
3. Search for "Kotlin Analyzer"
4. Click Install

## Configuration

Configure via Zed's settings (`settings.json`):

```json
{
  "lsp": {
    "kotlin-analyzer": {
      "settings": {
        "javaHome": "/path/to/jdk",
        "compilerFlags": ["-Xcontext-parameters"],
        "formattingTool": "ktfmt",
        "formattingStyle": "google",
        "formattingPath": "/path/to/ktfmt",
        "sidecarMaxMemory": "512m"
      }
    }
  }
}
```

### Settings Reference

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `javaHome` | `string` | auto-detected | Path to JDK 17+ installation |
| `compilerFlags` | `string[]` | `[]` | Additional Kotlin compiler flags (e.g., `-Xcontext-parameters`) |
| `formattingTool` | `string` | `"ktfmt"` | Formatter: `"ktfmt"`, `"ktlint"`, or `"none"` |
| `formattingStyle` | `string` | `"google"` | Formatting style (ktfmt: `"google"`, `"kotlinlang"`, `"meta"`) |
| `formattingPath` | `string` | PATH lookup | Custom path to formatter binary |
| `sidecarMaxMemory` | `string` | `"512m"` | JVM max heap size for the analysis sidecar |

### Manual Project Configuration

For projects without Gradle or Maven, create a `.kotlin-analyzer.json` in the project root:

```json
{
  "sourceRoots": ["src/main/kotlin"],
  "classpath": ["libs/some-library.jar"],
  "compilerFlags": ["-Xcontext-parameters"],
  "kotlinVersion": "2.1.20",
  "jdkHome": "/path/to/jdk"
}
```

## Architecture

The system consists of three components:

- **Zed extension** (Rust/WASM) - Registers Kotlin language support, manages tree-sitter queries for syntax highlighting, and downloads/launches the LSP binary.
- **Rust LSP server** - Handles the LSP protocol, document state management, project model extraction, formatting, and sidecar process lifecycle.
- **JVM sidecar** - Runs the Kotlin Analysis API (K2/FIR-backed) in standalone mode for semantic analysis. Communicates with the Rust server via JSON-RPC over stdin/stdout.

```
Zed <--LSP--> Rust binary <--JSON-RPC--> JVM sidecar (Analysis API)
```

This split architecture provides instant startup (Rust binary starts in milliseconds while the JVM warms up in the background), crash isolation (a sidecar crash doesn't take down the LSP server), and clean separation of concerns.

## Troubleshooting

### No JDK found

kotlin-analyzer requires JDK 17+. It searches for Java in this order:

1. `KOTLIN_LS_JAVA_HOME` environment variable
2. `JAVA_HOME` environment variable
3. `java` on PATH

### No diagnostics or completions

- Verify the project has a supported build system (Gradle or Maven)
- Check that `build.gradle.kts` / `pom.xml` is in the workspace root
- For non-standard projects, create a `.kotlin-analyzer.json` configuration
- Check Zed logs for errors during project resolution

### Formatting not working

- Ensure `ktfmt` or `ktlint` is installed and on PATH
- Or set `formattingPath` to the formatter binary's absolute path
- Set `formattingTool` to `"none"` to disable formatting

## Building from Source

### Prerequisites

- Rust 1.70+ (via [rustup](https://rustup.rs))
- JDK 17+ (for sidecar)
- wasm32-wasip1 target (`rustup target add wasm32-wasip1`)

### Build

```bash
# Rust LSP binary
cd server && cargo build --release

# JVM sidecar
cd sidecar && ./gradlew shadowJar

# Zed extension (WASM)
cargo build --target wasm32-wasip1
```

### Install locally

```bash
cp server/target/release/kotlin-analyzer ~/.local/bin/
cp sidecar/build/libs/sidecar-all.jar ~/.local/bin/sidecar.jar

# Symlink the Zed extension (only needed once)
ln -sfn "$(pwd)" ~/Library/Application\ Support/Zed/extensions/installed/kotlin-analyzer
```

### Test

```bash
# Unit tests
cd server && cargo test

# Full smoke test
cargo build && (cd sidecar && ./gradlew shadowJar) && cd server && cargo test
```

## Documentation

| Document | Description |
|----------|-------------|
| [Documentation Index](docs/INDEX.md) | Overview and links to all design documents |
| [Architecture](docs/architecture.md) | System design, module structure, process lifecycle |
| [Compiler Integration](docs/compiler-integration.md) | Analysis API usage, compiler flags |
| [LSP Protocol](docs/lsp-protocol.md) | LSP framework, bridge protocol, backpressure |
| [Distribution](docs/distribution.md) | Cross-platform builds, packaging, CI/CD |
| [Testing](docs/testing.md) | Testing strategy, fixtures, CI pipeline |

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.
