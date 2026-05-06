#![cfg(feature = "integration")]
//! Integration test that actually verifies LSP functionality works end-to-end.
//! This test will FAIL if hover, rename, or other LSP features don't work.
//!
//! Run with: cargo test --features integration
//!
//! The `LspTestClient` used here lives in the shared
//! `kotlin-analyzer-sensors` crate so the `sensors` binary and these tests
//! exercise the same end-to-end LSP path (see ka-g342).

use kotlin_analyzer_sensors::lsp_client::{env_lock, LspTestClient};
use serde_json::json;
use std::time::Duration;
use tempfile::tempdir;

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
fn test_initialize_advertises_execute_command_provider() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");

    let response = client
        .send_request(
            "initialize",
            json!({
                "processId": std::process::id(),
                "rootUri": "file:///tmp/test-project",
                "capabilities": {}
            }),
        )
        .expect("initialize request failed");

    let commands = response["result"]["capabilities"]["executeCommandProvider"]["commands"]
        .as_array()
        .expect("executeCommandProvider.commands should be present");
    let commands: Vec<&str> = commands
        .iter()
        .filter_map(|command| command.as_str())
        .collect();

    assert!(
        commands.contains(&"kotlin-analyzer.openTestTarget"),
        "openTestTarget should be advertised"
    );
    assert!(
        commands.contains(&"kotlin-analyzer.createAndOpenTestTarget"),
        "createAndOpenTestTarget should be advertised"
    );
}

#[test]
fn test_execute_command_open_target_emits_show_document() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize()
        .expect("Failed to initialize LSP server");

    let (response, requests) = client
        .send_request_and_capture_requests(
            "workspace/executeCommand",
            json!({
                "command": "kotlin-analyzer.openTarget",
                "arguments": [
                    {
                        "targetUri": "file:///tmp/impl.kt",
                        "selection": {
                            "startLine": 2,
                            "startCharacter": 6,
                            "endLine": 2,
                            "endCharacter": 20
                        }
                    }
                ]
            }),
            Some("window/showDocument"),
        )
        .expect("executeCommand should succeed");

    assert_eq!(requests.len(), 1, "expected a single showDocument request");
    assert_eq!(
        requests[0]["params"]["uri"].as_str(),
        Some("file:///tmp/impl.kt")
    );
    assert_eq!(
        requests[0]["params"]["selection"],
        json!({
            "start": { "line": 2, "character": 6 },
            "end": { "line": 2, "character": 20 }
        })
    );
    assert_eq!(response["result"]["shown"], json!(true));
}

#[test]
fn test_execute_command_open_target_uses_zed_cli_for_zed_clients() {
    let _guard = env_lock().lock().expect("env lock poisoned");
    std::env::set_var("KOTLIN_ANALYZER_ZED_CLI", "/usr/bin/true");

    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize_with_root_and_client("file:///tmp/test-project", Some("Zed"))
        .expect("Failed to initialize LSP server");

    let result = client
        .send_request_and_capture_requests(
            "workspace/executeCommand",
            json!({
                "command": "kotlin-analyzer.openTarget",
                "arguments": [
                    {
                        "targetUri": "file:///tmp/impl.kt",
                        "selection": {
                            "startLine": 2,
                            "startCharacter": 6,
                            "endLine": 2,
                            "endCharacter": 20
                        }
                    }
                ]
            }),
            Some("window/showDocument"),
        )
        .expect("executeCommand should succeed for Zed clients");

    std::env::remove_var("KOTLIN_ANALYZER_ZED_CLI");

    let (response, requests) = result;
    assert!(requests.is_empty(), "Zed CLI navigation should not emit showDocument");
    assert_eq!(response["result"]["shown"], json!(true));
}

