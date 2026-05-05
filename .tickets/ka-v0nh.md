---
id: ka-v0nh
status: open
deps: []
links: []
created: 2026-05-05T12:41:11Z
type: chore
priority: 2
assignee: Jens Kouros
tags: [gradle, sidecar, build]
---
# Fix Gradle 9 deprecation in sidecar runtime payload assembly

Running `cd sidecar && ./gradlew assembleRuntimePayloads` succeeds but reports deprecated Gradle features. With `--warning-mode all`, Gradle reports:

```
The CopyProcessingSpec.setFileMode(Integer) method has been deprecated. This is scheduled to be removed in Gradle 9.0. Please use the filePermissions(Action) method instead. Consult the upgrading guide for further information: https://docs.gradle.org/8.12/userguide/upgrading_version_8.html#unix_file_permissions_deprecated
```

This appears during sidecar build configuration/runtime payload assembly and will make the build incompatible with Gradle 9.0.

## Acceptance Criteria

- `cd sidecar && ./gradlew assembleRuntimePayloads --warning-mode all` completes without Gradle deprecation warnings.
- Any use of deprecated `CopyProcessingSpec.setFileMode(Integer)`/`fileMode = ...` is replaced with the Gradle 8+ `filePermissions { ... }` API or removed if coming from obsolete configuration.
- The runtime payload layout and executable permissions, if any, remain unchanged.
