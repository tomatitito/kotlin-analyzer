# Rust LSP Hover Handler Investigation Report

**Date**: 2026-03-05
**Investigator**: Claude Code Agent
**Task**: Investigate Rust hover handler and response format

## Summary

The Rust LSP hover handler is **correctly implemented and LSP-compliant**. All request routing, response translation, and protocol handling is sound. The handler properly declares hover capability, sends well-formed requests to the sidecar, and translates responses into LSP Hover format.

The "no popover in Zed" symptom appears to be caused by the **sidecar returning empty JSON objects** when it cannot find a meaningful element at the hover position, not by issues in the Rust handler.

---

## Implementation Details

### 1. Request Handling (server.rs:1082-1110)

```rust
async fn hover(&self, params: HoverParams) -> LspResult<Option<Hover>> {
    let uri = params.text_document_position_params.text_document.uri;
    let position = params.text_document_position_params.position;

    tracing::debug!("hover request: {}:{}:{}", uri, position.line, position.character);

    let bridge = self.bridge.lock().await;
    let bridge = match bridge.as_ref() {
        Some(b) => b,
        None => {
            tracing::warn!("hover: bridge is None, returning null");
            return Ok(None);
        }
    };

    match bridge
        .request(
            "hover",
            Some(serde_json::json!({
                "uri": uri.as_str(),
                "line": position.line + 1,      // Convert LSP 0-based to sidecar 1-based
                "character": position.character,  // Keep 0-based
            })),
        )
        .await
    { ... }
}
```

**Analysis:**
- ✓ Correctly extracts URI and position from LSP parameters
- ✓ Converts line number from LSP 0-based to sidecar 1-based (line += 1)
- ✓ Keeps character position 0-based (correct)
- ✓ Handles bridge unavailability gracefully

### 2. Response Translation (server.rs:1111-1131)

```rust
Ok(result) => {
    tracing::debug!("hover: sidecar returned: {}", result);
    if let Some(contents) = result.get("contents").and_then(|c| c.as_str()) {
        Ok(Some(Hover {
            contents: HoverContents::Markup(MarkupContent {
                kind: MarkupKind::Markdown,
                value: contents.to_string(),
            }),
            range: None,
        }))
    } else {
        tracing::warn!("hover: sidecar result has no 'contents' string field");
        Ok(None)
    }
}
Err(e) => {
    tracing::warn!("hover: bridge request failed: {}", e);
    Ok(None)
}
```

**Analysis:**
- ✓ Checks for "contents" field in response (required)
- ✓ Uses `.and_then(|c| c.as_str())` to validate it's a string
- ✓ Creates LSP-compliant Hover with MarkupContent
- ✓ Uses MarkupKind::Markdown (modern LSP standard, not deprecated MarkedString)
- ✓ Sets range to None (valid - range is optional)
- ✓ Returns Ok(None) for missing/malformed responses (correct)

### 3. Server Capabilities Declaration (server.rs:375)

```rust
hover_provider: Some(HoverProviderCapability::Simple(true)),
```

**Analysis:**
- ✓ Properly declares hover support in initialize response
- ✓ Uses Simple(true) variant (correct for basic hover without options)

### 4. Bridge Communication (bridge.rs)

**Request sending (lines 440-471):**
- ✓ `request()` method sends JSON-RPC request with proper framing
- ✓ Uses Content-Length headers (required by protocol)
- ✓ Waits up to 60 seconds for response (generous timeout)
- ✓ No modification of request/response body

**Response dispatch (lines 527-555):**
```rust
async fn dispatch_response(pending: &Mutex<Vec<PendingRequest>>, response: Response) {
    let id = match response.id {
        Some(id) => id,
        None => {
            tracing::warn!("received response without id");
            return;
        }
    };

    let mut pending = pending.lock().await;
    if let Some(pos) = pending.iter().position(|p| p.id == id) {
        let req = pending.remove(pos);
        let result = if let Some(error) = response.error {
            // ... error handling ...
        } else {
            Ok(response.result.unwrap_or(Value::Null))
        };
        let _ = req.response_tx.send(result);
    }
}
```

**Analysis:**
- ✓ Properly routes responses to waiting requests by ID
- ✓ Handles missing result field (defaults to Value::Null)
- ✓ Extracts error information correctly
- ✓ No parsing issues or unexpected transformations

### 5. JSON-RPC Protocol (jsonrpc.rs)

**Response struct:**
```rust
#[derive(Debug, Serialize, Deserialize)]
pub struct Response {
    pub jsonrpc: String,
    pub id: Option<u64>,
    pub result: Option<serde_json::Value>,
    pub error: Option<ResponseError>,
}
```

**Message framing:**
- ✓ Content-Length header parsing (read_content_length, lines 104-135)
- ✓ Proper header/body separation (blank line)
- ✓ Correct JSON deserialization with error handling
- ✓ Handles EOF and truncated messages

**Analysis:**
- ✓ Protocol implementation is sound and complete
- ✓ No risk of message corruption or parsing failure