#[test]
fn test_execute_command_open_test_target_emits_show_document() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize()
        .expect("Failed to initialize LSP server");

    let (response, requests) = client
        .send_request_and_capture_requests(
            "workspace/executeCommand",
            json!({
                "command": "kotlin-analyzer.openTestTarget",
                "arguments": [
                    {
                        "targetUri": "file:///tmp/existing-test.kt",
                        "selection": {
                            "startLine": 4,
                            "startCharacter": 2,
                            "endLine": 4,
                            "endCharacter": 8
                        }
                    }
                ]
            }),
            Some("window/showDocument"),
        )
        .expect("executeCommand should succeed");

    assert_eq!(requests.len(), 1, "expected a single showDocument request");
    assert_eq!(
        requests[0]["params"]["uri"].as_str(),
        Some("file:///tmp/existing-test.kt")
    );
    assert_eq!(
        requests[0]["params"]["selection"],
        json!({
            "start": { "line": 4, "character": 2 },
            "end": { "line": 4, "character": 8 }
        })
    );
    assert_eq!(response["result"]["shown"], json!(true));
}

#[test]
fn test_execute_command_create_and_open_writes_file_then_shows_document() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize()
        .expect("Failed to initialize LSP server");

    let workspace = tempdir().expect("failed to create tempdir");
    let target_path = workspace.path().join("src/test/kotlin/ExampleTest.kt");
    let target_uri = format!("file://{}", target_path.display());

    let (response, requests) = client
        .send_request_and_capture_requests(
            "workspace/executeCommand",
            json!({
                "command": "kotlin-analyzer.createAndOpenTestTarget",
                "arguments": [
                    {
                        "targetUri": target_uri,
                        "targetPath": target_path,
                        "initialContents": "class ExampleTest\n",
                        "selection": {
                            "startLine": 0,
                            "startCharacter": 6,
                            "endLine": 0,
                            "endCharacter": 13
                        }
                    }
                ]
            }),
            Some("window/showDocument"),
        )
        .expect("createAndOpen executeCommand should succeed");

    assert_eq!(
        std::fs::read_to_string(&target_path).expect("target file should be written"),
        "class ExampleTest\n"
    );
    assert_eq!(requests.len(), 1, "expected a single showDocument request");
    assert_eq!(
        requests[0]["params"]["uri"].as_str(),
        Some(target_uri.as_str())
    );
    assert_eq!(response["result"]["shown"], json!(true));
    assert_eq!(response["result"]["created"], json!(true));
}

#[test]
fn test_execute_command_rejects_invalid_payload_without_creating_file() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize()
        .expect("Failed to initialize LSP server");

    let workspace = tempdir().expect("failed to create tempdir");
    let target_path = workspace.path().join("src/test/kotlin/Missing.kt");

    let (response, requests) = client
        .send_request_and_capture_requests(
            "workspace/executeCommand",
            json!({
                "command": "kotlin-analyzer.createAndOpenTestTarget",
                "arguments": [
                    {
                        "targetPath": target_path,
                        "initialContents": "class Missing\n"
                    }
                ]
            }),
            Some("window/showDocument"),
        )
        .expect("server should return an LSP error response");

    assert!(
        response.get("error").is_some(),
        "invalid payload should return an error response"
    );
    assert!(
        !target_path.exists(),
        "invalid payload should not create the target file"
    );
    assert!(
        requests.is_empty(),
        "invalid payload should not trigger showDocument"
    );
}

#[test]
fn test_execute_command_rejects_unsupported_command_ids() {
    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize()
        .expect("Failed to initialize LSP server");

    let response = client
        .send_request(
            "workspace/executeCommand",
            json!({
                "command": "kotlin-analyzer.notSupported",
                "arguments": [{}]
            }),
        )
        .expect("server should respond with an error payload");

    let error = response
        .get("error")
        .expect("unsupported command should be rejected");
    assert_eq!(error["code"], json!(-32602));
    assert!(error["message"]
        .as_str()
        .unwrap_or_default()
        .contains("unsupported analyzer command"));
}

