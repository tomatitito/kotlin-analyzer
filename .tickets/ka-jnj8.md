---
id: ka-jnj8
status: closed
deps: []
links: [ka-quxz, ka-gwtp, ka-2pai]
created: 2026-03-30T12:40:01Z
type: bug
priority: 2
assignee: Jens Kouros
external-ref: TOM-75
parent: ka-7dbe
tags: [pebble, bug, references, definition, templates]
---
# Pebble real-world navigation: @pebvariable hints, for-loop aliases, and collection element member references

Manual verification in /Volumes/sourcecode/meta-breuninger/entdecken/reco/outfit-genie shows that Kotlin↔Pebble definition/references still miss common real-world patterns even though the MVP fixture passes. The current index handles direct Spring model bindings and simple root variables, but outfit-genie templates rely heavily on Pebble doc comments (`{# @pebvariable name=... type=... #}`), `{% for x in xs %}` loop aliases, collection element member access like `outfit.path`, and typed method calls such as `farbartikel.marke()`. These are not currently modeled, so Find All References and Go to Definition fail conservatively in editor testing.

## Acceptance Criteria

- Parse `{# @pebvariable name="..." type="..." #}` hints into the Pebble index and expose typed root-variable facts for definition/reference queries.
- Track simple `{% for item in items %}` aliases so `item` inherits the element type of `items` when recoverable from Spring facts or `@pebvariable` hints.
- Support Kotlin↔Pebble definition/reference for simple member access on typed variables and loop aliases (for example `outfit.path`, `outfit.relativePath`, `farbartikel.marke()`).
- Add regression fixtures/tests covering the failing outfit-genie-style patterns.
- Manual verification in `my-zed` against outfit-genie confirms the repros now navigate correctly or fail with explicitly documented limitations.
