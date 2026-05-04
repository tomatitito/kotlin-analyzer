---
id: ka-v3hp
status: closed
deps: []
links: [ka-xmsu]
created: 2026-04-15T07:56:27Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-ixtv
tags: [sidecar, tests, spring, kotlin]
---
# Add Spring/Kotlin 2.3 regression fixture for false-positive diagnostics

Add a minimal sidecar-facing regression fixture that captures the Spring Boot + Kotlin 2.3 project shape behind the recently observed false positives. The fixture should be intentionally small, but it must cover the constructs that previously triggered absurd diagnostics such as unresolved `build()` and unresolved string literal content like `variante1`.

The goal is not to mirror the full production project. The goal is to preserve the relevant semantic shape in a focused fixture that runs reliably in automated tests.

## Acceptance Criteria

- A minimal regression fixture exists in the analyzer test suite, preferably at the sidecar integration-test level.
- The fixture covers `ResponseEntity.ok().build()`.
- The fixture covers `WebClient.Builder.build()`.
- The fixture covers multi-dollar interpolation strings like `$$"${app.foo}"`.
- The fixture covers enum or string literal content like `"variante1"`.
- The chosen test asserts zero diagnostics for the covered files.
- The fixture location and test layer are clear from the implementation and easy to extend with future false-positive cases.

## Notes

- Prefer a minimal, purpose-built fixture over copying a large real-world project.
- If needed, split coverage between a reusable fixture project and focused assertions in sidecar integration tests.
- This ticket is about regression prevention, not about fixing stale diagnostic cache behavior in the Rust server.
- If the work reveals a gap that belongs to runtime/version alignment rather than fixture coverage, track that separately under `ka-xmsu`.

## Out of Scope

- Reproducing the entire original application layout.
- Broad changes to runtime selection or sidecar language-version policy.
- Rust-server diagnostic cache fixes.