#[test]
fn test_goto_implementation_resolves_interface_method_targets() {
    let workspace = tempdir().expect("failed to create temporary workspace");
    let source_root = workspace.path().join("src/main/kotlin/com/example");
    std::fs::create_dir_all(&source_root).expect("failed to create source root");

    let service_path = source_root.join("Service.kt");
    let primary_path = source_root.join("PrimaryService.kt");
    let fallback_path = source_root.join("FallbackService.kt");

    let service_text = r#"package com.example

interface Service {
    fun fetch(id: String): String
}
"#;
    let primary_text = r#"package com.example

class PrimaryService : Service {
    override fun fetch(id: String): String = id
}
"#;
    let fallback_text = r#"package com.example

object FallbackService : Service {
    override fun fetch(id: String): String = id.reversed()
}
"#;

    std::fs::write(&service_path, service_text).expect("failed to write interface");
    std::fs::write(&primary_path, primary_text).expect("failed to write primary implementation");
    std::fs::write(&fallback_path, fallback_text)
        .expect("failed to write fallback implementation");

    let root_uri = format!("file://{}", workspace.path().display());
    let service_uri = format!("file://{}", service_path.display());
    let primary_uri = format!("file://{}", primary_path.display());
    let fallback_uri = format!("file://{}", fallback_path.display());

    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize_with_root(&root_uri)
        .expect("Failed to initialize LSP server");

    client
        .open_document(&service_uri, service_text)
        .expect("failed to open interface document");
    client
        .open_document(&primary_uri, primary_text)
        .expect("failed to open primary implementation document");
    client
        .open_document(&fallback_uri, fallback_text)
        .expect("failed to open fallback implementation document");

    let result = client
        .implementation(&service_uri, 2, 8)
        .expect("implementation request failed")
        .expect("implementation request returned null");

    let locations = result
        .as_array()
        .expect("multi-target implementation response should be an array");
    assert_eq!(locations.len(), 2, "expected two implementation targets");

    let mut actual_targets = locations
        .iter()
        .map(|location| {
            (
                location["uri"].as_str().unwrap_or_default().to_string(),
                location["range"]["start"]["line"].as_u64().unwrap_or_default(),
            )
        })
        .collect::<Vec<_>>();
    actual_targets.sort();

    let mut expected_targets = vec![(fallback_uri, 2_u64), (primary_uri, 2_u64)];
    expected_targets.sort();

    assert_eq!(actual_targets, expected_targets);
}

#[test]
fn test_goto_definition_resolves_interface_method_declaration_from_override() {
    let workspace = tempdir().expect("failed to create temporary workspace");
    let source_root = workspace.path().join("src/main/kotlin/com/example");
    std::fs::create_dir_all(&source_root).expect("failed to create source root");

    let service_path = source_root.join("Service.kt");
    let primary_path = source_root.join("PrimaryService.kt");
    let fallback_path = source_root.join("FallbackService.kt");

    let service_text = r#"package com.example

interface Service {
    fun fetch(id: String): String
}
"#;
    let primary_text = r#"package com.example

class PrimaryService : Service {
    override fun fetch(id: String): String = id
}
"#;
    let fallback_text = r#"package com.example

object FallbackService : Service {
    override fun fetch(id: String): String = id.reversed()
}
"#;

    std::fs::write(&service_path, service_text).expect("failed to write interface");
    std::fs::write(&primary_path, primary_text).expect("failed to write primary implementation");
    std::fs::write(&fallback_path, fallback_text)
        .expect("failed to write fallback implementation");

    let root_uri = format!("file://{}", workspace.path().display());
    let service_uri = format!("file://{}", service_path.display());
    let primary_uri = format!("file://{}", primary_path.display());
    let fallback_uri = format!("file://{}", fallback_path.display());

    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize_with_root(&root_uri)
        .expect("Failed to initialize LSP server");

    client
        .open_document(&service_uri, service_text)
        .expect("failed to open interface document");
    client
        .open_document(&primary_uri, primary_text)
        .expect("failed to open primary implementation document");
    client
        .open_document(&fallback_uri, fallback_text)
        .expect("failed to open fallback implementation document");

    for implementation_uri in [&primary_uri, &fallback_uri] {
        let result = client
            .definition(implementation_uri, 3, 17)
            .expect("definition request failed")
            .expect("definition request returned null");

        let location = if result.is_array() {
            result
                .as_array()
                .and_then(|locations| locations.first())
                .cloned()
                .expect("expected at least one definition location")
        } else {
            result
        };

        assert_eq!(location["uri"].as_str(), Some(service_uri.as_str()));
        assert_eq!(location["range"]["start"]["line"].as_u64(), Some(3));
    }
}

