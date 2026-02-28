#![cfg(feature = "integration")]
//! Integration test that actually verifies LSP functionality works end-to-end.
//! This test will FAIL if hover, rename, or other LSP features don't work.
//!
//! Run with: cargo test --features integration

use serde_json::{json, Value};
use std::io::{BufRead, Read, Write};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::mpsc;
use std::time::Duration;

/// Test helper to start the LSP server and communicate with it.
///
/// Spawns a background reader thread that continuously reads server messages
/// from stdout and forwards them through a channel. This prevents pipe buffer
/// deadlocks that occur when the server writes many messages (progress
/// notifications, diagnostics) while the test is sleeping.
struct LspTestClient {
    process: std::process::Child,
    stdin: std::process::ChildStdin,
    rx: mpsc::Receiver<Value>,
    next_id: AtomicI64,
}

impl LspTestClient {
    fn new() -> Result<Self, Box<dyn std::error::Error>> {
        // Build the server first
        std::process::Command::new("cargo")
            .args(["build", "--release"])
            .output()?;

        // Start the LSP server
        let mut process = std::process::Command::new("target/release/kotlin-analyzer")
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()?;

        let stdin = process.stdin.take().expect("Failed to get stdin");
        let stdout = process.stdout.take().expect("Failed to get stdout");

        // Spawn background reader thread
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let mut reader = std::io::BufReader::new(stdout);
            loop {
                // Read Content-Length header
                let mut headers = String::new();
                loop {
                    let mut line = String::new();
                    match reader.read_line(&mut line) {
                        Ok(0) => return, // EOF
                        Err(_) => return,
                        Ok(_) => {}
                    }
                    if line == "\r\n" {
                        break;
                    }
                    headers.push_str(&line);
                }

                let content_length: usize = match headers
                    .lines()
                    .find(|l| l.starts_with("Content-Length:"))
                    .and_then(|l| l.split(':').nth(1))
                    .and_then(|len| len.trim().parse().ok())
                {
                    Some(len) => len,
                    None => continue,
                };

                let mut buffer = vec![0u8; content_length];
                if reader.read_exact(&mut buffer).is_err() {
                    return;
                }

                if let Ok(msg) = serde_json::from_slice::<Value>(&buffer) {
                    if tx.send(msg).is_err() {
                        return; // Receiver dropped
                    }
                }
            }
        });

        Ok(LspTestClient {
            process,
            stdin,
            rx,
            next_id: AtomicI64::new(1),
        })
    }

    /// Write a raw JSON-RPC message to the server's stdin.
    fn write_message(&mut self, msg: &Value) -> Result<(), Box<dyn std::error::Error>> {
        let body = serde_json::to_string(msg)?;
        let header = format!("Content-Length: {}\r\n\r\n", body.len());
        self.stdin.write_all(header.as_bytes())?;
        self.stdin.write_all(body.as_bytes())?;
        self.stdin.flush()?;
        Ok(())
    }

    /// Send a JSON-RPC request and wait for the matching response (by id).
    /// Server-initiated requests are answered with empty results.
    fn send_request(
        &mut self,
        method: &str,
        params: Value,
    ) -> Result<Value, Box<dyn std::error::Error>> {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);

        let request = json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params
        });

        self.write_message(&request)?;

        // Read messages until we find the response with our id.
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        loop {
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            if remaining.is_zero() {
                return Err(format!("Timeout waiting for response to {} (id={})", method, id).into());
            }

            let msg = match self.rx.recv_timeout(remaining) {
                Ok(msg) => msg,
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    return Err(format!("Timeout waiting for response to {} (id={})", method, id).into());
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    return Err("Server stdout closed".into());
                }
            };

            // Is this a response to our request?
            if let Some(msg_id) = msg.get("id") {
                if msg_id.as_i64() == Some(id) {
                    return Ok(msg);
                }

                // It's a server→client request (has method + id). Reply with
                // an empty success so the server doesn't time out.
                if msg.get("method").is_some() {
                    let reply = json!({
                        "jsonrpc": "2.0",
                        "id": msg_id,
                        "result": null
                    });
                    self.write_message(&reply)?;
                }
            }
            // Otherwise it's a notification from the server — skip it.
        }
    }

    /// Send a JSON-RPC notification (no response expected).
    fn send_notification(
        &mut self,
        method: &str,
        params: Value,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let notification = json!({
            "jsonrpc": "2.0",
            "method": method,
            "params": params
        });
        self.write_message(&notification)
    }

    /// Drain pending server messages for the given duration, answering any
    /// server→client requests that arrive.
    fn drain_messages(&mut self, timeout: Duration) {
        let deadline = std::time::Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            if remaining.is_zero() {
                break;
            }
            match self.rx.recv_timeout(remaining) {
                Ok(msg) => {
                    // Answer server requests
                    if let (Some(id), Some(_method)) = (msg.get("id"), msg.get("method")) {
                        let reply = json!({
                            "jsonrpc": "2.0",
                            "id": id,
                            "result": null
                        });
                        let _ = self.write_message(&reply);
                    }
                }
                Err(_) => break,
            }
        }
    }

    /// Drain messages for the given duration, collecting notifications with the specified method.
    /// Server-initiated requests are answered with empty results.
    fn collect_notifications(
        &mut self,
        method: &str,
        timeout: Duration,
    ) -> Vec<Value> {
        let mut notifications = Vec::new();
        let deadline = std::time::Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            if remaining.is_zero() {
                break;
            }
            match self.rx.recv_timeout(remaining) {
                Ok(msg) => {
                    // Answer server requests
                    if let (Some(id), Some(_)) = (msg.get("id"), msg.get("method")) {
                        let reply = json!({
                            "jsonrpc": "2.0",
                            "id": id,
                            "result": null
                        });
                        let _ = self.write_message(&reply);
                    }
                    // Collect matching notifications (no id = notification)
                    if let Some(m) = msg.get("method").and_then(|m| m.as_str()) {
                        if m == method && msg.get("id").is_none() {
                            notifications.push(msg);
                        }
                    }
                }
                Err(_) => break,
            }
        }
        notifications
    }

    fn initialize(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let params = json!({
            "processId": std::process::id(),
            "rootUri": "file:///tmp/test-project",
            "capabilities": {
                "textDocument": {
                    "hover": { "dynamicRegistration": false },
                    "rename": { "dynamicRegistration": false },
                    "definition": { "dynamicRegistration": false }
                }
            }
        });

        let response = self.send_request("initialize", params)?;

        // Check that we got capabilities back
        let result = response
            .get("result")
            .ok_or("No result in initialize response")?;
        let capabilities = result
            .get("capabilities")
            .ok_or("No capabilities in response")?;

        // Verify hover is supported
        let hover_provider = capabilities.get("hoverProvider");
        if hover_provider.is_none() || !hover_provider.unwrap().as_bool().unwrap_or(false) {
            return Err("Server doesn't support hover".into());
        }

        // Send initialized notification (no response expected)
        self.send_notification("initialized", json!({}))?;

        // Wait for sidecar to start — it needs to spawn the JVM process,
        // initialise the Kotlin Analysis API session, and register file
        // watchers. With no real project (rootUri points to /tmp) and
        // stdlib-only mode this takes ~2-3 seconds.
        self.drain_messages(Duration::from_secs(5));

        Ok(())
    }

    fn open_document(&mut self, uri: &str, text: &str) -> Result<(), Box<dyn std::error::Error>> {
        let params = json!({
            "textDocument": {
                "uri": uri,
                "languageId": "kotlin",
                "version": 1,
                "text": text
            }
        });

        self.send_notification("textDocument/didOpen", params)?;

        // Wait for sidecar to process the file and produce diagnostics
        self.drain_messages(Duration::from_secs(3));

        Ok(())
    }

    fn hover(
        &mut self,
        uri: &str,
        line: u32,
        character: u32,
    ) -> Result<Option<String>, Box<dyn std::error::Error>> {
        let params = json!({
            "textDocument": { "uri": uri },
            "position": { "line": line, "character": character }
        });

        let response = self.send_request("textDocument/hover", params)?;

        // Check if we got a result
        if let Some(result) = response.get("result") {
            if !result.is_null() {
                if let Some(contents) = result.get("contents") {
                    if let Some(value) = contents.get("value") {
                        return Ok(Some(value.as_str().unwrap_or("").to_string()));
                    }
                }
            }
        }

        // Check for error
        if let Some(error) = response.get("error") {
            let message = error
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("Unknown error");
            return Err(format!("Hover request failed: {}", message).into());
        }

        Ok(None)
    }
}

