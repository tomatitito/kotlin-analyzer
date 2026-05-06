---
id: ka-wkai
status: in_progress
deps: []
links: [ka-rr3u, ka-g342, ka-dhkx]
created: 2026-05-06T20:28:51Z
type: task
priority: 2
assignee: Jens Kouros
tags: [observability, logging, completion, lsp, extension]
---
# Persist completion metrics to per-session log files in the analyzer repo

When `completionMetrics` is enabled, the LSP server currently emits the `target=completion_metrics` tracing lines as part of the same `tracing` writer that handles every other log line — and the Zed extension routes the whole stream to a single hardcoded `/tmp/kotlin-analyzer-server.log`. That file is shared across sessions, lives outside the repo, and gets clobbered by general LSP noise; it is not useful for tracking completion latency over time.

Move the structured completion-metrics logs into the kotlin-analyzer source repo so they accumulate as a versioned record of perf history.

## Settled decisions

- **Scope**: only the `target=completion_metrics` tracing target lands in the new file. General LSP logs continue to go wherever they go today (stderr / `--log-file` if set), so ad-hoc debugging is unaffected.
- **Location**: `<kotlin-analyzer-repo>/logs/`, centralised (one place for all sessions across all served projects). Not under `<served-project>/.kotlin-analyzer/`.
- **Layout**: one file per editor session. Filename `kotlin-analyzer-YYYY-MM-DDTHH-MM-SSZ.log` (UTC, sortable, filesystem-safe).
- **Path discovery**: the LSP server resolves its own binary via `std::env::current_exe().canonicalize()` and walks up looking for a repo marker (e.g. `extension.toml` at the root). The Zed extension stays dumb — it only sets `KOTLIN_ANALYZER_COMPLETION_METRICS=1` (already done in src/lib.rs after ka-rr3u). No new Zed setting required.
- **Fallback**: if the repo can't be located (downloaded release binary placed somewhere unrelated), the metrics file is silently not created. Server keeps running normally and structured metrics still go to stderr like today.
- **Backwards compatibility**: none. The hardcoded `/tmp/kotlin-analyzer-server.log` in src/lib.rs:50 is replaced. General logs still go to that path or wherever the user configures via existing `--log-file`.
- **Auto-cleanup**: none in v1. Files accumulate; the user prunes manually.
- **Gitignore**: not added — `logs/` files are committed to git on purpose, so the perf-history is visible in PRs and reviewable across machines.

## Implementation hints

- `tracing-subscriber` supports composable layers. Add a second `tracing_subscriber::fmt::Layer` with `with_filter(EnvFilter::new("completion_metrics=info"))` and a non-blocking file writer pointed at the per-session path. Keep the existing stderr layer as-is.
- Path discovery in `server::main`: `let exe = std::env::current_exe()?.canonicalize()?;` then walk parents until one contains `extension.toml`. Cache the result for the session.
- The Zed extension change is just removing the `/tmp` log_file lines and (if any) anything that no longer applies. The `KOTLIN_ANALYZER_COMPLETION_METRICS=1` env var plumbing landed in ka-rr3u/follow-up and stays.
- Use `tracing_appender::non_blocking` so the metrics writer doesn't block the LSP request loop. Drop the guard at server shutdown.

## Acceptance Criteria

- When `completionMetrics` is enabled (env var or LSP setting), each kotlin-analyzer session writes a new file at `<kotlin-analyzer-repo>/logs/kotlin-analyzer-<UTC-timestamp>.log`. The file contains exactly the lines emitted to the `completion_metrics` tracing target, one record per line (`completion timing` and `completion superseded`). No other tracing targets appear in this file.
- General LSP logs still flow to stderr (and to the path passed via `--log-file` if any), unchanged.
- The hardcoded `/tmp/kotlin-analyzer-server.log` reference in `src/lib.rs` is removed.
- When the LSP binary cannot find the repo root (e.g. installed as a release artefact outside the source tree), no file is created and the server continues running. A single info-level log line on stderr explains "metrics file disabled: no repo root found".
- A short `docs/sensors.md` (or `docs/lsp-protocol.md`) update mentions the new log layout and links to it.
- `logs/` directory exists in the repo with at least a `.gitkeep` so the path is stable; logs are *not* gitignored. Existing `.gitignore` entries unchanged.
- Manual verification: open Zed against any project with `completionMetrics: true`, type to trigger a few completions, verify a fresh `logs/kotlin-analyzer-<timestamp>.log` appeared with non-empty completion_metrics lines and no general LSP noise.

## Open Questions Deferred to Implementer

- Exact composition of the layered tracing setup (per-target filter on a global subscriber vs separate subscriber crate features).
- Repo-root marker — `extension.toml` is the obvious choice, but any unique top-level file works. Pick one and document it.
- Whether to also include the sidecar process's own `completion_metrics` lines if those ever start being emitted via Rust (currently they're parsed off stderr by the sensor; this ticket does not change that).
