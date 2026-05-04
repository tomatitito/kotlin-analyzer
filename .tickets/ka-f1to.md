---
id: ka-f1to
status: open
deps: []
links: []
created: 2026-03-29T16:17:50Z
type: chore
priority: 2
assignee: Jens Kouros
external-ref: gh-56
tags: [release, ci, versioning]
---
# Improve release workflow: single source of truth and automated releases

The current release flow requires manually updating the version in multiple files and separately pushing a v* tag. This is error-prone and can produce releases whose tag/version does not match the embedded extension, Rust crate, and sidecar/runtime metadata.

## Acceptance Criteria

- Version is edited in only one place.
- Derived versioned files are updated automatically or verified in CI.
- A release can be created without manually pushing a tag.
- Published release tag, archive names, extension version, and sidecar/runtime metadata all match.
- The workflow is documented in docs/distribution.md.


## Notes

**2026-03-29T21:37:08Z**

Implementation outline agreed:

- Introduce a root `VERSION` file as the single source of truth for the release version.
- Add a local version-sync script (for example `scripts/set-version.sh <version>` or equivalent) that:
  - updates `VERSION`
  - updates `Cargo.toml`
  - updates `sidecar/build.gradle.kts`
  - updates `extension.toml`
  - updates any other build/release metadata files required for consistency.
- Add a CI verification script (for example `scripts/check-version-sync.sh`) that fails if the version in derived files does not match `VERSION`.
- Update release packaging to use the current sidecar runtime layout:
  - build `sidecar` with `./gradlew assembleRuntimePayloads`
  - package `sidecar-runtimes/` with the server binary
  - stop shipping the old `sidecar-all.jar`-based release artifact.
- Split/adjust GitHub Actions so normal CI and release automation are separate concerns:
  - CI on PRs and pushes to `main`: build, test, and verify version sync
  - Release workflow on pushes to `main`: detect whether `VERSION` represents an unreleased version.
- Release detection policy:
  - on `main`, read `VERSION`
  - if tag `v<VERSION>` already exists, skip release
  - if tag does not exist, create it automatically in the workflow and publish the release.
- The release workflow should ensure all published metadata matches the same version:
  - git tag `v<VERSION>`
  - GitHub release version/title
  - archive filenames
  - `Cargo.toml`
  - `extension.toml`
  - sidecar/runtime metadata such as `analyzerVersion`.
- Update `docs/distribution.md` to document:
  - `VERSION` as the single source of truth
  - the local version bump/sync script
  - automatic release creation from version detection on `main`
  - automatic tag creation by CI
  - the `sidecar-runtimes/` release artifact layout.

Open implementation details to decide during execution:
- exact script names/interface (`set-version.sh` vs `sync-version.sh`)
- whether to keep release automation in `ci.yml` or move it to a dedicated `release.yml`
- whether to add `workflow_dispatch` as a manual recovery path for failed releases.

**2026-03-29T21:38:15Z**

Concrete implementation plan:

1. Add single-source version file
   - Create root `VERSION` containing the current release version.
   - Initialize it from the currently released project version (`0.4.0` at the time of writing).

2. Add version sync/update script
   - Add a local script under `scripts/` that accepts a version string and updates all required files.
   - Preferred behavior: one command updates `VERSION` and all derived version fields in the repo.
   - Initial required targets:
     - `VERSION`
     - `Cargo.toml`
     - `sidecar/build.gradle.kts`
     - `extension.toml`
   - During implementation, audit for any additional release/version metadata that must be kept in sync.

3. Add version verification script
   - Add a second script under `scripts/` that reads `VERSION` and verifies all derived files match it.
   - The script should exit non-zero on mismatch so it can be reused in CI and release automation.

4. Update CI workflow
   - Modify `.github/workflows/ci.yml` so PRs and pushes to `main` run:
     - existing build/test steps
     - version consistency verification
   - Remove the old tag-push-based release path from `ci.yml` if release automation is moved to a dedicated workflow.

5. Add dedicated release workflow
   - Create `.github/workflows/release.yml` (preferred) triggered on pushes to `main`.
   - Release workflow steps:
     1. checkout repository with sufficient history/tags
     2. read `VERSION`
     3. verify repo versions are in sync
     4. check whether tag `v<VERSION>` already exists locally/remotely
     5. if tag exists, exit successfully without releasing
     6. if tag does not exist:
        - create tag `v<VERSION>` automatically in the workflow
        - push the tag
        - build release artifacts
        - create GitHub release for `v<VERSION>`
   - Optional: add `workflow_dispatch` as a manual recovery/retry entry point.

6. Update release build steps to current packaging model
   - Replace old sidecar release packaging that uses `shadowJar` / `sidecar-all.jar`.
   - Build runtime payloads with:
     - `cd sidecar && ./gradlew assembleRuntimePayloads`
   - Package release archives with:
     - server binary
     - `sidecar-runtimes/` directory
   - Ensure archive names use `VERSION`.

7. Add release consistency checks
   - In the release workflow, verify that published outputs all agree on the same version:
     - `VERSION`
     - git tag `v<VERSION>`
     - archive filenames
     - `Cargo.toml`
     - `extension.toml`
     - sidecar runtime manifests / `analyzerVersion`
   - Fail before publishing if any mismatch is detected.

8. Validate sidecar/runtime metadata flow
   - Confirm that runtime manifests produced by `assembleRuntimePayloads` embed the analyzer version derived from synced project metadata.
   - Add or update tests/checks if needed so regressions are caught automatically.

9. Update documentation
   - Update `docs/distribution.md` to describe:
     - `VERSION` as the single source of truth
     - the local version bump script
     - CI version verification
     - automatic release detection on `main`
     - automatic tag creation by CI
     - `sidecar-runtimes/` artifact packaging.

10. End-to-end verification
   - Test locally where possible:
     - run version sync script against a test version
     - run version verification script
     - build server and sidecar runtime payloads
   - Dry-run workflow logic as much as practical before merging.
   - After merge, verify that a real version bump on `main`:
     - creates tag `v<VERSION>` automatically
     - publishes the expected archives
     - produces matching runtime metadata.
