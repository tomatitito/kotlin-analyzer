---
id: ka-dry2
status: closed
deps: []
links: []
created: 2026-05-05T16:36:32Z
type: task
priority: 2
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion]
---
# Add completion benchmark script

Create a standalone benchmark that exercises BridgeContext.completion() directly against the gradle-spring-web fixture. Should run multiple scenarios (dot completion, prefix completion, import completion, empty prefix, after-edit) with warmup passes, collect min/avg/p95/max per scenario, and print a summary table. Wire it up as a Gradle task so it can be run with ./gradlew benchmark.