impl Drop for LspTestClient {
    fn drop(&mut self) {
        // Send a shutdown request so the server can gracefully terminate
        // the JVM sidecar. If this fails (e.g. pipe broken), fall back
        // to SIGKILL which may leave orphaned sidecar processes.
        let shutdown = json!({
            "jsonrpc": "2.0",
            "id": 9999,
            "method": "shutdown",
            "params": null
        });
        let _ = self.write_message(&shutdown);
        // Brief wait for graceful shutdown
        std::thread::sleep(Duration::from_millis(500));
        let _ = self.process.kill();
    }
}

#[test]
fn test_hover_actually_works() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");

    // Initialize the server
    client
        .initialize()
        .expect("Failed to initialize LSP server");

    // Open a simple Kotlin document
    let test_code = r#"
class TestClass {
    fun hello(): String {
        return "Hello, World!"
    }
}

fun main() {
    val test = TestClass()
    println(test.hello())
}
"#;

    let uri = "file:///tmp/test.kt";
    client
        .open_document(uri, test_code)
        .expect("Failed to open document");

    // Try to hover over "TestClass" on line 8 (0-indexed)
    let hover_result = client.hover(uri, 8, 15).expect("Hover request failed");

    // THIS IS WHERE THE TEST SHOULD FAIL IF HOVER DOESN'T WORK
    assert!(
        hover_result.is_some(),
        "Hover should return information about TestClass, but got None"
    );

    let hover_text = hover_result.unwrap();
    assert!(
        hover_text.contains("TestClass") || hover_text.contains("class"),
        "Hover text should mention TestClass or class, but got: {}",
        hover_text
    );
}

