# Command-Based Code Actions

This document defines the first analyzer-owned command contract used by command-bearing code actions.

The canonical machine-readable source is [`protocol/analyzer-commands.json`](../protocol/analyzer-commands.json).

## Supported commands

### `kotlin-analyzer.openTarget`

Arguments are passed as a single JSON object inside the LSP command `arguments` array.

- `targetUri: string` required
- `selection` optional
  - `startLine: number`
  - `startCharacter: number`
  - `endLine: number`
  - `endCharacter: number`

### `kotlin-analyzer.openTestTarget`

Arguments are passed as a single JSON object inside the LSP command `arguments` array.

- `targetUri: string` required
- `selection` optional
  - `startLine: number`
  - `startCharacter: number`
  - `endLine: number`
  - `endCharacter: number`

### `kotlin-analyzer.createAndOpenTestTarget`

Arguments are passed as a single JSON object inside the LSP command `arguments` array.

- `targetUri: string` required
- `targetPath: string` required
- `initialContents: string` required
- `selection` optional
  - `startLine: number`
  - `startCharacter: number`
  - `endLine: number`
  - `endCharacter: number`

## Notes

- `selection` uses standard LSP zero-based positions.
- Rust executes the transport behavior only. Kotlin decides the target URI/path and initial contents.
- Existing-target navigation uses `window/showDocument` for general LSP clients, and the Zed CLI (`my-zed`/`zed`) for Zed because Zed does not handle `window/showDocument`.
- Set `KOTLIN_ANALYZER_ZED_CLI` to override the CLI executable path used for Zed navigation.
