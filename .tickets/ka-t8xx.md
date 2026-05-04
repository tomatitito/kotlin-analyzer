---
id: ka-t8xx
status: in_progress
deps: []
links: []
created: 2026-04-20T12:00:00Z
type: task
priority: 1
tags: [sidecar, compiler, diagnostics]
---

# Update bundled Kotlin to 2.3.20

## Problem

The sidecar still bundles Kotlin 2.2.0-RC2 for analysis. We want to move the analyzer forward to Kotlin 2.3.20 to pick up the current compiler and Analysis API behavior, including diagnostic changes that remove known false positives.

This ticket is intentionally narrow. Do not add or change any dynamic Kotlin-version detection, runtime selection, or project-version matching logic. The goal is a single bundled Kotlin version update only.

## Acceptance criteria

- [ ] Update the sidecar build and bundled runtime payloads from Kotlin `2.2.0-RC2` to Kotlin `2.3.20`
- [ ] Keep the existing single-bundled-version behavior; no project Kotlin version detection or dynamic runtime selection is introduced
- [ ] The sidecar builds and tests pass against Kotlin `2.3.20`
- [ ] Known false diagnostics caused by the old bundled compiler behavior no longer reproduce under the updated bundle