#[test]
fn test_bridge_channel_is_alive() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");

    // Initialize the server
    client
        .initialize()
        .expect("Failed to initialize LSP server");

    // Open a document
    let test_code = "fun main() { println(\"test\") }";
    let uri = "file:///tmp/test2.kt";
    client
        .open_document(uri, test_code)
        .expect("Failed to open document");

    // Send multiple hover requests to test the channel stays alive
    for i in 0..3 {
        let result = client.hover(uri, 0, 5 + i);
        assert!(
            result.is_ok(),
            "Hover request {} should not fail with channel error: {:?}",
            i + 1,
            result.err()
        );
    }
}

#[test]
fn test_sidecar_stays_alive() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");

    // Initialize
    client
        .initialize()
        .expect("Failed to initialize LSP server");

    // Wait to see if sidecar crashes after initialization
    client.drain_messages(Duration::from_secs(2));

    // Try a hover request - if sidecar died, this will fail
    let test_code = "val x = 42";
    let uri = "file:///tmp/test3.kt";
    client
        .open_document(uri, test_code)
        .expect("Failed to open document");

    let result = client.hover(uri, 0, 4);
    assert!(
        result.is_ok(),
        "Sidecar appears to have died - hover request failed: {:?}",
        result.err()
    );
}

#[test]
fn test_completion_works() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");

    client
        .initialize()
        .expect("Failed to initialize LSP server");

    let test_code = r#"
fun main() {
    val str = "Hello"
    str.
}
"#;

    let uri = "file:///tmp/test4.kt";
    client
        .open_document(uri, test_code)
        .expect("Failed to open document");

    // Request completion after "str."
    let params = json!({
        "textDocument": { "uri": uri },
        "position": { "line": 3, "character": 8 }
    });

    let response = client
        .send_request("textDocument/completion", params)
        .expect("Completion request failed");

    // Check we got completions — response may be an array or {items: [...]}
    let items = response
        .get("result")
        .and_then(|r| r.as_array().or_else(|| r.get("items").and_then(|i| i.as_array())));

    assert!(
        items.is_some() && !items.unwrap().is_empty(),
        "Completion should return String methods but got none or empty. Response: {:?}",
        response.get("result")
    );
}

// Plan: code-actions-not-showing.md
// Code actions don't appear in Zed despite being implemented in the sidecar.

