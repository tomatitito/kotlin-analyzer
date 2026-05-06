---
id: ka-xjd7
status: open
deps: [ka-rw1x, ka-j4dc]
links: [ka-aa4k, ka-rd05, ka-t7gp, ka-rr3u, ka-g342]
created: 2026-05-05T19:51:54Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion, benchmark, analysis]
---
# Capture a real-project completion performance baseline and prioritize bottlenecks

After granular metrics and real-project benchmark support exist, run the benchmark against at least one representative real Kotlin project and record the latency breakdown. Use the data to validate or reprioritize existing hypotheses such as eager completion item detail rendering, broad Analysis API scope queries, auto-import indexing, background analysis head-of-line blocking, and JSON/bridge overhead.

This ticket is intentionally about measurement and decision-making, not implementing the next optimization. It should produce an actionable profile and update the epic/child tickets if the data contradicts current assumptions.

## Acceptance Criteria

- A benchmark run against a named real project is recorded with environment details and raw/summary results.
- The report identifies the top completion latency contributors by stage and scenario.
- Existing child tickets under ka-36lk are reprioritized or amended based on the profile.
- New follow-up optimization tickets are created if the dominant bottleneck is not covered by existing work.
- The report includes target latency thresholds for interactive completion and notes whether each scenario meets them.
