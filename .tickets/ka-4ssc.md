---
id: ka-4ssc
status: closed
deps: []
links: [ka-fm0u]
created: 2026-05-04T12:34:55Z
type: feature
priority: 2
assignee: Jens Kouros
parent: ka-4qf5
tags: [kotlin, imports, lsp, rust, transport]
---
# Implement workspace/applyEdit transport for sidecar-initiated edits

Add a server-to-client workspace/applyEdit request path so the sidecar can push edits (like auto-import insertions) to the editor without the user triggering a code action.

## Current state

The Rust server currently has no workspace/applyEdit transport. The sidecar can return edits in response to code-action requests, but cannot proactively push edits after re-analysis. ka-fm0u documents a failed attempt at testing this path.

## Design

- Add a workspace/applyEdit sender to the Rust server that the bridge can invoke.
- The bridge should expose a method that the sidecar calls with a label and a set of text edits.
- The server sends workspace/applyEdit to the client and handles the ApplyWorkspaceEditResponse.
- Include a document version in the edit so stale edits can be detected and dropped.
- Keep the Rust changes minimal — this is transport plumbing, not policy about when to send edits.

## Dependency direction

Per architecture constraints, bridge must not depend on state or project. The applyEdit sender should live in server or bridge. Server registers a callback/channel that bridge can invoke to emit edits, preserving the downward dependency flow:

  server → bridge → jsonrpc

## Code areas

- src/server.rs: register workspace/applyEdit capability, wire up sending
- src/bridge.rs or src/bridge/mod.rs: expose a channel or callback for sidecar-initiated edits
- src/jsonrpc.rs: if needed for request framing (server→client requests)

## Acceptance criteria

- Server declares workspace/applyEdit capability in initialize response.
- Bridge can emit a set of text edits with a document URI and version.
- Server sends a well-formed workspace/applyEdit request to the client.
- Stale edits (wrong document version) are dropped before sending.
- Integration test covers a round-trip: sidecar emits edit → server sends applyEdit → client receives it.
- Subsumes and closes ka-fm0u once stable test coverage is in place.
