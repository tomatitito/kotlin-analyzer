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
**Visual verification in Zed (use `my-zed` to see LSP logs and JSON-RPC messages in the terminal):**
- [x] Verify BasicConstructs.kt / Example.kt shows no squiggly lines (verified: 0 diagnostics on clean file)
- [ ] Verify TypeMismatch.kt shows red squiggly lines
- [ ] Verify UnusedVariable.kt shows warning indicators
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
- [x] Clear error messages for common failures:
  - [x] No JVM found (shows ERROR via LSP showMessage)
  - [ ] JVM version too old
  - [x] Gradle extraction failed (shows WARNING via LSP showMessage)
  - [x] Sidecar crash notification (already shows in logs)
  - [ ] Out of memory suggestion
  - [x] Sidecar JAR not found (shows WARNING via LSP showMessage)
- [ ] Zed status bar integration
- [x] Graceful degradation when sidecar is down (returns empty results)

#### Verification Gates
- [x] Test sidecar crash recovery (immediate cancellation working)
- [ ] Test with no JVM installed
- [ ] Test with missing/corrupted sidecar JAR
- [ ] Test no-build-system fallback

#### Publishing
- [ ] Publish extension to Zed registry
- [x] Create GitHub release with binaries (v0.2.0)
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
1. ~~**Create v0.2.0 release**~~ — Done. See [v0.2.0 release](https://github.com/tomatitito/kotlin-analyzer/releases/tag/v0.2.0)
2. ~~**Documentation sync and build instructions**~~ — Done. README updated, pre-commit hook added.

### Must Have (Blocking v1.0)
1. ~~Error handling for missing JVM/JAR~~ — Already implemented (clear error messages shown to user via LSP showMessage)
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
- Integration tests (39 sidecar tests, all passing)
- Performance targets met
- v0.2.0 release created (stable baseline)
- Documentation and build instructions updated
- Error handling for missing JVM/JAR (user-facing messages via LSP)
- Zero compiler warnings
- Enhanced hover: package/container info, documentation from library symbols (KDoc + Javadoc)
- Rich annotation hover: meta-annotations (@Target, @Retention, @MustBeDocumented, @Component), supertypes, source origin
- Annotation entry hover: special PSI walk-up handler for `KtAnnotationEntry` resolves to annotation CLASS
- Library symbol hover: classId-based package resolution, origin-based source identification (kotlin-stdlib, JAR files)
- Code actions: refactoring actions (add explicit type, convert expression/block body), line-level diagnostic matching
- Logging cleanup: per-request logs moved to debug level, only lifecycle events at info
- Spring Boot annotation support (classpath extraction + multi-module parser fixes)
- Extension always passes `--log-file /tmp/kotlin-analyzer-server.log` for debugging
- Server logs PID and CLI args on startup for diagnostics
- Bridge lock contention fix in `did_close` handler
- Build script corrected to use `wasm32-wasip2` target
- Binary install documented as symlink (required for sidecar JAR discovery)
- Project root discovery: server walks up from rootUri to find actual project root (fixes incorrect classpath/source root when Zed passes a deep source directory)
- Startup race condition: `analyze_document` no longer logs WARN when bridge is initializing (expected during startup)
- Integration tests fixed: proper channel-based LSP message handling (notifications vs requests), all tests pass
- `did_save` handler: triggers re-analysis on save (was missing, caused tower-lsp WARN spam)
- Logging: remaining per-request INFO logs (analyze_document raw response, code_action response) moved to DEBUG

## Success Criteria for v1.0

- [x] All core LSP features work
- [x] Performance meets targets
- [x] Clear error messages for common failures (JVM, JAR, Gradle, sidecar crash)
- [ ] Published to Zed extension registry
- [ ] Works on macOS and Linux

## Timeline

Target: Complete remaining Must Have tasks for v1.0 release by end of February 2026

## Notes

- Spring Boot annotation support completed and moved to `plans/completed/spring-boot-annotations.md`
- Advanced LSP features (call hierarchy, semantic tokens) deferred to post-v1
- Multi-module and Kotlin Multiplatform support deferred to v2
