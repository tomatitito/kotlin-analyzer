---
id: ka-b4yk
status: open
deps: []
links: []
created: 2026-04-02T11:02:05Z
type: bug
priority: 1
assignee: Jens Kouros
tags: [lsp, completion, hover, sidecar, transport]
---
# Fix end-to-end completion transport regressions

Track the remaining issues after the dirty-buffer completion/hover fixes. Current failures: 1) server/tests/integration_test.rs:test_completion_works_immediately_after_did_change_on_disk_file still returns an empty completion array end-to-end even though the sidecar dirty-buffer regressions now pass. 2) In my-zed on /Volumes/sourcecode/meta-breuninger/entdecken/reco/outfit-genie, opening PythiaBestandteilDtoToDomainConverterTest.kt caused the sidecar to log 'Failed to parse JSON-RPC request: MalformedJsonException' and exit; subsequent hover requests were canceled. Investigate server->sidecar JSON-RPC framing/encoding, especially large/non-ASCII didOpen payloads, and reconcile the remaining server integration gap.
