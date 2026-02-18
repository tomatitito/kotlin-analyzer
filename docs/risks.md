# Risk Register

## Risks

| Risk | Likelihood | Impact | Mitigation | Status |
|------|-----------|--------|------------|--------|
| Kotlin Analysis API breaks across versions | High | High | Pin compiler version. Abstract behind a stable interface layer so internals can be swapped without rewriting consumers. Test against new Kotlin releases before upgrading. | Open |
| JetBrains kotlin-analyzerp reaches stable | Medium-High | High | Exit strategy defined (see [competitive-landscape.md](competitive-landscape.md)). Re-evaluate after Milestone 2. Focus development on differentiators: compiler flag support, crash resilience, fast startup. Do not compete on feature breadth. | Open |
| tower-lsp goes fully unmaintained | High (already dormant) | Medium | Evaluate [lsp-server](https://crates.io/crates/lsp-server) as an alternative. Be prepared to fork tower-lsp or switch to lsp-server within Milestone 1, before we accumulate significant tower-lsp-specific code. | Open |
| JVM sidecar startup latency (5-20s) | Certain | Medium | Start the sidecar eagerly on `initialize`. Queue incoming requests and replay them once the sidecar is ready. Show progress notifications to the editor. Cache the project model to disk for faster restarts. | Open |
| Classpath resolution complexity (Gradle edge cases) | Medium | Medium | Start with simple single-module Gradle projects. Provide a manual configuration fallback (`.kotlin-analyzer.json`) for projects where automatic resolution fails. Cache resolved classpath to disk. | Open |
| Sidecar crashes | Medium | Low | Auto-restart with exponential backoff. Rust frontend replays document state to the new sidecar instance. Zed shows a degraded-mode indicator so the user knows semantic features are temporarily unavailable. | Open |
| Zed extension API changes | Low | Medium | Pin `zed_extension_api` version. Follow Zed release notes. Avoid depending on undocumented or unstable extension APIs. | Open |
| K1 API used instead of K2 | N/A (decided) | Critical | Decision made: use the Analysis API (FIR-backed, K2). See [compiler-integration.md](compiler-integration.md). Do not revisit this decision unless the Analysis API proves fundamentally unusable for standalone use. | Resolved |
| Completion implementation complexity | High | High | Completion is months of work, not a checkbox. Scope computation, smart casts, type inference, auto-import resolution, and ranking all require deep compiler integration. Budget accordingly. Defer completion to after diagnostics and navigation are solid and validated. | Open |
| Multi-module memory scaling | Medium | High | This is a v2 concern. Mitigate with lazy loading: only create compiler environments for modules with open files. Expose JVM `-Xmx` as a user-configurable setting. Monitor memory usage in real-world projects during v1 to inform v2 design. | Open |
| User adoption / discoverability | Medium | Medium | Publish to the Zed extension registry. Write a clear README with concrete examples of the compiler flag advantage. Demonstrate the value proposition with real-world projects (e.g., Arrow context parameters, Spring Boot multi-dollar interpolation). | Open |
| Compose plugin not loaded | Medium | Medium | Detect the Compose Gradle plugin in the project model. Load the Compose compiler plugin into the analysis session so that `@Composable` annotations and Compose-specific compiler behavior work correctly. See [compiler-integration.md](compiler-integration.md). | Open |
| No JVM on user machine | Medium | High | Provide clear error messaging when no JVM is detected. Document the JVM requirement prominently in the README and extension description. Consider bundling a JVM in a future release, though this significantly increases binary size. | Open |

## Risk Review Schedule

Risks should be re-evaluated at each milestone:

- **Milestone 1 (Extension Skeleton)**: Review tower-lsp maintenance status. Decide whether to proceed with tower-lsp or switch to lsp-server before writing significant LSP code.
- **Milestone 2 (LSP Walking Skeleton)**: Review JetBrains kotlin-analyzerp status and development velocity. This is the primary decision point for the exit strategy. If JetBrains has shipped stable or announced a timeline, evaluate whether to continue with our LSP or pivot to Option C (Zed wrapper around JetBrains LSP).
- **Milestone 3 (JVM Sidecar)**: Review sidecar startup latency against targets. Validate crash resilience claims with real failure scenarios. Reassess Analysis API stability based on implementation experience.
- **Milestone 4 (Project Model)**: Review classpath resolution coverage against real-world projects. Assess how many projects require the manual `.kotlin-analyzer.json` fallback. Evaluate Compose plugin loading.
- **Milestone 5 (Core Semantic Features)**: Review completion complexity estimate. Measure memory usage on real projects. Reassess JetBrains competitive position with updated data.
- **Milestone 6 (Navigation & Refactoring)**: Review multi-module scaling concerns based on v1 usage patterns. Evaluate user adoption and feedback.

Do not skip risk reviews. The competitive landscape is moving fast enough that assumptions from Milestone 1 may be invalid by Milestone 3.
