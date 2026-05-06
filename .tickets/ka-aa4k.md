---
id: ka-aa4k
status: open
deps: [ka-kyvv]
links: [ka-rd05, ka-t7gp, ka-rr3u, ka-xjd7]
created: 2026-05-05T15:25:36Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion]
---
# Implement completionItem/resolve for lazy detail computation

Currently `resolve_provider: false` (server.rs:1005) means every completion item must include its full detail (rendered return type) and `additionalTextEdits` (auto-import insertion) upfront. For 100+ candidates, this means 100+ type renderings and 20 import-edit computations — all before the user has even looked at the list.

With `completionItem/resolve`, the initial response returns just `label`, `kind`, and `insertText`. When the user highlights a specific item in the menu, the editor sends a resolve request for that single item, and only then do we compute the return type string and import edit. This turns O(N) type rendering into O(1) — only the selected item pays the cost.

Rust side: set `resolve_provider: true`, add a `completion_resolve` handler that forwards to sidecar.
Sidecar side: add a `completionItem/resolve` handler that renders the return type and computes import insertion for one item.

## Acceptance Criteria

- server.rs declares resolve_provider: true
- completionItem/resolve handler exists in Rust and sidecar
- Initial completion response omits detail and additionalTextEdits
- Resolve request returns detail and additionalTextEdits for one item
- Completion menu shows type info when item is highlighted
