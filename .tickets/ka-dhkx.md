---
id: ka-dhkx
status: closed
deps: []
links: [ka-wkai]
created: 2026-05-06T20:46:38Z
type: feature
priority: 3
assignee: Jens Kouros
tags: [observability, logging, completion, lsp, extension, config]
---
# Configurable metrics log file location

Replace ka-wkai's repo-walk auto-discovery with an explicit, user-controlled path for the per-session completion-metrics log file.

Today the LSP server walks up from `current_exe()` looking for `extension.toml` and writes the metrics file under that repo's `logs/` directory. That works for the dev build but silently disables the file for any binary installed outside the source tree (release artefacts, `cargo install`, Homebrew bottles), and there's no way for a user with an installed binary to opt in. Implicit "where am I on disk" discovery is also surprising — users have to read the source to know what controls the path.

This ticket replaces that with two explicit knobs. If neither is set, no file is written.

## Settled decisions

- **Precedence**: `KOTLIN_ANALYZER_METRICS_DIR` env var first; Zed LSP setting `completionMetricsDir` second. Env wins for ad-hoc debugging without editing settings.json. No third fallback — the repo-walk path is removed.
- **Setting shape**: sibling of the existing `completionMetrics: bool`, not a discriminated union. Two camelCase keys at the top of the kotlin-analyzer LSP settings block:
  ```json
  {
    "completionMetrics": true,
    "completionMetricsDir": "~/code/kotlin-analyzer/logs"
  }
  ```
- **Both required**: `completionMetricsDir` without `completionMetrics: true` is a no-op (no file written). The bool remains the on/off gate; the dir setting is meaningless without it.
- **Transport extension → server**: env var, consistent with how `KOTLIN_ANALYZER_COMPLETION_METRICS=1` is already plumbed in src/lib.rs. The extension reads `completionMetricsDir`, expands `~` if present, and re-exports it as `KOTLIN_ANALYZER_METRICS_DIR`. No new CLI flag.
- **Path resolution**: leading `~` expands to `$HOME`. Otherwise treated as a literal absolute or relative-to-cwd path. No further variable interpolation.
- **Failure mode**: if the configured path can't be created (permission denied, read-only fs), the server logs a single info-level line `metrics file disabled: cannot create <path>: <reason>` and continues running normally. No silent fallback — users notice typos this way.
- **Filename**: unchanged from ka-wkai — `kotlin-analyzer-<UTC-timestamp>.log` with `YYYY-MM-DDTHH-MM-SSZ`.
- **Removed code**: ka-wkai's `find_repo_root()`, the `REPO_MARKER` constant, and the `extension.toml`-walk path are deleted. The metrics layer setup in server/src/main.rs becomes a straight env-var read.

## Implementation hints

- Zed extension (src/lib.rs): in `language_server_command`, read the LSP setting via the existing `lsp_settings` helper, expand `~` (the WASM sandbox can read `HOME` from `worktree.shell_env()`), and call `Self::set_env_var(&mut env, "KOTLIN_ANALYZER_METRICS_DIR", &expanded)`. Only set it if `completionMetrics` is also true — keeps the env clean.
- LSP server (server/src/main.rs): replace `find_repo_root()` and the build_metrics_layer's `repo_root.join("logs")` with a single `std::env::var("KOTLIN_ANALYZER_METRICS_DIR")` lookup. If empty/unset, skip the metrics layer entirely.
- Tests: keep the existing `metrics_enabled_via_env` tests, add new tests for the dir resolver — env-set / env-unset / env-empty / env-with-tilde-already-expanded-by-extension.
- Tilde expansion happens in the extension, not the server, so the server only sees fully-resolved paths. That keeps the server's logic minimal and avoids duplicate `~` handling.

## Known limitations (not blocking)

- One LSP server process serves multiple worktrees. If two worktrees configure different `completionMetricsDir` values, the value from whichever worktree initialised first wins for the lifetime of the server. Document this behaviour in docs/sensors.md; don't try to solve it.

## Acceptance Criteria

- LSP server resolves metrics file location from `KOTLIN_ANALYZER_METRICS_DIR` only. If unset or empty, no file is written. The `extension.toml` repo-walk discovery from ka-wkai is removed from server/src/main.rs.
- Zed extension reads `completionMetricsDir` from LSP settings, expands a leading `~` to `$HOME` from `worktree.shell_env()`, and exports the result as `KOTLIN_ANALYZER_METRICS_DIR` — but only when `completionMetrics: true` is also set. Setting the dir without enabling completionMetrics is a no-op.
- Env var takes precedence over the LSP setting: if a user has both set, the extension uses the user's setting only when no env var is already in the inherited shell environment.
- Missing intermediate directories under the configured path are created via `std::fs::create_dir_all`. If creation fails, the server logs `metrics file disabled: cannot create <path>: <reason>` once at info level and continues running. No fallback path.
- Filename remains `kotlin-analyzer-<UTC-timestamp>.log` (`YYYY-MM-DDTHH-MM-SSZ`), regardless of the directory.
- Unit tests cover: env unset → no metrics layer, env set to existing dir → file created in that dir, env set to dir with missing parents → dirs created and file appears, env set to unwritable path → graceful info line, no panic.
- docs/sensors.md updated with: new `completionMetricsDir` setting, env-var precedence, multi-worktree caveat, and removal of the repo-walk fallback.
- Manual verification with a release-installed binary: set `completionMetricsDir` in Zed settings, restart LSP, trigger completions, verify the file lands at the configured path.
- The `logs/.gitkeep` file added by ka-wkai stays — the repo's own `logs/` directory remains a sensible default location *that the user explicitly opts into* via the setting, not via auto-discovery.
