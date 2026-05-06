---
id: ka-p0fi
status: closed
deps: []
links: [ka-kyvv]
created: 2026-05-05T20:25:00Z
type: task
priority: 0
assignee: Jens Kouros
parent: ka-36lk
tags: [docs, perf, analysis-api, psi, fir]
---
# Document PSI, FIR, and in-memory/dangling file models

The completion performance investigation now depends on a shared vocabulary for Kotlin's parsed source model and semantic compiler model. Add a concise documentation section explaining PSI, FIR, and the PSI file variants kotlin-analyzer deals with during editor-driven analysis.

Recommended location: `docs/compiler-integration.md`, near the Analysis API / FIR overview. If the section grows too large, split it into a focused doc and link it from `docs/INDEX.md`.

## Content to Add

- Define **PSI** as IntelliJ's parsed source representation: `KtFile`, `KtElement`, declarations, references, and cursor-position lookup.
- Define **FIR** as Kotlin K2's semantic frontend representation used for symbols, types, scopes, diagnostics, and resolution.
- Include a small ASCII diagram showing the relationship between source text, PSI, Analysis API, and FIR, for example:

```text
editor/disk text
      |
      v
Kotlin parser / IntelliJ PSI
      |
      v
KtFile / KtElement at cursor
      |
      v
Analysis API analyze(...) block
      |
      v
FIR-backed semantic model
      |
      v
symbols, types, diagnostics, completion data
```

- Explain the PSI file variants relevant to kotlin-analyzer:
  - Physical/on-disk project PSI: normal source file known to the project/session.
  - In-memory editor PSI/text: current unsaved editor buffer content parsed into a `KtFile` or held as text outside the saved disk file.
  - Shadow-root/session PSI: sidecar-managed copy used to rebuild a coherent standalone Analysis API session.
  - LightVirtualFile-backed PSI: temporary virtual file representation used for editor content.
  - Dangling PSI: temporary PSI file not registered as a normal project source file, but analyzed in the context of a real module/file.
- Explicitly distinguish **in-memory** from **dangling**:
  - In-memory describes where the text/PSI comes from and lives: current editor/server memory rather than the saved disk file.
  - Dangling describes how that PSI participates in analysis: not a normal source-root member, but resolved against the module/context of a real project file.
- Include an example flow for interactive requests:

```text
current editor text
      |
      v
temporary in-memory KtFile
      |
      v
dangling resolution against real Foo.kt's module
      |
      v
completion / hover / signature help
```

- Include a second ASCII diagram showing how these variants relate to each other and where stale/duplicate-file risks can appear.
- Connect the terminology to the performance work:
  - ordinary text edits should not imply a full project/session rebuild;
  - interactive requests such as completion, hover, and signature help are good candidates for current in-memory/dangling PSI against a stable module context;
  - dangling PSI should avoid making FIR see a second real copy of the same source file;
  - full rebuilds should be reserved for structural changes such as new/deleted source files, classpath changes, compiler flag changes, or project model reloads.

## Acceptance Criteria

- Documentation clearly distinguishes PSI from FIR in terms a contributor can use while reading sidecar code.
- Documentation includes an ASCII PSI -> FIR relationship diagram.
- Documentation explains physical, in-memory, shadow-root, LightVirtualFile-backed, and dangling PSI file variants.
- Documentation clearly distinguishes "in-memory" as a storage/origin property from "dangling" as an analysis-context participation property.
- Documentation explains why dangling PSI is a promising model for interactive requests and what correctness risk it is meant to avoid.
- Documentation includes an ASCII diagram of the PSI file variants and notes where duplicate/stale file visibility can happen.
- `docs/INDEX.md` is updated if a new document is created.
- The performance epic or related tickets can refer to this doc instead of redefining PSI/FIR terminology.
