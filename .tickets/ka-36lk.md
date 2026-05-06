---
id: ka-36lk
status: open
deps: []
links: []
created: 2026-05-05T15:25:16Z
type: epic
priority: 1
assignee: Jens Kouros
tags: [perf, completion]
---
# Critical Kotlin analyzer performance

Track the critical performance work needed to make kotlin-analyzer feel responsive on real Kotlin projects, with completion latency as the first and most visible problem area.

The initial hypothesis was that completions were unusably slow mainly because each request rebuilt the compiler session after every edit. ka-kyvv removed that rebuild from the completion path, but real-world completion is still too slow. This epic now covers the broader performance effort: instrument the Rust/server and JVM sidecar hot paths, benchmark against representative real projects rather than only synthetic fixtures, identify the dominant latency contributors, and implement the highest-impact optimizations. Target: bring interactive completion latency to a reliably usable range, ideally <300ms for warmed real-project scenarios.
