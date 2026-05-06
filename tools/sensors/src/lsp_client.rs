//! Test/sensor LSP client used to drive the real `kotlin-analyzer` binary
//! over stdin/stdout. Originally lived in `server/tests/integration_test.rs`;
//! lifted here so the integration tests and the `sensors` binary share one
//! implementation (per ka-g342).

use serde_json::{json, Value};
use std::io::{BufRead, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::mpsc;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

/// Drives the LSP server. Spawns background reader threads for stdout and
/// stderr so neither pipe blocks the server while the caller is sleeping.
pub struct LspTestClient {
    process: std::process::Child,
    stdin: std::process::ChildStdin,
    rx: mpsc::Receiver<Value>,
    stderr_rx: mpsc::Receiver<String>,
    next_id: AtomicI64,
}

impl LspTestClient {
    /// Build the server crate with `cargo build --release` (assumed cwd is
    /// `server/`) and start the resulting binary at
    /// `target/release/kotlin-analyzer`. Used by the existing integration
    /// tests.
    pub fn new() -> Result<Self, Box<dyn std::error::Error>> {
        std::process::Command::new("cargo")
            .args(["build", "--release"])
            .output()?;
        Self::spawn_binary(Path::new("target/release/kotlin-analyzer"))
    }

    /// Start the server binary at the given path. The caller is responsible
    /// for ensuring the binary exists.
    pub fn spawn_binary(binary: &Path) -> Result<Self, Box<dyn std::error::Error>> {
        let mut process = std::process::Command::new(binary)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()
            .map_err(|e| format!("failed to spawn {}: {e}", binary.display()))?;

        let stdin = process.stdin.take().expect("Failed to get stdin");
        let stdout = process.stdout.take().expect("Failed to get stdout");
        let stderr = process.stderr.take().expect("Failed to get stderr");

        // Background stdout reader: parses JSON-RPC framed messages.
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let mut reader = std::io::BufReader::new(stdout);
            loop {
                let mut headers = String::new();
                loop {
                    let mut line = String::new();
                    match reader.read_line(&mut line) {
                        Ok(0) => return,
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
                        return;
                    }
                }
            }
        });

        // Background stderr reader: forwards lines so the pipe never blocks
        // and so callers (e.g. the completion-burst sensor) can parse log
        // metrics.
        let (stderr_tx, stderr_rx) = mpsc::channel();
        std::thread::spawn(move || {
            let reader = std::io::BufReader::new(stderr);
            for line in reader.lines() {
                match line {
                    Ok(line) => {
                        if stderr_tx.send(line).is_err() {
                            return;
                        }
                    }
                    Err(_) => return,
                }
            }
        });

        Ok(LspTestClient {
            process,
            stdin,
            rx,
            stderr_rx,
            next_id: AtomicI64::new(1),
        })
    }

    /// Write a raw JSON-RPC message to the server's stdin.
    pub fn write_message(&mut self, msg: &Value) -> Result<(), Box<dyn std::error::Error>> {
        let body = serde_json::to_string(msg)?;
        let header = format!("Content-Length: {}\r\n\r\n", body.len());
        self.stdin.write_all(header.as_bytes())?;
        self.stdin.write_all(body.as_bytes())?;
        self.stdin.flush()?;
        Ok(())
    }

    /// Send a JSON-RPC request and wait for the matching response (by id).
    /// Server-initiated requests are answered with empty results so the
    /// server does not time out.
    pub fn send_request(
        &mut self,
        method: &str,
        params: Value,
    ) -> Result<Value, Box<dyn std::error::Error>> {
        self.send_request_and_capture_requests(method, params, None)
            .map(|(response, _)| response)
    }

    /// Send a JSON-RPC request, returning both the response and any captured
    /// server→client requests with the given method.
    pub fn send_request_and_capture_requests(
        &mut self,
        method: &str,
        params: Value,
        watched_method: Option<&str>,
    ) -> Result<(Value, Vec<Value>), Box<dyn std::error::Error>> {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let mut captured_requests = Vec::new();

        let request = json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params
        });

        self.write_message(&request)?;

        let deadline = Instant::now() + Duration::from_secs(30);
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(
                    format!("Timeout waiting for response to {} (id={})", method, id).into(),
                );
            }

            let msg = match self.rx.recv_timeout(remaining) {
                Ok(msg) => msg,
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    return Err(
                        format!("Timeout waiting for response to {} (id={})", method, id).into(),
                    );
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    return Err("Server stdout closed".into());
                }
            };

            if let Some(msg_id) = msg.get("id") {
                if msg_id.as_i64() == Some(id) && msg.get("method").is_none() {
                    if let Some(expected_method) = watched_method {
                        self.capture_server_requests(
                            expected_method,
                            &mut captured_requests,
                            Duration::from_millis(250),
                        );
                    }
                    return Ok((msg, captured_requests));
                }

                if msg.get("method").is_some() {
                    if let Some(expected_method) = watched_method {
                        if msg.get("method").and_then(|m| m.as_str()) == Some(expected_method) {
                            captured_requests.push(msg.clone());
                        }
                    }

                    let result = server_request_result(&msg);
                    let reply = json!({
                        "jsonrpc": "2.0",
                        "id": msg_id,
                        "result": result
                    });
                    self.write_message(&reply)?;
                }
            }
        }
    }

    /// Send a JSON-RPC notification (no response expected).
    pub fn send_notification(
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
    pub fn drain_messages(&mut self, timeout: Duration) {
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            match self.rx.recv_timeout(remaining) {
                Ok(msg) => {
                    if let (Some(id), Some(_method)) = (msg.get("id"), msg.get("method")) {
                        let result = server_request_result(&msg);
                        let reply = json!({
                            "jsonrpc": "2.0",
                            "id": id,
                            "result": result
                        });
                        let _ = self.write_message(&reply);
                    }
                }
                Err(_) => break,
            }
        }
    }

    pub fn capture_server_requests(
        &mut self,
        watched_method: &str,
        captured_requests: &mut Vec<Value>,
        timeout: Duration,
    ) {
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }

            let msg = match self.rx.recv_timeout(remaining) {
                Ok(msg) => msg,
                Err(_) => break,
            };

            if msg.get("method").and_then(|m| m.as_str()) == Some(watched_method) {
                captured_requests.push(msg.clone());
            }

            if let (Some(id), Some(_method)) = (msg.get("id"), msg.get("method")) {
                let result = server_request_result(&msg);
                let reply = json!({
                    "jsonrpc": "2.0",
                    "id": id,
                    "result": result
                });
                let _ = self.write_message(&reply);
            }
        }
    }

    /// Drain messages for the given duration, collecting notifications with
    /// the specified method. Server-initiated requests are answered.
    pub fn collect_notifications(&mut self, method: &str, timeout: Duration) -> Vec<Value> {
        let mut notifications = Vec::new();
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            match self.rx.recv_timeout(remaining) {
                Ok(msg) => {
                    if let (Some(id), Some(_)) = (msg.get("id"), msg.get("method")) {
                        let result = server_request_result(&msg);
                        let reply = json!({
                            "jsonrpc": "2.0",
                            "id": id,
                            "result": result
                        });
                        let _ = self.write_message(&reply);
                    }
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

    /// Send a request without blocking on its response. Returns the JSON-RPC
    /// id assigned to the request so the caller can match the response.
    pub fn send_request_no_wait(
        &mut self,
        method: &str,
        params: Value,
    ) -> Result<i64, Box<dyn std::error::Error>> {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let request = json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params
        });
        self.write_message(&request)?;
        Ok(id)
    }

    /// Wait for a JSON-RPC response with the given id. Server→client
    /// requests received while waiting are answered with empty results.
    pub fn await_response_for(
        &mut self,
        id: i64,
        timeout: Duration,
    ) -> Result<Value, Box<dyn std::error::Error>> {
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(format!("Timeout waiting for response id={id}").into());
            }

            let msg = match self.rx.recv_timeout(remaining) {
                Ok(msg) => msg,
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    return Err(format!("Timeout waiting for response id={id}").into());
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    return Err("Server stdout closed".into());
                }
            };

            if let Some(msg_id) = msg.get("id") {
                if msg_id.as_i64() == Some(id) && msg.get("method").is_none() {
                    return Ok(msg);
                }
                if msg.get("method").is_some() {
                    let result = server_request_result(&msg);
                    let reply = json!({
                        "jsonrpc": "2.0",
                        "id": msg_id,
                        "result": result
                    });
                    self.write_message(&reply)?;
                }
            }
        }
    }

    /// Drain stderr lines emitted so far. Non-blocking.
    pub fn drain_stderr(&mut self) -> Vec<String> {
        let mut out = Vec::new();
        while let Ok(line) = self.stderr_rx.try_recv() {
            out.push(line);
        }
        out
    }

    /// Drain stderr lines for up to `timeout`, returning all lines seen.
    pub fn drain_stderr_for(&mut self, timeout: Duration) -> Vec<String> {
        let mut out = Vec::new();
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            match self.stderr_rx.recv_timeout(remaining) {
                Ok(line) => out.push(line),
                Err(_) => break,
            }
        }
        out
    }

    pub fn initialize(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        self.initialize_with_root_and_client_and_options("file:///tmp/test-project", None, None)
    }

    pub fn initialize_with_root(
        &mut self,
        root_uri: &str,
    ) -> Result<(), Box<dyn std::error::Error>> {
        self.initialize_with_root_and_client_and_options(root_uri, None, None)
    }

    pub fn initialize_with_root_and_client(
        &mut self,
        root_uri: &str,
        client_name: Option<&str>,
    ) -> Result<(), Box<dyn std::error::Error>> {
        self.initialize_with_root_and_client_and_options(root_uri, client_name, None)
    }

    /// Initialize with optional `initializationOptions` (e.g. to enable
    /// `completionMetrics`).
    pub fn initialize_with_root_and_client_and_options(
        &mut self,
        root_uri: &str,
        client_name: Option<&str>,
        init_options: Option<Value>,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let mut params = json!({
            "processId": std::process::id(),
            "rootUri": root_uri,
            "clientInfo": client_name.map(|name| json!({ "name": name })),
            "capabilities": {
                "textDocument": {
                    "hover": { "dynamicRegistration": false },
                    "rename": { "dynamicRegistration": false },
                    "definition": { "dynamicRegistration": false }
                }
            }
        });

        if let Some(opts) = init_options {
            params
                .as_object_mut()
                .expect("initialize params object")
                .insert("initializationOptions".to_string(), opts);
        }

        let response = self.send_request("initialize", params)?;

        let result = response
            .get("result")
            .ok_or("No result in initialize response")?;
        let capabilities = result
            .get("capabilities")
            .ok_or("No capabilities in response")?;

        let hover_provider = capabilities.get("hoverProvider");
        if hover_provider.is_none() || !hover_provider.unwrap().as_bool().unwrap_or(false) {
            return Err("Server doesn't support hover".into());
        }

        let implementation_provider = capabilities.get("implementationProvider");
        if implementation_provider.is_none()
            || !implementation_provider.unwrap().as_bool().unwrap_or(false)
        {
            return Err("Server doesn't support go-to-implementation".into());
        }

        self.send_notification("initialized", json!({}))?;

        // Sidecar startup is a background task spawned from `initialized`;
        // give it time to come up before the caller starts measuring.
        self.drain_messages(Duration::from_secs(5));

        Ok(())
    }

    pub fn open_document(
        &mut self,
        uri: &str,
        text: &str,
    ) -> Result<(), Box<dyn std::error::Error>> {
        self.open_document_with_language(uri, "kotlin", text)
    }

    pub fn open_document_with_language(
        &mut self,
        uri: &str,
        language_id: &str,
        text: &str,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let params = json!({
            "textDocument": {
                "uri": uri,
                "languageId": language_id,
                "version": 1,
                "text": text
            }
        });

        self.send_notification("textDocument/didOpen", params)?;
        self.drain_messages(Duration::from_secs(3));

        Ok(())
    }

    pub fn change_document(
        &mut self,
        uri: &str,
        version: i32,
        text: &str,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let params = json!({
            "textDocument": { "uri": uri, "version": version },
            "contentChanges": [ { "text": text } ]
        });
        self.send_notification("textDocument/didChange", params)
    }

    pub fn hover(
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

        if let Some(result) = response.get("result") {
            if !result.is_null() {
                if let Some(contents) = result.get("contents") {
                    if let Some(value) = contents.get("value") {
                        return Ok(Some(value.as_str().unwrap_or("").to_string()));
                    }
                }
            }
        }

        if let Some(error) = response.get("error") {
            let message = error
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("Unknown error");
            return Err(format!("Hover request failed: {}", message).into());
        }

        Ok(None)
    }

    pub fn definition(
        &mut self,
        uri: &str,
        line: u32,
        character: u32,
    ) -> Result<Option<Value>, Box<dyn std::error::Error>> {
        let params = json!({
            "textDocument": { "uri": uri },
            "position": { "line": line, "character": character }
        });

        let response = self.send_request("textDocument/definition", params)?;

        if let Some(result) = response.get("result") {
            if result.is_null() {
                return Ok(None);
            }
            return Ok(Some(result.clone()));
        }

        if let Some(error) = response.get("error") {
            let message = error
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("Unknown error");
            return Err(format!("Definition request failed: {}", message).into());
        }

        Ok(None)
    }

    pub fn implementation(
        &mut self,
        uri: &str,
        line: u32,
        character: u32,
    ) -> Result<Option<Value>, Box<dyn std::error::Error>> {
        let params = json!({
            "textDocument": { "uri": uri },
            "position": { "line": line, "character": character }
        });

        let response = self.send_request("textDocument/implementation", params)?;

        if let Some(result) = response.get("result") {
            if result.is_null() {
                return Ok(None);
            }
            return Ok(Some(result.clone()));
        }

        if let Some(error) = response.get("error") {
            let message = error
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("Unknown error");
            return Err(format!("Implementation request failed: {}", message).into());
        }

        Ok(None)
    }
}

