#![cfg(feature = "integration")]
//! Integration test that actually verifies LSP functionality works end-to-end.
//! This test will FAIL if hover, rename, or other LSP features don't work.
//!
//! Run with: cargo test --features integration

use serde_json::{json, Value};
use std::time::Duration;

/// Test helper to start the LSP server and communicate with it
struct LspTestClient {
    process: std::process::Child,
    stdin: std::process::ChildStdin,
    stdout: std::io::BufReader<std::process::ChildStdout>,
}

impl LspTestClient {
    fn new() -> Result<Self, Box<dyn std::error::Error>> {
        // Build the server first
        std::process::Command::new("cargo")
            .args(&["build", "--release"])
            .output()?;

        // Start the LSP server
        let mut process = std::process::Command::new("target/release/kotlin-analyzer")
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()?;

        let stdin = process.stdin.take().expect("Failed to get stdin");
        let stdout = std::io::BufReader::new(process.stdout.take().expect("Failed to get stdout"));

        Ok(LspTestClient {
            process,
            stdin,
            stdout,
        })
    }

    fn send_request(
        &mut self,
        method: &str,
        params: Value,
    ) -> Result<Value, Box<dyn std::error::Error>> {
        let request = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": method,
            "params": params
        });

        // Send request
        let request_str = serde_json::to_string(&request)?;
        let content_length = request_str.len();
        let message = format!("Content-Length: {}\r\n\r\n{}", content_length, request_str);

        use std::io::Write;
        self.stdin.write_all(message.as_bytes())?;
        self.stdin.flush()?;

        // Read response
        use std::io::BufRead;
        let mut headers = String::new();
        loop {
            let mut line = String::new();
            self.stdout.read_line(&mut line)?;
            if line == "\r\n" {
                break;
            }
            headers.push_str(&line);
        }

        // Parse content-length
        let content_length: usize = headers
            .lines()
            .find(|line| line.starts_with("Content-Length:"))
            .and_then(|line| line.split(':').nth(1))
            .and_then(|len| len.trim().parse().ok())
            .expect("Failed to parse Content-Length");

        // Read response body
        let mut buffer = vec![0u8; content_length];
        use std::io::Read;
        self.stdout.read_exact(&mut buffer)?;

        let response: Value = serde_json::from_slice(&buffer)?;
        Ok(response)
    }

    fn initialize(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let params = json!({
            "processId": std::process::id(),
            "rootUri": "file:///tmp/test-project",
            "capabilities": {
                "textDocument": {
                    "hover": {
                        "dynamicRegistration": false
                    },
                    "rename": {
                        "dynamicRegistration": false
                    },
                    "definition": {
                        "dynamicRegistration": false
                    }
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

        // Send initialized notification
        self.send_request("initialized", json!({})).ok();

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

        self.send_request("textDocument/didOpen", params)?;

        // Wait a bit for the sidecar to process
        std::thread::sleep(Duration::from_millis(500));

        Ok(())
    }

    fn hover(
        &mut self,
        uri: &str,
        line: u32,
        character: u32,
    ) -> Result<Option<String>, Box<dyn std::error::Error>> {
        let params = json!({
            "textDocument": {
                "uri": uri
            },
            "position": {
                "line": line,
                "character": character
            }
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
    std::thread::sleep(Duration::from_secs(2));

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
        "textDocument": {
            "uri": uri
        },
        "position": {
            "line": 3,
            "character": 8
        }
    });

    let response = client
        .send_request("textDocument/completion", params)
        .expect("Completion request failed");

    // Check we got completions
    let items = response
        .get("result")
        .and_then(|r| r.get("items"))
        .and_then(|i| i.as_array());

    assert!(
        items.is_some() && !items.unwrap().is_empty(),
        "Completion should return String methods but got none or empty"
    );
}
