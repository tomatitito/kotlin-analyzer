# Documentation Synchronization and Build Instructions

## Overview

Ensure all documentation is up-to-date and consistent, with clear build instructions in all relevant files. Add a pre-commit hook to keep CLAUDE.md and AGENTS.md synchronized.

## Tasks

### 1. Add/Improve Build Instructions

Current status:
- AGENTS.md and CLAUDE.md already have build instructions under "Verification Commands"
- README.md needs build instructions added

Tasks:
- [ ] Add comprehensive build instructions to README.md
- [ ] Consider renaming "Verification Commands" to "Building and Testing" in AGENTS.md/CLAUDE.md for clarity
- [ ] Add installation instructions (where to put the binaries)

#### Build Instructions Template
```markdown
## Building kotlin-analyzer Locally

### Prerequisites
- Rust 1.70+ (via rustup)
- JDK 11+ (for sidecar)
- Gradle 7+ (for sidecar)

### Build Steps

1. **Build the Rust LSP server:**
   ```bash
   cargo build --release
   ```
   The binary will be at `target/release/kotlin-analyzer`

2. **Build the JVM sidecar:**
   ```bash
   cd sidecar
   ./gradlew shadowJar
   ```
   The JAR will be at `sidecar/build/libs/sidecar-all.jar`

3. **Build the Zed extension (WASM):**
   ```bash
   cargo build --target wasm32-wasip1
   ```
   The WASM file will be at `target/wasm32-wasip1/debug/kotlin_analyzer.wasm`

### Installation

1. **Install the binaries:**
   ```bash
   # Copy to local bin directory
   cp target/release/kotlin-analyzer ~/.local/bin/
   cp sidecar/build/libs/sidecar-all.jar ~/.local/bin/sidecar.jar
   ```

2. **Install the Zed extension (dev mode):**
   ```bash
   # Symlink the extension directory
   ln -sfn "$(pwd)" ~/Library/Application\ Support/Zed/extensions/installed/kotlin-analyzer
   ```

3. **Restart Zed** to load the extension

### Quick Build & Test
```bash
# Full build and test
cargo build --release && \
(cd sidecar && ./gradlew shadowJar) && \
cargo test --features integration
```
```

### 2. Synchronize CLAUDE.md and AGENTS.md

**Good news**: These files are already identical!

Tasks:
1. **Add pre-commit hook** to keep them synchronized
2. **Document the relationship** - Add a note in both files that they must remain identical
3. **Consider using symlink** - Alternative: make one a symlink to the other

### 3. Add Pre-commit Hook for Sync

Create a new pre-commit hook configuration:

```yaml
# In .pre-commit-config.yaml (or prek configuration)
- id: sync-claude-agents
  name: Ensure CLAUDE.md and AGENTS.md are identical
  entry: bash -c 'diff CLAUDE.md AGENTS.md || (echo "CLAUDE.md and AGENTS.md must be identical" && exit 1)'
  language: system
  files: ^(CLAUDE|AGENTS)\.md$
  pass_filenames: false
```

Or as a prek script:
```bash
#!/bin/bash
# .prek/hooks/sync-docs.sh
if ! diff CLAUDE.md AGENTS.md > /dev/null 2>&1; then
    echo "Error: CLAUDE.md and AGENTS.md must have identical content"
    echo "Please ensure both files are synchronized"
    exit 1
fi
```

### 4. Update Documentation

Review and update all docs in `docs/` directory:

- [ ] `docs/INDEX.md` - Ensure index is complete and accurate
- [ ] `docs/architecture.md` - Verify architecture is current
- [ ] `docs/compiler-integration.md` - Check Analysis API details
- [ ] `docs/lsp-protocol.md` - Confirm LSP implementation details
- [ ] `docs/testing.md` - Update with new test infrastructure
- [ ] `docs/distribution.md` - Add v0.2.0 release process

#### Key Updates Needed

1. **docs/testing.md** - Add information about:
   - 14 new sidecar integration tests
   - Test scripts in `scripts/` directory
   - How to run integration tests with `--features integration`

2. **docs/architecture.md** - Document:
   - Request buffering mechanism
   - State watching for startup coordination
   - Crash recovery improvements

3. **docs/lsp-protocol.md** - Update with:
   - Working features (hover, rename, go-to-definition, references)
   - Known issues (Spring Boot annotations)

## Implementation Order

1. **First**: Add build instructions to README.md
2. **Second**: Synchronize CLAUDE.md and AGENTS.md with build instructions
3. **Third**: Add pre-commit hook for synchronization
4. **Fourth**: Update all documentation files

## Testing

- [ ] Verify build instructions work on clean checkout
- [ ] Test pre-commit hook catches differences
- [ ] Confirm all doc links are valid
- [ ] Check that examples in docs still work

## Success Criteria

- [ ] Build instructions present in README.md, CLAUDE.md, AGENTS.md
- [ ] CLAUDE.md and AGENTS.md have identical content
- [ ] Pre-commit hook prevents divergence
- [ ] All documentation reflects current state
- [ ] No broken links or outdated information

## Files to Modify

- `README.md` - Add build instructions
- `AGENTS.md` - Sync with CLAUDE.md
- `CLAUDE.md` - Ensure complete
- `.pre-commit-config.yaml` or `.prek/` - Add sync hook
- `docs/*.md` - Update all documentation files

## Priority

High - Documentation accuracy is critical for development and onboarding. Should be done immediately after v0.2.0 release.
