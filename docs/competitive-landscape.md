# Competitive Landscape

## 1. JetBrains kotlin-lsp

- **Repository**: [Kotlin/kotlin-lsp](https://github.com/Kotlin/kotlin-lsp)
- **Status**: Experimental, pre-alpha, actively developed (multiple commits per day as of Feb 2026)
- **Community**: ~54 contributors, 2.9k stars, 81 open issues
- **Releases**: 3 public releases (May 2024, Aug 2024, Dec 2024), latest v261.13587.0

This is our primary competitor and the most significant threat to the project's relevance.

### What they ship

- Highlighting, navigation, completion, diagnostics, rename, code actions, hover, inlay hints
- As of Dec 2024: zero-dependencies platform-specific builds that bundle the JVM -- no external JDK required
- JVM-only Kotlin Gradle projects (same scope limitation as ours)

### Development velocity

The repository sees multiple commits per day. At this pace, a stable release could arrive within months. This is not a distant threat -- it is an active one.

### Implications

JetBrains has functionally unlimited resources relative to this project. They will always ship more features, fix more edge cases, and attract more contributors. We cannot compete on breadth. Any strategy that assumes we will match JetBrains on feature completeness is a losing strategy.

---

## 2. fwcd/kotlin-language-server (Deprecated)

- **Repository**: [fwcd/kotlin-language-server](https://github.com/fwcd/kotlin-language-server)
- **Status**: Deprecated, succeeded by JetBrains' official effort
- **Implementation**: Written in Kotlin, embeds compiler via K1 internal APIs

### Features

Completion, hover, go-to-definition, references, symbols, diagnostics.

### Lessons

- K1 compiler internals are usable for building an LSP but break across Kotlin versions.
- Maintaining compatibility with compiler internals is a constant tax.
- The project's deprecation demonstrates that a community-maintained Kotlin LSP cannot sustain itself indefinitely against an official effort.

---

## 3. fwcd/tree-sitter-kotlin

- **Repository**: [fwcd/tree-sitter-kotlin](https://github.com/fwcd/tree-sitter-kotlin)
- **Status**: Actively maintained as of Feb 2026 (multiple commits per day)
- **Community**: 39 contributors, last tagged release 0.3.8 (Aug 2024), active main branch
- **Recent work**: Range-until operator, suspend modifiers, tree-sitter 0.24.x bindings

This is the grammar we depend on (not a competitor). The active maintenance is good news -- it reduces the risk that we need to maintain a grammar fork.

No explicit Kotlin 2.x tracking has been confirmed. Multi-dollar interpolation and context parameters support is unconfirmed. This needs verification but is a lower risk than initially expected given the project's activity level.

---

## 4. Our Positioning

### Honest differentiators

These are areas where we have a real, defensible advantage:

- **Compiler flag support** (`-Xcontext-parameters`, `-Xmulti-dollar-interpolation`, etc.) -- JetBrains' LSP currently does not honor project-level compiler flags from `build.gradle`. This causes false-positive diagnostics for any feature gated behind an `-X` flag. This is our strongest differentiator, but only for as long as JetBrains does not fix it.
- **Faster startup** -- Rust frontend starts instantly; JVM sidecar launches in background. JetBrains' LSP requires full JVM initialization before any response. This is measurable and meaningful for editor restarts.
- **Lower memory (single module)** -- Our target is ~300 MB vs JetBrains' 1-2 GB. Needs verification against JetBrains' latest builds, which may have improved.
- **Crash resilience** -- Rust process survives JVM sidecar crashes, auto-restarts with state replay. JetBrains' LSP dies entirely on crash. This matters for long editing sessions.
- **Zed-first UX** -- Runnables, text objects, and editor-specific configuration. These are tree-sitter features, not LSP features, but they contribute to the overall experience.

### Weak differentiators (do not rely on these)

- **"Lightweight"** -- JetBrains is actively optimizing. Their zero-dependency builds already eliminate the external JDK requirement. This gap is closing.
- **"No bundled JRE"** -- JetBrains now bundles the JRE with zero external dependencies. We actually have a *worse* story here: we require users to have a JVM installed on their machine.
- **Feature completeness** -- We will never match JetBrains' resources. Any roadmap that depends on feature parity is unrealistic.

---

## 5. Exit Strategy

If JetBrains ships a stable kotlin-lsp before we reach Phase 2, we need a plan. Pretending this will not happen is not a strategy.

### Option A: Lightweight alternative (recommended primary path)

Keep the crash-resilient Rust + JVM sidecar architecture. Focus on fast startup and low memory for resource-constrained environments (CI containers, low-RAM laptops, remote dev servers). Position as the lightweight Kotlin LSP for users who do not want a 1-2 GB process.

### Option B: Contribute upstream

Upstream tree-sitter query improvements (textobjects.scm, runnables.scm) to `zed-extensions/zed-kotlin`. Contribute extension UX work. Accept that our LSP is not needed and redirect effort to improving the ecosystem.

### Option C: Zed extension wrapper (recommended fallback)

Use JetBrains' kotlin-lsp as the backend. Build a Zed extension that adds Zed-specific UX on top: runnables, text objects, better configuration, and compiler flag passthrough. This preserves the Zed extension work while dropping the custom LSP.

### Recommendation

Build with Option A as the primary path. Keep Option C as a fallback. Re-evaluate after Milestone 2. If JetBrains ships stable and our differentiators have eroded, pivot to Option C without sunk-cost resistance.

---

## 6. Upstream Contribution Strategy

Rather than maintaining a full fork of `zed-extensions/zed-kotlin`, consider contributing tree-sitter query improvements (textobjects.scm, runnables.scm) upstream. Our fork then only needs the language server swap, which reduces ongoing maintenance burden.

This also hedges against the exit strategy: if we pivot to Option C, the tree-sitter work is already upstream and benefits the community regardless.
