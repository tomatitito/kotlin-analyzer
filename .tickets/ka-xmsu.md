---
id: ka-xmsu
status: closed
deps: []
links: [ka-v3hp]
created: 2026-04-15T07:56:27Z
type: feature
priority: 2
assignee: Jens Kouros
parent: ka-ixtv
tags: [sidecar, kotlin, runtime]
---
# Improve Kotlin project version and sidecar runtime alignment

The analyzer currently hardcodes Kotlin 2.1 language/api settings in parts of sidecar session setup and may analyze newer projects via cross-minor runtime fallback. Improve alignment so newer Kotlin projects are analyzed with configuration closer to the project model.

This ticket is specifically about version alignment behavior:
- how the Rust side chooses a sidecar runtime for a project Kotlin version,
- how the sidecar configures language/api version from project metadata,
- and how fallback behavior is made explicit, tested, and less surprising.

## Acceptance Criteria

- The current runtime-selection and language-version behavior is documented by tests.
- Sidecar session setup no longer hardcodes an outdated language/api version when project version information is available, or there is a deliberate documented fallback path.
- Runtime fallback behavior for unsupported project Kotlin versions is explicit in tests and/or user-facing warnings.
- The implementation makes it clear which parts of version alignment are solved now versus still constrained by bundled runtime availability.

## Notes

- Prefer an incremental improvement over a broad redesign.
- If this work naturally separates into runtime selection and sidecar language-version configuration, split follow-up work into additional tickets.
- This ticket is about semantic/runtime correctness, not about stale diagnostic cache clearing in the Rust server.

## Out of Scope

- Rust-server diagnostic cache lifecycle fixes.
- Broad regression-fixture work unrelated to version alignment.
- Reworking the full packaging/distribution model for sidecar runtimes unless required by the chosen fix.
