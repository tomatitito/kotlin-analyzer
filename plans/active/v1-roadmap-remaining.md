# kotlin-analyzer v1 Roadmap - Remaining Tasks

This roadmap contains only the remaining tasks for the v1 release. Completed items have been moved to `plans/completed/v1-roadmap-completed.md`.

## Outstanding Tasks

### Milestone 2: Verification Gates

#### Rust Toolchain Checks
**Note**: Pre-commit hooks are already configured and working (using `prek`), including:
- ✅ cargo fmt --check
- ✅ cargo clippy
- ✅ cargo test
- ✅ sidecar ./gradlew test

These are firing correctly on every commit. No further action needed.

#### End-to-End Diagnostics Verification
**Visual verification in Zed:**
- [ ] Verify TypeMismatch.kt shows red squiggly lines
- [ ] Verify UnusedVariable.kt shows warning indicators
- [ ] Verify BasicConstructs.kt shows no squiggly lines
- [ ] Verify compiler-flags files work correctly with/without flags

### Milestone 3: Remaining Items

#### Formatting Enhancements
- [ ] .editorconfig change detection and invalidation
- [ ] Map .editorconfig properties for ktfmt (max_line_length → --max-width)
- [ ] Respect per-section overrides

#### Kotlin-Specific Support
- [ ] Compose compiler plugin loading in sidecar environment

#### Request Management (Nice to have, not critical for v1)
- [ ] Backpressure handling: bounded channels, supersede stale requests
- [ ] Request prioritization: cancellation > sync > diagnostics > completion

#### Testing
- [ ] Snapshot tests for LSP responses
- [ ] Add Coroutines.kt and TestClass.kt fixtures (require external dependencies)
- [ ] Add LargeFile.kt and DeepNesting.kt edge cases

### Milestone 4: Polish + Release

#### Performance (Current status is good)
Current metrics already meet v1 targets:
- ✅ Time to LSP connection: ~400ms (target < 500ms)
- ✅ Time to first diagnostics: < 5s (target < 15s)
- ✅ Completion latency: < 500ms
- ✅ Hover/go-to-def latency: < 200ms
- ✅ Memory (Rust): ~15 MB (target < 20 MB)
- ✅ Memory (JVM): ~97 MB (target < 512 MB)

Further optimization can be deferred to post-v1.

#### Error Handling and UX
- [ ] Clear error messages for common failures:
  - [ ] No JVM found
  - [ ] JVM version too old
  - [ ] Gradle extraction failed
  - [x] Sidecar crash notification (already shows in logs)
  - [ ] Out of memory suggestion
- [ ] Zed status bar integration
- [x] Graceful degradation when sidecar is down (returns empty results)

#### Verification Gates
- [x] Test sidecar crash recovery (immediate cancellation working)
- [ ] Test with no JVM installed
- [ ] Test with missing/corrupted sidecar JAR
- [ ] Test no-build-system fallback

#### Publishing
- [ ] Publish extension to Zed registry
- [ ] Create GitHub release with binaries
- [ ] Announce availability
- [ ] Set up issue templates
- [ ] Gather initial feedback

## CI/CD Tasks
- [ ] Verify CI runs with `gh run list`
- [ ] Test tag-triggered releases

## Non-blocking Updates
- [ ] Update to latest stable Analysis API artifacts when 2.2.0+ is published

## Priority Order for v1.0

### IMMEDIATE - Before Any Other Changes
1. **Create v0.2.0 release** (see `create-v0.2.0-release.md`)
   - Tag current working version
   - Create GitHub release with binaries
   - Establish stable baseline to return to

2. **Documentation sync and build instructions** (see `documentation-sync.md`)
   - Add build instructions to README.md, AGENTS.md, CLAUDE.md
   - Synchronize CLAUDE.md and AGENTS.md content
   - Add pre-commit hook to enforce synchronization
   - Update all docs/ files to current state

### Must Have (Blocking v1.0)
1. Error handling for missing JVM/JAR
2. Basic Zed status bar integration
3. Publishing to Zed registry
4. GitHub release with v1.0 binaries

### Nice to Have (Can be v1.1)
1. Formatting enhancements (.editorconfig)
2. Compose compiler plugin support
3. Request prioritization
4. Additional test fixtures

### Already Complete
- Core LSP features (hover, rename, go-to-definition, references)
- Request buffering and crash recovery
- Pre-commit hooks
- Integration tests (14 sidecar tests)
- Performance targets met

## Success Criteria for v1.0

- [x] All core LSP features work
- [x] Performance meets targets
- [ ] Clear error messages for common failures
- [ ] Published to Zed extension registry
- [ ] Works on macOS and Linux

## Timeline

Target: Complete remaining Must Have tasks for v1.0 release by end of February 2026

## Notes

- Spring Boot annotation support tracked separately in `plans/active/spring-boot-annotations.md`
- Advanced LSP features (call hierarchy, semantic tokens) deferred to post-v1
- Multi-module and Kotlin Multiplatform support deferred to v2
