---
id: ka-j4dc
status: open
deps: []
links: []
created: 2026-05-05T19:51:54Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion, benchmark, real-project]
---
# Run completion benchmarks against a real Kotlin project

The current completion benchmark uses small synthetic snippets in the gradle-spring-web fixture. That is useful for regression tests, but it does not reproduce the latency profile of a real codebase with a large classpath, many source files, and real Spring/Kotlin idioms.

Extend the benchmark workflow so it can run against a real project checked out temporarily or linked from a local path. The benchmark should avoid vendoring a large project into the repo: use a cache directory, an explicit --project-root override, or a symlink such as .awb/perf-project when available.

The workflow should prepare/read the real project model, initialize the sidecar with that project's source roots/classpath/compiler flags, and run realistic completion scenarios in existing source files or injected temporary edits. It should be possible to use this for local profiling without committing the checked-out project.

## Acceptance Criteria

- Benchmark runner accepts a real project root via CLI/property/env var and documents the expected setup.
- If no local project is provided, the runner can optionally clone or update a configured public Kotlin project into an ignored cache/temp directory, or clearly skips with instructions.
- The real project checkout/symlink/cache path is gitignored and no third-party project contents are committed.
- Benchmark scenarios exercise at least member completion, non-member prefix completion, empty-prefix scope completion, import completion, and after-edit completion in the real project context.
- Results include project identity, commit/path, source file count, classpath size, warmup/iteration counts, total latency stats, and metrics-stage summaries when ka metrics are enabled.
- The workflow is callable from Gradle or a documented script and can be reproduced by another developer.
- Synthetic fixture benchmarks remain available for fast regression checks.
