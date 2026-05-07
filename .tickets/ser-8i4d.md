---
id: ser-8i4d
status: closed
deps: []
links: []
created: 2026-05-07T20:30:38Z
type: feature
priority: 2
assignee: Jens Kouros
parent: ser-8btk
tags: [observability, build, server, dx]
---
# Embed git commit in kotlin-analyzer binary and stamp metrics log

Performance analysis of `.logs/kotlin-analyzer-*.log` files needs to
attribute each session to the source revision that built the binary —
otherwise an "improvement" between two runs could just mean an old
binary against a newer Zed, or a forgotten `assembleRuntimePayloads`.
Without a stamp the script can only show the filename timestamp.

Adds:

- `server/build.rs` — runs `git rev-parse --short=12 HEAD` and
  `git status --porcelain` at compile time, exposes them via
  `cargo:rustc-env=GIT_COMMIT_HASH=…` and `GIT_DIRTY=…`.
  `cargo:rerun-if-changed=.git/HEAD` and `.git/index` re-stamp the
  binary on commit/index changes.
- `server/src/main.rs` — emits one `tracing::info!(target: METRICS_TARGET, …)`
  startup line carrying `version`, `commit`, `dirty`, `pid`. Lands at
  the top of every per-session metrics file.
- `.sensors/log-analysis.py` — parses that line for commit/dirty/version
  and falls back to `git -C <repo> log --before=<file mtime> -1
  --format=%h` for older logs that predate the stamp. Renders a
  `commit` column in every aggregate table and the single-file summary.
  Annotation legend: `+` = dirty tree at build, `~` = approximate
  git-mtime guess.

## Acceptance Criteria

- `cargo build --release -p kotlin-analyzer-server` produces a binary
  whose stderr/metrics-log startup line includes
  `commit="<short SHA>"` and `dirty="<true|false>"`.
- A fresh metrics file written by that binary shows
  `commit_source: embedded` in `sensors run log-analysis --json`.
- An older metrics file (no startup line) shows
  `commit_source: git-mtime` and a `~`-prefixed commit.
- All existing server tests still pass after the build.rs addition.
