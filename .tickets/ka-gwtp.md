---
id: ka-gwtp
status: open
deps: [ka-2pai]
links: [ka-quxz, ka-2pai, ka-jnj8]
created: 2026-03-28T21:36:35Z
type: feature
priority: 3
assignee: Jens Kouros
external-ref: TOM-68
parent: ka-7dbe
tags: [pebble, phase2, spring, needs-design]
---
# Pebble Phase 2: expand Spring MVC coverage

Phase 2. Expand static Spring MVC extraction so more conventional projects work without adding deep dynamic analysis.

## Acceptance Criteria

- Most conventional Spring MVC plus Pebble projects work without code changes.
- Unsupported cases still return empty results rather than incorrect navigation.