impl Drop for LspTestClient {
    fn drop(&mut self) {
        let shutdown = json!({
            "jsonrpc": "2.0",
            "id": 9999,
            "method": "shutdown",
            "params": null
        });
        let _ = self.write_message(&shutdown);
        std::thread::sleep(Duration::from_millis(500));
        let _ = self.process.kill();
    }
}

pub fn server_request_result(msg: &Value) -> Value {
    match msg.get("method").and_then(|method| method.as_str()) {
        Some("window/showDocument") => json!({ "success": true }),
        Some("workspace/applyEdit") => json!({ "applied": true }),
        _ => Value::Null,
    }
}

/// Process-wide lock for tests that mutate environment variables.
pub fn env_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

/// Discover the `kotlin-analyzer` binary using the precedence documented in
/// ka-g342: explicit override, `KA_BIN`, then known `target/release` paths
/// under the repository root. Falls back to building the server crate when
/// nothing is found.
pub fn discover_lsp_binary(
    explicit: Option<&Path>,
    repo_root: &Path,
) -> Result<PathBuf, Box<dyn std::error::Error>> {
    if let Some(path) = explicit {
        if !path.exists() {
            return Err(format!("--lsp-binary path does not exist: {}", path.display()).into());
        }
        return Ok(path.to_path_buf());
    }

    if let Ok(env_path) = std::env::var("KA_BIN") {
        let path = PathBuf::from(env_path);
        if !path.exists() {
            return Err(format!("KA_BIN points to missing path: {}", path.display()).into());
        }
        return Ok(path);
    }

    let candidates = [
        repo_root.join("server/target/release/kotlin-analyzer"),
        repo_root.join("target/release/kotlin-analyzer"),
    ];
    for candidate in &candidates {
        if candidate.exists() {
            return Ok(candidate.clone());
        }
    }

    eprintln!(
        "kotlin-analyzer binary not found in {:?}; building with `cargo build --release` in {}",
        candidates,
        repo_root.join("server").display()
    );
    let status = std::process::Command::new("cargo")
        .args(["build", "--release"])
        .current_dir(repo_root.join("server"))
        .status()?;
    if !status.success() {
        return Err("cargo build --release failed in server/".into());
    }

    let built = repo_root.join("server/target/release/kotlin-analyzer");
    if built.exists() {
        Ok(built)
    } else {
        Err(format!(
            "expected kotlin-analyzer at {} after build, but it does not exist",
            built.display()
        )
        .into())
    }
}