#[test]
fn test_code_actions_returned_for_expression_body() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize()
        .expect("Failed to initialize LSP server");

    let test_code = "fun add(a: Int, b: Int): Int = a + b\n";
    let uri = "file:///tmp/test-code-action.kt";
    client
        .open_document(uri, test_code)
        .expect("Failed to open document");

    // Request code actions at the "fun" keyword (line 0, character 0)
    let params = json!({
        "textDocument": { "uri": uri },
        "range": {
            "start": { "line": 0, "character": 0 },
            "end": { "line": 0, "character": 0 }
        },
        "context": {
            "diagnostics": []
        }
    });

    let response = client
        .send_request("textDocument/codeAction", params)
        .expect("Code action request failed");

    let result = response.get("result");

    // Result should be a non-null, non-empty array of code actions
    let actions = result
        .and_then(|r| if r.is_null() { None } else { r.as_array() });

    assert!(
        actions.is_some() && !actions.unwrap().is_empty(),
        "Code action response should contain actions for an expression-body function, \
         but got: {:?}",
        result
    );

    // At least one action should be the "Convert to block body" refactoring
    let has_convert = actions.unwrap().iter().any(|a| {
        a.get("title")
            .and_then(|t| t.as_str())
            .map(|t| t.contains("Convert to block body"))
            .unwrap_or(false)
    });

    assert!(
        has_convert,
        "Should include 'Convert to block body' action, got: {:?}",
        actions
            .unwrap()
            .iter()
            .filter_map(|a| a.get("title").and_then(|t| t.as_str()))
            .collect::<Vec<_>>()
    );
}

// --- Regression tests for active plans ---
// These tests reproduce known bugs from plans/active/.
// Each test should FAIL until its corresponding issue is fixed.

// Plan: diagnostics-disappear-on-file-switch.md
// didClose publishes empty diagnostics, clearing errors from the problems panel.

#[test]
fn test_diagnostics_persist_after_did_close() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize()
        .expect("Failed to initialize LSP server");

    // Send didOpen with a type error (don't use open_document — it drains notifications)
    let test_code = "fun main() {\n    val x: Int = \"not an int\"\n}\n";
    let uri = "file:///tmp/test-diag-persist.kt";

    client
        .send_notification(
            "textDocument/didOpen",
            json!({
                "textDocument": {
                    "uri": uri,
                    "languageId": "kotlin",
                    "version": 1,
                    "text": test_code
                }
            }),
        )
        .expect("Failed to send didOpen");

    // Collect publishDiagnostics — should eventually include errors for our file
    let diags_after_open = client.collect_notifications(
        "textDocument/publishDiagnostics",
        Duration::from_secs(10),
    );

    let has_errors = diags_after_open.iter().any(|n| {
        if let Some(params) = n.get("params") {
            let diag_uri = params
                .get("uri")
                .and_then(|u| u.as_str())
                .unwrap_or("");
            if let Some(diagnostics) = params.get("diagnostics").and_then(|d| d.as_array()) {
                return diag_uri.contains("test-diag-persist") && !diagnostics.is_empty();
            }
        }
        false
    });

    assert!(
        has_errors,
        "Should receive non-empty diagnostics after opening file with type error. \
         Got {} notification(s): {:?}",
        diags_after_open.len(),
        diags_after_open
    );

    // Now close the file
    client
        .send_notification(
            "textDocument/didClose",
            json!({
                "textDocument": { "uri": uri }
            }),
        )
        .expect("Failed to send didClose");

    // Collect any publishDiagnostics emitted after didClose
    let diags_after_close = client.collect_notifications(
        "textDocument/publishDiagnostics",
        Duration::from_secs(3),
    );

    // The server should NOT clear diagnostics on didClose
    let cleared = diags_after_close.iter().any(|n| {
        if let Some(params) = n.get("params") {
            let diag_uri = params
                .get("uri")
                .and_then(|u| u.as_str())
                .unwrap_or("");
            if let Some(diagnostics) = params.get("diagnostics").and_then(|d| d.as_array()) {
                return diag_uri.contains("test-diag-persist") && diagnostics.is_empty();
            }
        }
        false
    });

    assert!(
        !cleared,
        "Server should NOT publish empty diagnostics on didClose — \
         diagnostics should persist across file switches"
    );
}
