# kotlin-analyzer v1 Roadmap - Remaining Tasks

This roadmap contains only the remaining tasks for the v1 release. Completed items have been moved to `plans/completed/v1-roadmap-completed.md`.

## Outstanding Tasks

### Milestone 2: Verification Gates

#### Rust Toolchain Checks
- [ ] Set up pre-commit hooks using `prek`:
  - [ ] `cargo fmt --check` — reject unformatted code
  - [ ] `cargo clippy -- -D warnings` — reject linter warnings
  - [ ] `cargo test` — reject failing tests
- [ ] Verify pre-commit hooks fire correctly

#### Sidecar Build and Tests
- [ ] Add sidecar checks to `prek` pre-commit hooks:
  - [ ] `cd sidecar && ./gradlew test` — reject failing sidecar tests

#### End-to-End Diagnostics Verification
**CLI-level verification:**
- [ ] Test TypeMismatch.kt produces expected diagnostics
- [ ] Test BasicConstructs.kt produces zero diagnostics
- [ ] Test compiler-flags files WITHOUT flags produce errors
- [ ] Test compiler-flags files WITH flags produce no errors

**Visual verification in Zed:**
- [ ] Verify TypeMismatch.kt shows red squiggly lines
- [ ] Verify UnusedVariable.kt shows warning indicators
- [ ] Verify BasicConstructs.kt shows no squiggly lines

### Milestone 2: Testing
- [ ] Integration tests with mock sidecar
- [ ] Architecture dependency test (verify module dependencies)
- [ ] Diagnostic verification against all fixture files
- [ ] Test harness for EXPECT-ERROR/EXPECT-WARNING headers

### Milestone 3: Remaining Items

#### Formatting Enhancements
- [ ] .editorconfig change detection and invalidation
- [ ] Map .editorconfig properties for ktfmt (max_line_length → --max-width)
- [ ] Respect per-section overrides

#### Kotlin-Specific Support
- [ ] Compose compiler plugin loading in sidecar environment

#### Request Management
- [ ] Backpressure handling: bounded channels, supersede stale requests
- [ ] Request prioritization: cancellation > sync > diagnostics > completion

#### Testing
- [ ] Integration tests with real sidecar + Kotlin project fixtures
- [ ] Test semantic features against expected results
- [ ] Snapshot tests for LSP responses
- [ ] Add Coroutines.kt and TestClass.kt fixtures
- [ ] Add LargeFile.kt and DeepNesting.kt edge cases

#### Verification Gate
- [ ] Visual verification of all semantic features in Zed
- [ ] Configuration changes take effect properly
- [ ] Format command works with various formatters

### Milestone 4: Polish + Release

#### Performance
- [ ] Measure baseline performance metrics
- [ ] Set concrete performance targets
- [ ] Optimize against targets
- [ ] Set up CI performance tracking

#### Error Handling and UX
- [ ] Clear error messages for common failures:
  - [ ] No JVM found
  - [ ] JVM version too old
  - [ ] Gradle extraction failed
  - [ ] Sidecar crash notification
  - [ ] Out of memory suggestion
- [ ] Zed status bar integration
- [ ] Graceful degradation when sidecar is down

#### Verification Gates
- [ ] Test with no JVM installed
- [ ] Test with missing/corrupted sidecar JAR
- [ ] Test no-build-system fallback
- [ ] Test sidecar crash recovery

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

## Priority Order

1. **Testing Infrastructure** - Complete test harnesses and verification
2. **Error UX** - Ensure clear failure messages
3. **Performance Baseline** - Measure before optimizing
4. **Publishing** - Get v1.0 released

## Success Criteria for v1.0

- All verification gates pass
- Performance meets targets
- No silent failures (all errors are user-visible)
- Works on macOS and Linux
- Published to Zed extension registry
- Documented for users

## Timeline

Target: Complete remaining tasks for v1.0 release by end of February 2026

## Notes

- Spring Boot annotation support tracked separately in `plans/active/spring-boot-annotations.md`
- Advanced LSP features (call hierarchy, semantic tokens) deferred to post-v1
- Multi-module and Kotlin Multiplatform support deferred to v2