---

## Sidecar Response Format

### Hover Method (CompilerBridge.kt:625-707)

```kotlin
fun hover(uri: String, line: Int, character: Int): JsonObject {
    val result = JsonObject()
    val perfStart = System.currentTimeMillis()

    val currentSession = session ?: return result
    val ktFile = findKtFile(currentSession, uri) ?: return result

    try {
        analyze(ktFile) {
            val offset = lineColToOffset(ktFile, line, character)
            if (offset == null) return@analyze  // ← Returns empty result
            val element = ktFile.findElementAt(offset)
            if (element == null) return@analyze  // ← Returns empty result

            // ... walk up PSI tree ...

            if (current is KtNamedDeclaration) {
                val hoverText = buildDeclarationHover(current)
                if (hoverText != null) {
                    result.addProperty("contents", hoverText)  // ← Success path
                    return@analyze
                }
            }

            // ... other patterns ...
        }
    } catch (e: Throwable) {
        System.err.println("CompilerBridge: hover failed: ...")
    }

    return result  // ← Can be empty {} if no element found
}
```

**Response formats:**
1. **Success**: `{"contents": "markdown string"}`
2. **Not found**: `{}` (empty object)
3. **Error**: `{}` (empty object, exception logged)

**Analysis:**
- ✓ Markdown format is correct
- ✓ Returns valid JSON-RPC result (not error)
- ✓ Empty object on failure is valid JSON-RPC, but causes Rust handler to return Ok(None)

---

## Potential Issues

### Issue 1: Missing "contents" Field (Medium Priority)

**Symptom**: No popover shown in Zed even though JSON-RPC messages appear in logs

**Root Cause**: Sidecar returns `{}` when element not found, Rust returns `Ok(None)`

**Current flow:**
```
Sidecar: {} (no element found)
↓
Rust: result.get("contents") returns None
↓
Rust returns: Ok(None)
↓
Zed: No popover shown
```

**Evidence:**
- Sidecar returns empty JsonObject (line 626: `val result = JsonObject()`)
- Early returns on line 630 (session null), 630 (ktFile null), 636-637 (offset null), 640-642 (element null)
- Line 706 returns `result` which may be empty

**Rust handler's response (lines 1122-1124):**
```rust
} else {
    tracing::warn!("hover: sidecar result has no 'contents' string field");
    Ok(None)
}
```

**Status**: This is **correct behavior** — the Rust handler properly handles the case where sidecar finds no content. However, this might indicate that the sidecar's element-finding logic is failing more often than expected.

### Issue 2: No Other Protocol Issues Found

Investigated and verified:
- ✓ Hover capability properly declared
- ✓ LSP message format correct (MarkupContent, not MarkedString)
- ✓ Range field handling correct (None is valid)
- ✓ JSON-RPC framing correct
- ✓ Bridge request/response dispatch correct
- ✓ Error handling comprehensive
- ✓ Timeout handling generous (60s)
- ✓ Logging at appropriate levels

---

## Recommendations

### For Debugging the "No Popover" Issue

1. **Enable debug logging** on both Rust and sidecar to see:
   - What hover requests are being sent
   - What sidecar returns (empty {} or valid response)
   - Whether Rust handler is seeing the warning about missing "contents"

2. **Check sidecar hover logic**:
   - Is `findKtFile()` failing to locate files?
   - Is `lineColToOffset()` returning null despite valid position?
   - Is element-finding logic returning null for valid positions?

3. **Verify position accuracy**:
   - Is line conversion (LSP 0-based → sidecar 1-based) correct?
   - Are character positions 0-based as expected?

### For Future Development

1. **Better empty response handling**: Consider returning explicit null or error if sidecar finds nothing, rather than relying on missing field
2. **More detailed hover logging**: Log what element type matched (declaration, reference, expression) for debugging
3. **Cache hover results**: Performance optimization for repeated hovers on same position

---

## Code Locations

| Component | File | Lines | Responsibility |
|-----------|------|-------|-----------------|
| Hover handler | server.rs | 1082-1131 | Request → Response translation |
| Server capabilities | server.rs | 375 | Declare hover support |
| Bridge request/response | bridge.rs | 440-471, 527-555 | JSON-RPC communication |
| JSON-RPC framing | jsonrpc.rs | 59-135 | Message serialization |
| Sidecar hover | CompilerBridge.kt | 625-707 | Element analysis and rendering |

---

## Conclusion

The Rust LSP hover handler is **well-implemented and correct**. It:
- ✓ Properly declares capabilities
- ✓ Sends well-formed requests with correct position conversion
- ✓ Uses LSP-compliant response format
- ✓ Handles all error cases gracefully
- ✓ Has appropriate timeouts and logging

The "no popover" symptom is likely caused by the sidecar returning empty responses when it cannot find hover information at the requested position, not by issues in the Rust handler itself.

**Recommendation**: Investigate sidecar's `hover()` method's element-finding logic (CompilerBridge.kt:625-707) to understand why it's returning empty responses in cases where hover should be available.