#[test]
fn test_pebble_template_definition_routes_through_pebble_bridge() {
    let workspace = tempdir().expect("failed to create temporary workspace");
    let templates_dir = workspace.path().join("src/main/resources/templates");
    std::fs::create_dir_all(templates_dir.join("layouts")).expect("failed to create layouts dir");
    std::fs::create_dir_all(templates_dir.join("macros")).expect("failed to create macros dir");
    std::fs::create_dir_all(templates_dir.join("partials")).expect("failed to create partials dir");
    std::fs::create_dir_all(templates_dir.join("users")).expect("failed to create users dir");

    let extends_path = templates_dir.join("layouts/base.peb");
    let import_path = templates_dir.join("macros/forms.peb");
    let include_path = templates_dir.join("partials/details.peb");
    let page_path = templates_dir.join("users/detail.peb");

    std::fs::write(&extends_path, "{% block content %}{% endblock %}\n")
        .expect("failed to write base template");
    std::fs::write(
        &import_path,
        "{% macro input(name, value) %}{{ value }}{% endmacro %}\n",
    )
    .expect("failed to write macro template");
    std::fs::write(&include_path, "<p>details</p>\n").expect("failed to write partial template");

    let page_text = r#"{% extends "layouts/base.peb" %}
{% import "macros/forms.peb" as forms %}

{% block content %}
  <section class="user-card">
    <h1>{{ user.name | title }}</h1>
    {% if user.active and user.roles[0] != "guest" %}
      <p>{{ forms.input("email", user.email) }}</p>
      {% include "partials/details.peb" %}
    {% endif %}
  </section>
{% endblock %}
"#;
    std::fs::write(&page_path, page_text).expect("failed to write page template");

    let root_uri = format!("file://{}", workspace.path().display());
    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize_with_root(&root_uri)
        .expect("Failed to initialize LSP server");

    let extends_uri = format!("file://{}", extends_path.display());
    let import_uri = format!("file://{}", import_path.display());
    let include_uri = format!("file://{}", include_path.display());
    let page_uri = format!("file://{}", page_path.display());

    client
        .open_document_with_language(&page_uri, "plaintext", page_text)
        .expect("Failed to open pebble page");

    let extends_definition = client
        .definition(&page_uri, 0, 15)
        .expect("extends definition request failed")
        .expect("extends definition returned null");
    let import_definition = client
        .definition(&page_uri, 1, 14)
        .expect("import definition request failed")
        .expect("import definition returned null");
    let include_definition = client
        .definition(&page_uri, 8, 18)
        .expect("include definition request failed")
        .expect("include definition returned null");

    let extends_target = extends_definition
        .get("uri")
        .and_then(|value| value.as_str())
        .or_else(|| {
            extends_definition
                .as_array()
                .and_then(|values| values.first())
                .and_then(|value| value.get("uri"))
                .and_then(|value| value.as_str())
        });
    let import_target = import_definition
        .get("uri")
        .and_then(|value| value.as_str())
        .or_else(|| {
            import_definition
                .as_array()
                .and_then(|values| values.first())
                .and_then(|value| value.get("uri"))
                .and_then(|value| value.as_str())
        });
    let include_target = include_definition
        .get("uri")
        .and_then(|value| value.as_str())
        .or_else(|| {
            include_definition
                .as_array()
                .and_then(|values| values.first())
                .and_then(|value| value.get("uri"))
                .and_then(|value| value.as_str())
        });

    assert_eq!(extends_target, Some(extends_uri.as_str()));
    assert_eq!(import_target, Some(import_uri.as_str()));
    assert_eq!(include_target, Some(include_uri.as_str()));
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
    let items = response.get("result").and_then(|r| {
        r.as_array()
            .or_else(|| r.get("items").and_then(|i| i.as_array()))
    });

    assert!(
        items.is_some() && !items.unwrap().is_empty(),
        "Completion should return String methods but got none or empty. Response: {:?}",
        response.get("result")
    );
}

