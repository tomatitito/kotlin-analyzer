---
id: sid-colf
status: open
deps: []
links: [ka-d4n8]
created: 2026-05-20T11:12:37Z
type: bug
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [completion, sidecar, analysis-api, static-scope]
---
# Member completion on class-reference receivers returns only 'Any' members (enums, value classes)

After ka-d4n8 flipped the dangling overlay to PREFER_SELF, instance-receiver member completion is restored (`farbIdInstance.value` → `value` candidate visible). Class-reference receivers — where the receiver is the **class itself**, not an instance — still collapse to the three `Any` members.

`collectMemberCompletions` in `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeCompletion.kt:448-571` resolves the receiver to a type via `receiverExpr.expressionType`, then walks instance scopes only (`receiverType.scope`, then for `KaClassType` also `classSymbol.memberScope`). Neither path is correct for `ClassName.x`:

- Enum class-reference completion (`AnfrageKontext.`): expected `PDS`, `HOMEFEED`, `values()`, `valueOf()`, `entries`. Observed: `toString`/`equals`/`hashCode` only.
- Companion / `@JvmStatic` access (`Foo.create(...)` where `Foo` has a companion factory): expected companion-exposed callables; observed same Any-only collapse.
- Nested-class access (`Outer.Inner`): not currently surfaced.
- Cosmetic side-effect: instance-`Any` callables (`toString`, `equals`, `hashCode`) are served as candidates for a static qualifier where they aren't callable. Skip them when the receiver is a class reference rather than an instance value.

## Evidence

`.logs/kotlin-analyzer-2026-05-20T10-53-24Z.log` records 31 and 33 (with the ka-d4n8 build loaded — every record reports `danglingAnalysisMode:"PREFER_SELF"`):

```
counts:{"memberCandidates":3,"finalItems":3,"sessionRebuilt":0,"danglingFallback":0,...}
fields:{"completionFileMode":"existing_session_source_with_editor_overlay","danglingAnalysisMode":"PREFER_SELF","psiValidationBypass":"true"}
stages includes receiver_detection and member_candidate_collection
```

Both `FarbId.` and `AnfrageKontext.` reached the member-completion path (receiver was detected, member-candidate collection ran) and still returned three candidates. Same session, same file, at line 53 char 24 the receiver was an instance and member completion returned **181** candidates — confirming the overlay/dangling path itself is healthy and the regression is specific to class-reference receivers.

Reproduce live with `sensors run log-analysis --path <log>` — the new "member completions (per-record candidate count)" section flags `<-- ANY-ONLY` rows automatically.

## Approach

Classify the receiver before walking scopes:

- `KtReferenceExpression` whose `mainReference?.resolveToSymbol()` is a `KaClassSymbol` (or `KaClassLikeSymbol`) → treat as **class-reference / static qualifier**, not an instance value. Do not call `receiverExpr.expressionType` for scope walking on this path.
- For class references:
  - Enum classes: enumerate `KaEnumClassSymbol.entries`, plus synthetic `values()` / `valueOf(String)` / `entries`.
  - Class with companion: include the companion's own callables (i.e. members callable via `ClassName.x`), and `Companion` itself as a classifier.
  - `@JvmStatic` annotated companion members already surface via the companion path.
  - Nested classes/objects: include via `classSymbol.staticDeclaredMemberScope.getClassifierSymbols(...)`.
- Skip instance `Any` callables for class-reference receivers.
- Instance-receiver path (current code) is unchanged for backwards compatibility — confirmed working by 181-candidate record on the same session.

## Acceptance criteria

- New integration test: `AnfrageKontext`-shaped enum overlay (e.g. fixture under `test-sources/`), completion at `EnumName.` returns at minimum the enum entries and `values`/`valueOf`/`entries`. Assert `memberCandidates >= 4` and labels include each entry name.
- New integration test: class with companion, `ClassName.companionMethod` overlay returns the companion callable.
- Existing instance-receiver tests remain green (no regression to `farbIdInstance.value`-shaped completion).
- Real-Zed reproduction of the original report: typing `AnfrageKontext.` against MongoOutfitLikeRepository.kt produces a log record with `memberCandidates >= 4` and labels including `PDS` / `HOMEFEED`.

## Out of scope

- Static-extension functions imported via `import Foo.bar` (Kotlin top-level companion extensions) — handle if it falls out for free, otherwise spin a new ticket.
- The `symbolIndex.searchSymbols(prefix, limit = 20)` cap for unimported types (ka-d4n8 "Out of scope" note carried forward).
- The instance-receiver overlay path fixed by ka-d4n8.

## References

- `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeCompletion.kt:448-571` — `collectMemberCompletions`
- ka-d4n8 (closed predecessor; PREFER_SELF flip restored instance-receiver overlays)
- `.logs/kotlin-analyzer-2026-05-20T10-53-24Z.log` records 31, 33 (Any-only) vs 16 (181 candidates)
- Analysis API: https://kotlin.github.io/analysis-api/ — `KaClassSymbol`, `staticDeclaredMemberScope`, `KaEnumEntrySymbol`
