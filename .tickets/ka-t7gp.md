---
id: ka-t7gp
status: open
deps: [ka-kyvv]
links: []
created: 2026-05-05T15:25:51Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion]
---
# Pass name predicates to Analysis API scope queries

The scope and member completion queries pass `{ true }` as the name filter predicate, meaning they fetch every symbol in scope before filtering client-side by prefix. For example, typing `fo` after a dot still fetches all 50+ methods on the receiver type, then discards 90% of them.

The Analysis API's `getCallableSignatures` and `getClassifierSymbols` accept a `KaScopeNameFilter` predicate — a `(Name) -> Boolean` lambda that runs inside the API and skips non-matching symbols entirely. Passing a prefix filter eliminates most candidates before they're ever materialized as `KaSymbol` objects, saving both allocation and type resolution costs.

Locations to fix:
- BridgeCompletion.kt:285 — `typeScope.getCallableSignatures { true }`
- BridgeCompletion.kt:320 — `typeScope.getClassifierSymbols { true }`
- BridgeCompletion.kt:411 — `compositeScope.callables` (use `callables(nameFilter)`)
- BridgeCompletion.kt:443 — `compositeScope.classifiers` (use `classifiers(nameFilter)`)

When prefix is empty (just typed `.`), keep `{ true }` to return all members.

## Acceptance Criteria

- Scope queries pass name filter predicate when prefix is non-empty
- Completion with a typed prefix fetches fewer symbols (verify via logging)
- Empty-prefix completion (e.g., right after dot) still returns all members
- No change in completion result correctness