#[test]
fn test_completion_works_immediately_after_did_change_on_disk_file() {
    let workspace = tempdir().expect("failed to create temporary workspace");
    let src_dir = workspace.path().join("src/main/kotlin");
    std::fs::create_dir_all(&src_dir).expect("failed to create source dir");

    let file_path = src_dir.join("CompletionAfterEdit.kt");
    let initial_text = r#"fun main() {
    val str = "Hello"
    str
}
"#;
    let updated_text = r#"fun main() {
    val str = "Hello"
    str.
}
"#;
    std::fs::write(&file_path, initial_text).expect("failed to write Kotlin file");

    let root_uri = format!("file://{}", workspace.path().display());
    let uri = format!("file://{}", file_path.display());

    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize_with_root(&root_uri)
        .expect("Failed to initialize LSP server");
    client
        .open_document(&uri, initial_text)
        .expect("Failed to open document");
    client
        .change_document(&uri, 2, updated_text)
        .expect("Failed to send didChange");

    let params = json!({
        "textDocument": { "uri": uri },
        "position": { "line": 2, "character": 8 },
        "context": {
            "triggerKind": 2,
            "triggerCharacter": "."
        }
    });

    let response = client
        .send_request("textDocument/completion", params)
        .expect("Completion request failed");

    let items = response.get("result").and_then(|r| {
        r.as_array()
            .or_else(|| r.get("items").and_then(|i| i.as_array()))
    });

    assert!(
        items.is_some() && !items.unwrap().is_empty(),
        "Completion after didChange should return String methods but got none or empty. Response: {:?}",
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
    let actions = result.and_then(|r| if r.is_null() { None } else { r.as_array() });

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

#[test]
fn test_unit_test_code_action_opens_existing_target() {
    let workspace = tempdir().expect("failed to create temporary workspace");
    let source_dir = workspace.path().join("src/main/kotlin/com/example");
    let test_dir = workspace.path().join("src/test/kotlin/com/example");
    std::fs::create_dir_all(&source_dir).expect("failed to create main source dir");
    std::fs::create_dir_all(&test_dir).expect("failed to create test source dir");

    let source_path = source_dir.join("Foo.kt");
    let test_path = test_dir.join("FooTest.kt");
    let source_text = "package com.example\n\nclass Foo {\n    fun value(): Int = 42\n}\n";
    let test_text = concat!(
        "package com.example\n\n",
        "import org.junit.jupiter.api.Test\n\n",
        "class FooTest {\n",
        "    @Test\n",
        "    fun existingWorks() {\n",
        "    }\n",
        "}\n"
    );
    std::fs::write(&source_path, source_text).expect("failed to write source file");
    std::fs::write(&test_path, test_text).expect("failed to write existing test file");

    let source_uri = format!("file://{}", source_path.display());
    let test_uri = format!("file://{}", test_path.display());
    let root_uri = format!("file://{}", workspace.path().display());

    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize_with_root(&root_uri)
        .expect("Failed to initialize LSP server");
    client
        .open_document(&source_uri, source_text)
        .expect("Failed to open source document");

    let code_action_response = client
        .send_request(
            "textDocument/codeAction",
            json!({
                "textDocument": { "uri": source_uri },
                "range": {
                    "start": { "line": 2, "character": 7 },
                    "end": { "line": 2, "character": 7 }
                },
                "context": { "diagnostics": [] }
            }),
        )
        .expect("Code action request failed");

    let action = code_action_response["result"]
        .as_array()
        .and_then(|actions| {
            actions.iter().find(|action| {
                action.get("title").and_then(|title| title.as_str())
                    == Some("Open or Create Unit Test")
            })
        })
        .cloned()
        .expect("expected unit-test code action");

    let command = action["command"].clone();
    let (response, requests) = client
        .send_request_and_capture_requests(
            "workspace/executeCommand",
            json!({
                "command": command["command"],
                "arguments": command["arguments"],
            }),
            Some("window/showDocument"),
        )
        .expect("executeCommand should succeed");

    assert_eq!(requests.len(), 1, "expected a single showDocument request");
    assert_eq!(
        requests[0]["params"]["uri"].as_str(),
        Some(test_uri.as_str())
    );
    assert_eq!(
        requests[0]["params"]["selection"],
        json!({
            "start": { "line": 6, "character": 4 },
            "end": { "line": 6, "character": 4 }
        })
    );
    assert_eq!(response["result"]["shown"], json!(true));
    assert_eq!(
        std::fs::read_to_string(&test_path).expect("existing test file should remain readable"),
        test_text
    );
}

#[test]
fn test_unit_test_code_action_creates_missing_target() {
    let workspace = tempdir().expect("failed to create temporary workspace");
    let source_dir = workspace.path().join("src/main/kotlin/com/example");
    std::fs::create_dir_all(&source_dir).expect("failed to create main source dir");

    let source_path = source_dir.join("Foo.kt");
    let source_text = "package com.example\n\nclass Foo {\n    fun value(): Int = 42\n}\n";
    std::fs::write(&source_path, source_text).expect("failed to write source file");

    let source_uri = format!("file://{}", source_path.display());
    let root_uri = format!("file://{}", workspace.path().display());
    let expected_test_path = workspace
        .path()
        .join("src/test/kotlin/com/example/FooTest.kt");
    let expected_test_uri = format!("file://{}", expected_test_path.display());

    let mut client = LspTestClient::new().expect("Failed to start LSP server");
    client
        .initialize_with_root(&root_uri)
        .expect("Failed to initialize LSP server");
    client
        .open_document(&source_uri, source_text)
        .expect("Failed to open source document");

    let code_action_response = client
        .send_request(
            "textDocument/codeAction",
            json!({
                "textDocument": { "uri": source_uri },
                "range": {
                    "start": { "line": 2, "character": 7 },
                    "end": { "line": 2, "character": 7 }
                },
                "context": { "diagnostics": [] }
            }),
        )
        .expect("Code action request failed");

    let action = code_action_response["result"]
        .as_array()
        .and_then(|actions| {
            actions.iter().find(|action| {
                action.get("title").and_then(|title| title.as_str())
                    == Some("Open or Create Unit Test")
            })
        })
        .cloned()
        .expect("expected unit-test code action");

    let command = action["command"].clone();
    let (response, requests) = client
        .send_request_and_capture_requests(
            "workspace/executeCommand",
            json!({
                "command": command["command"],
                "arguments": command["arguments"],
            }),
            Some("window/showDocument"),
        )
        .expect("executeCommand should succeed");

    let created_text =
        std::fs::read_to_string(&expected_test_path).expect("expected test file to be created");

    assert_eq!(requests.len(), 1, "expected a single showDocument request");
    assert_eq!(
        requests[0]["params"]["uri"].as_str(),
        Some(expected_test_uri.as_str())
    );
    assert_eq!(
        requests[0]["params"]["selection"],
        json!({
            "start": { "line": 7, "character": 8 },
            "end": { "line": 7, "character": 8 }
        })
    );
    assert_eq!(response["result"]["shown"], json!(true));
    assert_eq!(response["result"]["created"], json!(true));
    assert!(created_text.contains("package com.example"));
    assert!(created_text.contains("import org.junit.jupiter.api.Test"));
    assert!(created_text.contains("class FooTest"));
    assert!(created_text.contains("fun hurz()"));
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
    let diags_after_open =
        client.collect_notifications("textDocument/publishDiagnostics", Duration::from_secs(10));

    let has_errors = diags_after_open.iter().any(|n| {
        if let Some(params) = n.get("params") {
            let diag_uri = params.get("uri").and_then(|u| u.as_str()).unwrap_or("");
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
    let diags_after_close =
        client.collect_notifications("textDocument/publishDiagnostics", Duration::from_secs(3));

    // The server should NOT clear diagnostics on didClose
    let cleared = diags_after_close.iter().any(|n| {
        if let Some(params) = n.get("params") {
            let diag_uri = params.get("uri").and_then(|u| u.as_str()).unwrap_or("");
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
