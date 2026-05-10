---
id: sid-3k7o
status: in_progress
deps: []
links: []
created: 2026-05-07T15:38:48Z
type: bug
priority: 2
assignee: Jens Kouros
tags: [build, sidecar, gradle, dx]
---
# assembleRuntimePayloads silently skips re-runs after source change

The Gradle task `assembleRuntimePayload<kotlinVersion>` in
`sidecar/build.gradle.kts` (the per-version task that
`assembleRuntimePayloads` aggregates) declares `outputs.dir(runtimeDir)`
but does not declare any inputs. With no inputs and an existing output
directory, Gradle's UP-TO-DATE check considers the task complete on
every subsequent invocation — the `doLast` block (which deletes and
re-copies launcher + payload + runtime classpath jars) is skipped.

Symptom (observed during ser-leba): edits to `sidecar/src/main/...` are
compiled into `build/libs/sidecar-<version>.jar` by the regular `jar`
task, but `build/runtime/<kotlin-version>/payload/sidecar-impl.jar`
keeps the OLD timestamp and OLD bytes. The Rust LSP launches from the
runtime payload, so editor sessions silently run the pre-edit sidecar
even after `./gradlew assembleRuntimePayloads` reports BUILD SUCCESSFUL.
Only `--rerun-tasks` (or a full clean) refreshes the payload.

This is a real DX footgun — it cost a full debug round during ser-leba
to track down "why is my new code not running" only to discover the
task was UP-TO-DATE'd into oblivion. Anyone shipping sidecar changes
hits this. The docs entry at `docs/development-commands.md` says
"Re-run this task after any change under sidecar/src/" — which is
correct as guidance but doesn't work because the task itself silently
no-ops.

## Design

Two fixes worth considering. Pick one (or both):

1. Declare proper inputs on the runtime task. The doLast block reads:
   - `tasks.named<Jar>("jar").get().archiveFile`
   - `launcherJar.get().archiveFile`
   - `configurations.runtimeClasspath.get().resolve()` (or the detached
     versioned configuration for non-default kotlin versions)

   Each of these should be declared via `inputs.file(...)` /
   `inputs.files(...)` so Gradle's UP-TO-DATE check actually reflects
   what the task consumes. With proper inputs, a source edit that
   changes `jar` output content will mark `assembleRuntimePayload<v>`
   as out-of-date and re-execute it. This is the correct fix.

2. Belt-and-braces: also ensure the per-version task is wired so that
   any change in the `jar` task's outputs triggers re-execution — using
   `inputs.file(tasks.named<Jar>("jar").map { it.archiveFile })` (lazy
   provider) is the canonical Gradle pattern.

   If we go with option 1, also revisit whether to delete-then-recreate
   `rootDir` inside `doLast` is still desirable — with proper input
   tracking Gradle could let us use a real `Copy` task type with
   incremental copy instead of a `doLast` action. That's a bigger
   refactor though; option 1 is the minimal fix.

Test plan:
- Edit a string in any sidecar source file
- Run `./gradlew assembleRuntimePayloads`
- Verify `build/runtime/<v>/payload/sidecar-impl.jar` has a fresh
  timestamp AND that grepping the new string in the unzipped class
  finds it.

Out of scope: switching the task from `doLast` to a typed `Copy`/`Sync`
task — separate refactor.

## Acceptance Criteria

- Editing any file under sidecar/src/main and running
  `./gradlew assembleRuntimePayloads` (without --rerun-tasks) refreshes
  build/runtime/<kotlin-version>/payload/sidecar-impl.jar (new mtime,
  new content reflecting the source change).
- Re-running the same task with no source changes still reports
  UP-TO-DATE — the task remains incremental, just correctly so.
- docs/development-commands.md note about "Re-run this task after any
  change under sidecar/src/" can be left as-is OR shortened — once the
  task is correct, the reminder becomes redundant.
