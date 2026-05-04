---
id: ka-3maw
status: closed
deps: [ka-feaw]
links: []
created: 2026-03-28T21:36:35Z
type: task
priority: 2
assignee: Jens Kouros
external-ref: TOM-70
parent: ka-7dbe
tags: [pebble, mvp, definition, references]
---
# Pebble MVP: integrate Kotlin-Pebble definition and references

Phase 1, step 5. Use the Pebble index and Spring cross-language index to surface real LSP navigation across Kotlin and Pebble documents.

## Acceptance Criteria

- From `{{ user.name }}`, definition can reach the Kotlin producer for `user` and the `name` declaration in simple cases.
- Kotlin find-all-references includes Pebble usages when indexed.
- Template includes, extends, and imports navigate correctly.
