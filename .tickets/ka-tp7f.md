---
id: ka-tp7f
status: open
deps: []
links: []
created: 2026-05-05T11:39:03Z
type: bug
priority: 2
assignee: Jens Kouros
parent: ka-sizl
---
# Add missing Rendering test coverage

Three test gaps identified during code review of the Rendering.view() chain support:

1. isPureLuxury variable resolution — the fixture chains .modelAttribute("isPureLuxury", farbartikel.markeninfo.pureLuxury) (a two-level dot-qualified value expression) and rendering-carousel.peb has {{ isPureLuxury }}, but no test asserts this resolves. Distinct path from single-level and simple-reference cases.

2. Bare Rendering.view("x").build() — no test covers a view() chain without any modelAttribute calls.

3. Rendering.status(204).modelAttribute(...) — the status-only negative test has no model attributes. A variant with model attributes chained onto a view-less rendering would lock in the correct silent-skip behavior.
