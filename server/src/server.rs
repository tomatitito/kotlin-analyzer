use std::collections::HashMap;
use std::path::Path;
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use lsp_types::request::Request as LspRequest;
use lsp_types::*;
use serde::Deserialize;
use serde_json::Value;
use tokio::io::AsyncWriteExt;
use tokio::process::Command;
use tokio::sync::Mutex;
use tower_lsp::jsonrpc::{Error as JsonRpcError, ErrorCode, Result as LspResult};
use tower_lsp::lsp_types;
use tower_lsp::{Client, LanguageServer};

use crate::bridge::{Bridge, SidecarState};
use crate::config::{Config, FormattingTool};
use crate::project;
use crate::runtime;
use crate::state::{DocumentKind, DocumentStore};

const ANALYZER_COMMAND_CONTRACT_JSON: &str = include_str!("../../protocol/analyzer-commands.json");

#[derive(Debug, Deserialize)]
struct AnalyzerCommandContract {
    commands: AnalyzerCommandEntries,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AnalyzerCommandEntries {
    open_test_target: AnalyzerCommandDefinition,
    create_and_open_test_target: AnalyzerCommandDefinition,
}

#[derive(Debug, Deserialize)]
struct AnalyzerCommandDefinition {
    id: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct CommandSelection {
    start_line: u32,
    start_character: u32,
    end_line: u32,
    end_character: u32,
}

impl CommandSelection {
    fn into_range(self) -> Range {
        Range {
            start: Position::new(self.start_line, self.start_character),
            end: Position::new(self.end_line, self.end_character),
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct OpenTestTargetArgs {
    target_uri: String,
    selection: Option<CommandSelection>,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct CreateAndOpenTestTargetArgs {
    target_uri: String,
    target_path: String,
    initial_contents: String,
    selection: Option<CommandSelection>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum AnalyzerCommandRequest {
    OpenTestTarget(OpenTestTargetArgs),
    CreateAndOpenTestTarget(CreateAndOpenTestTargetArgs),
}

enum CompatibleShowDocument {}

impl LspRequest for CompatibleShowDocument {
    type Params = ShowDocumentParams;
    type Result = Option<ShowDocumentResult>;
    const METHOD: &'static str = "window/showDocument";
}

fn analyzer_command_contract() -> &'static AnalyzerCommandContract {
    static CONTRACT: OnceLock<AnalyzerCommandContract> = OnceLock::new();
    CONTRACT.get_or_init(|| {
        serde_json::from_str(ANALYZER_COMMAND_CONTRACT_JSON)
            .expect("protocol/analyzer-commands.json must be valid")
    })
}

fn supported_analyzer_command_ids() -> Vec<String> {
    let contract = analyzer_command_contract();
    vec![
        contract.commands.open_test_target.id.clone(),
        contract.commands.create_and_open_test_target.id.clone(),
    ]
}

fn invalid_params_error<M: Into<String>>(message: M) -> JsonRpcError {
    JsonRpcError {
        code: ErrorCode::InvalidParams,
        message: message.into().into(),
        data: None,
    }
}

fn request_failed_error<M: Into<String>>(message: M) -> JsonRpcError {
    JsonRpcError {
        code: ErrorCode::ServerError(-32001),
        message: message.into().into(),
        data: None,
    }
}

fn parse_command_payload<T>(arguments: Vec<Value>, command_id: &str) -> Result<T, JsonRpcError>
where
    T: for<'de> Deserialize<'de>,
{
    if arguments.len() != 1 {
        return Err(invalid_params_error(format!(
            "{command_id} requires exactly one argument object"
        )));
    }

    serde_json::from_value(arguments.into_iter().next().expect("checked len == 1"))
        .map_err(|e| invalid_params_error(format!("invalid arguments for {command_id}: {e}")))
}

fn parse_analyzer_command_request(
    params: ExecuteCommandParams,
) -> Result<AnalyzerCommandRequest, JsonRpcError> {
    let command_id = params.command;
    let arguments = params.arguments;
    let contract = analyzer_command_contract();

    if command_id == contract.commands.open_test_target.id {
        let payload = parse_command_payload(arguments, &command_id)?;
        return Ok(AnalyzerCommandRequest::OpenTestTarget(payload));
    }

    if command_id == contract.commands.create_and_open_test_target.id {
        let payload = parse_command_payload(arguments, &command_id)?;
        return Ok(AnalyzerCommandRequest::CreateAndOpenTestTarget(payload));
    }

    Err(invalid_params_error(format!(
        "unsupported analyzer command: {command_id}"
    )))
}

fn parse_workspace_edits(result: &Value) -> HashMap<Url, Vec<TextEdit>> {
    let edits_array = match result.get("edits").and_then(|e| e.as_array()) {
        Some(arr) => arr,
        None => return HashMap::new(),
    };

    let mut changes: HashMap<Url, Vec<TextEdit>> = HashMap::new();

    for edit in edits_array {
        let uri_str = match edit.get("uri").and_then(|u| u.as_str()) {
            Some(s) => s,
            None => continue,
        };
        let uri = match Url::parse(uri_str) {
            Ok(u) => u,
            Err(_) => continue,
        };

        let range = match edit.get("range") {
            Some(r) => r,
            None => continue,
        };

        let start_line = range
            .get("startLine")
            .and_then(|l| l.as_u64())
            .map(|l| l.saturating_sub(1) as u32)
            .unwrap_or(0);
        let start_col = range
            .get("startColumn")
            .and_then(|c| c.as_u64())
            .unwrap_or(0) as u32;
        let end_line = range
            .get("endLine")
            .and_then(|l| l.as_u64())
            .map(|l| l.saturating_sub(1) as u32)
            .unwrap_or(start_line);
        let end_col = range.get("endColumn").and_then(|c| c.as_u64()).unwrap_or(0) as u32;

        let new_text = match edit.get("newText").and_then(|t| t.as_str()) {
            Some(t) => t.to_string(),
            None => continue,
        };

        changes.entry(uri).or_default().push(TextEdit {
            range: Range {
                start: Position::new(start_line, start_col),
                end: Position::new(end_line, end_col),
            },
            new_text,
        });
    }

    changes
}

fn response_version(result: &Value) -> Option<i32> {
    result
        .get("version")
        .and_then(|value| value.as_i64())
        .and_then(|value| i32::try_from(value).ok())
}

fn analyze_edits_are_current(
    expected_version: i32,
    current_version: Option<i32>,
    result: &Value,
) -> bool {
    let response_version = response_version(result).unwrap_or(expected_version);
    response_version == expected_version && current_version == Some(expected_version)
}

fn parse_code_action_command(action: &Value) -> Option<lsp_types::Command> {
    let command = action.get("command")?.clone();
    match serde_json::from_value::<lsp_types::Command>(command) {
        Ok(command) => Some(command),
        Err(error) => {
            tracing::warn!("ignoring malformed code action command: {}", error);
            None
        }
    }
}

fn parse_code_actions_result(result: &Value) -> CodeActionResponse {
    let actions_array = match result.get("actions").and_then(|a| a.as_array()) {
        Some(arr) => arr,
        None => return Vec::new(),
    };

    actions_array
        .iter()
        .filter_map(|action| {
            let title = action.get("title")?.as_str()?.to_string();
            let kind = action
                .get("kind")
                .and_then(|k| k.as_str())
                .map(|k| CodeActionKind::from(k.to_string()));

            let edits = parse_workspace_edits(action);
            let edit = if edits.is_empty() {
                None
            } else {
                Some(WorkspaceEdit {
                    changes: Some(edits),
                    document_changes: None,
                    change_annotations: None,
                })
            };

            Some(CodeActionOrCommand::CodeAction(CodeAction {
                title,
                kind,
                diagnostics: None,
                edit,
                command: parse_code_action_command(action),
                is_preferred: None,
                disabled: None,
                data: None,
            }))
        })
        .collect()
}

fn temporary_target_path(target_path: &Path) -> PathBuf {
    let file_name = target_path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("kotlin-analyzer-target");
    let unique_suffix = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);

    target_path.with_file_name(format!(
        ".{file_name}.kotlin-analyzer.tmp-{}-{unique_suffix}",
        std::process::id()
    ))
}

/// The main language server implementation.
pub struct KotlinLanguageServer {
    client: Client,
    documents: Arc<Mutex<DocumentStore>>,
    bridge: Arc<Mutex<Option<Arc<Bridge>>>>,
    config: Arc<Mutex<Config>>,
    project_root: Arc<Mutex<Option<PathBuf>>>,
    debounce_tx: Arc<Mutex<Option<tokio::sync::mpsc::Sender<Url>>>>,
}

impl KotlinLanguageServer {
    pub fn new(client: Client) -> Self {
        Self {
            client,
            documents: Arc::new(Mutex::new(DocumentStore::default())),
            bridge: Arc::new(Mutex::new(None)),
            config: Arc::new(Mutex::new(Config::default())),
            project_root: Arc::new(Mutex::new(None)),
            debounce_tx: Arc::new(Mutex::new(None)),
        }
    }

    /// Creates a "server not initialized" error for when the sidecar bridge is unavailable.
    ///
    /// Returns LSP error code -32002, signaling to clients that the server is still starting up
    /// and they should retry the request later.
    fn server_not_initialized_error<T>() -> LspResult<T> {
        Err(JsonRpcError {
            code: ErrorCode::ServerError(-32002),
            message: "Server not initialized (sidecar still starting)".into(),
            data: None,
        })
    }

    /// Returns a cloned Arc to the bridge, releasing the mutex immediately.
    /// This prevents holding the bridge mutex during long-running sidecar requests,
    /// which would block all other LSP handlers.
    async fn get_bridge(&self) -> Option<Arc<Bridge>> {
        let guard = self.bridge.lock().await;
        guard.as_ref().map(Arc::clone)
    }

    /// Publishes diagnostics for a document by requesting analysis from the sidecar.
    async fn analyze_document(&self, uri: &Url) {
        tracing::debug!("analyze_document: {}", uri);

        // Skip .kts build scripts — they produce hundreds of false positives
        // because Gradle DSL scripts need a runtime environment the sidecar doesn't replicate.
        if is_gradle_script(uri) {
            tracing::debug!("analyze_document: skipping build script {}", uri);
            return;
        }

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => {
                tracing::debug!(
                    "analyze_document: bridge is None (sidecar still starting), skipping"
                );
                return;
            }
        };

        let state = bridge.state().await;
        if state != SidecarState::Ready {
            tracing::warn!("analyze_document: sidecar state is {:?}, skipping", state);
            return;
        }

        let (text, version) = {
            let documents = self.documents.lock().await;
            match documents.get(uri) {
                Some(d) if d.kind.supports_kotlin_analysis() => (d.text.clone(), d.version),
                Some(_) => {
                    tracing::debug!("analyze_document: skipping non-Kotlin document {}", uri);
                    return;
                }
                None => return,
            }
        };

        // Send the document content to the sidecar
        let _ = bridge
            .notify(
                "textDocument/didChange",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "version": version,
                    "text": text,
                })),
            )
            .await;

        // Request analysis
        match bridge
            .request(
                "analyze",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "version": version,
                })),
            )
            .await
        {
            Ok(result) => {
                tracing::debug!(
                    "analyze_document: raw sidecar response for {}: {}",
                    uri,
                    result
                );
                let diagnostics = self.parse_diagnostics(&result);
                tracing::debug!(
                    "analyze_document: {} returned {} diagnostics",
                    uri,
                    diagnostics.len()
                );
                for d in &diagnostics {
                    tracing::debug!(
                        "  diagnostic: {:?} at L{}:{} - {}",
                        d.severity,
                        d.range.start.line,
                        d.range.start.character,
                        d.message
                    );
                }
                // Cache diagnostics so they survive didClose/didOpen tab switches
                {
                    let mut documents = self.documents.lock().await;
                    documents.set_diagnostics(uri.clone(), diagnostics.clone());
                }
                self.apply_analyzed_edits(uri, &result).await;
                self.client
                    .publish_diagnostics(uri.clone(), diagnostics, None)
                    .await;
            }
            Err(e) => {
                tracing::warn!("analyze_document: analysis failed for {}: {}", uri, e);
            }
        }
    }

    async fn execute_analyzer_command(&self, request: AnalyzerCommandRequest) -> LspResult<Value> {
        match request {
            AnalyzerCommandRequest::OpenTestTarget(args) => {
                let target_uri = Url::parse(&args.target_uri).map_err(|error| {
                    invalid_params_error(format!("invalid targetUri for openTestTarget: {error}"))
                })?;

                self.show_document(target_uri, args.selection).await?;
                Ok(serde_json::json!({ "shown": true }))
            }
            AnalyzerCommandRequest::CreateAndOpenTestTarget(args) => {
                let target_uri = Url::parse(&args.target_uri).map_err(|error| {
                    invalid_params_error(format!(
                        "invalid targetUri for createAndOpenTestTarget: {error}"
                    ))
                })?;

                let target_path = PathBuf::from(&args.target_path);
                let created = !target_path.exists();
                self.create_target_file_if_missing(&target_path, &args.initial_contents)
                    .await?;
                self.show_document(target_uri, args.selection).await?;

                Ok(serde_json::json!({
                    "created": created,
                    "shown": true
                }))
            }
        }
    }

    async fn create_target_file_if_missing(
        &self,
        target_path: &Path,
        initial_contents: &str,
    ) -> LspResult<()> {
        if target_path.exists() {
            return Ok(());
        }

        if let Some(parent) = target_path.parent() {
            tokio::fs::create_dir_all(parent).await.map_err(|error| {
                request_failed_error(format!(
                    "failed to create directories for {}: {}",
                    target_path.display(),
                    error
                ))
            })?;
        }

        let temp_path = temporary_target_path(target_path);
        let write_result = async {
            let mut file = tokio::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temp_path)
                .await?;
            file.write_all(initial_contents.as_bytes()).await?;
            file.flush().await?;
            tokio::fs::rename(&temp_path, target_path).await
        }
        .await;

        match write_result {
            Ok(()) => Ok(()),
            Err(error) => {
                let _ = tokio::fs::remove_file(&temp_path).await;
                Err(request_failed_error(format!(
                    "failed to create {}: {}",
                    target_path.display(),
                    error
                )))
            }
        }
    }

    async fn show_document(&self, uri: Url, selection: Option<CommandSelection>) -> LspResult<()> {
        let shown = self
            .client
            .send_request::<CompatibleShowDocument>(ShowDocumentParams {
                uri,
                external: Some(false),
                take_focus: Some(true),
                selection: selection.map(CommandSelection::into_range),
            })
            .await
            .map_err(|error| {
                request_failed_error(format!("window/showDocument failed: {error}"))
            })?;

        if show_document_acknowledged(shown) {
            Ok(())
        } else {
            Err(request_failed_error(
                "window/showDocument was not acknowledged",
            ))
        }
    }

    fn parse_diagnostics(&self, result: &Value) -> Vec<Diagnostic> {
        let diagnostics = match result.get("diagnostics").and_then(|d| d.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        diagnostics
            .iter()
            .filter_map(|d| {
                let severity = match d.get("severity")?.as_str()? {
                    "ERROR" => DiagnosticSeverity::ERROR,
                    "WARNING" => DiagnosticSeverity::WARNING,
                    "INFO" | "INFORMATION" => DiagnosticSeverity::INFORMATION,
                    "HINT" => DiagnosticSeverity::HINT,
                    _ => DiagnosticSeverity::ERROR,
                };

                let message = d.get("message")?.as_str()?.to_string();
                let line = d.get("line")?.as_u64()?.saturating_sub(1) as u32;
                let col = d.get("column").and_then(|c| c.as_u64()).unwrap_or(0);
                let end_line = d
                    .get("endLine")
                    .and_then(|l| l.as_u64())
                    .map(|l| l.saturating_sub(1) as u32)
                    .unwrap_or(line);
                let end_col = d
                    .get("endColumn")
                    .and_then(|c| c.as_u64())
                    .unwrap_or(col + 1) as u32;
                let col = col as u32;

                Some(Diagnostic {
                    range: Range {
                        start: Position::new(line, col),
                        end: Position::new(end_line, end_col),
                    },
                    severity: Some(severity),
                    code: d
                        .get("code")
                        .and_then(|c| c.as_str())
                        .map(|c| NumberOrString::String(c.to_string())),
                    source: Some("kotlin-analyzer".into()),
                    message,
                    ..Default::default()
                })
            })
            .collect()
    }

    fn parse_result_version(result: &Value) -> Option<i32> {
        result
            .get("version")
            .and_then(|value| value.as_i64())
            .and_then(|value| i32::try_from(value).ok())
    }

    async fn apply_analyzed_edits(&self, uri: &Url, result: &Value) {
        let planned_version = match Self::parse_result_version(result) {
            Some(version) => version,
            None => return,
        };

        let current_version = {
            let documents = self.documents.lock().await;
            documents.get(uri).map(|doc| doc.version)
        };
        if !analyze_edits_are_current(planned_version, current_version, result) {
            tracing::debug!(
                "skipping analyzer edit for {} because version changed (planned={}, current={:?}, response={:?})",
                uri,
                planned_version,
                current_version,
                response_version(result),
            );
            return;
        }

        let edits = parse_workspace_edits(result);
        if edits.is_empty() {
            return;
        }

        let edit = WorkspaceEdit {
            changes: Some(edits),
            document_changes: None,
            change_annotations: None,
        };

        match self.client.apply_edit(edit).await {
            Ok(response) if response.applied => {}
            Ok(response) => {
                tracing::warn!(
                    "client rejected analyzer edit for {}: {:?}",
                    uri,
                    response.failure_reason,
                );
            }
            Err(error) => {
                tracing::warn!("failed to apply analyzer edit for {}: {}", uri, error);
            }
        }
    }

    /// Starts the debounce loop for document analysis.
    fn start_debounce_loop(&self) -> tokio::sync::mpsc::Sender<Url> {
        let (tx, mut rx) = tokio::sync::mpsc::channel::<Url>(64);

        let client = self.client.clone();
        let documents = Arc::clone(&self.documents);
        let bridge = Arc::clone(&self.bridge);

        tokio::spawn(async move {
            let mut pending: Option<Url> = None;
            let debounce_duration = Duration::from_millis(300);

            loop {
                tokio::select! {
                    uri = rx.recv() => {
                        match uri {
                            Some(uri) => {
                                pending = Some(uri);
                            }
                            None => break,
                        }
                    }
                    _ = tokio::time::sleep(debounce_duration), if pending.is_some() => {
                        if let Some(uri) = pending.take() {
                            // Skip Gradle build scripts
                            if is_gradle_script(&uri) {
                                continue;
                            }
                            let bridge_arc = {
                                let guard = bridge.lock().await;
                                guard.as_ref().map(Arc::clone)
                            };
                            if let Some(bridge) = bridge_arc {
                                if bridge.state().await == SidecarState::Ready {
                                    let document_store = documents.lock().await;
                                    if let Some(doc) = document_store.get(&uri) {
                                        let text = doc.text.clone();
                                        let version = doc.version;
                                        let kind = doc.kind;
                                        drop(document_store);

                                        let _ = bridge.notify(kind.did_change_method(), Some(serde_json::json!({
                                            "uri": uri.as_str(),
                                            "version": version,
                                            "text": text,
                                        }))).await;

                                        if !kind.supports_kotlin_analysis() {
                                            continue;
                                        }

                                        match bridge.request("analyze", Some(serde_json::json!({
                                            "uri": uri.as_str(),
                                            "version": version,
                                        }))).await {
                                            Ok(result) => {
                                                if let Some(planned_version) = Self::parse_result_version(&result) {
                                                    let current_version = {
                                                        let document_store = documents.lock().await;
                                                        document_store.get(&uri).map(|doc| doc.version)
                                                    };
                                                    if current_version == Some(planned_version) {
                                                        let edits = parse_workspace_edits(&result);
                                                        if !edits.is_empty() {
                                                            let edit = WorkspaceEdit {
                                                                changes: Some(edits),
                                                                document_changes: None,
                                                                change_annotations: None,
                                                            };
                                                            match client.apply_edit(edit).await {
                                                                Ok(response) if response.applied => {}
                                                                Ok(response) => tracing::warn!(
                                                                    "client rejected analyzer edit for {}: {:?}",
                                                                    uri,
                                                                    response.failure_reason,
                                                                ),
                                                                Err(error) => tracing::warn!(
                                                                    "failed to apply analyzer edit for {}: {}",
                                                                    uri,
                                                                    error,
                                                                ),
                                                            }
                                                        }
                                                    }
                                                }
                                                let diagnostics = parse_diagnostics_static(&result);
                                                client.publish_diagnostics(uri, diagnostics, None).await;
                                            }
                                            Err(e) => {
                                                tracing::warn!("debounced analysis failed: {}", e);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });

        tx
    }
}

/// Returns true if the URI points to a Gradle build script (.gradle.kts in any
/// location, or .kts files inside buildSrc/ or gradle/ directories).
fn is_gradle_script(uri: &Url) -> bool {
    if let Ok(path) = uri.to_file_path() {
        let path_str = path.to_string_lossy();
        path_str.ends_with(".gradle.kts")
            || (path_str.ends_with(".kts")
                && (path_str.contains("/buildSrc/") || path_str.contains("/gradle/")))
    } else {
        false
    }
}

fn parse_diagnostics_static(result: &Value) -> Vec<Diagnostic> {
    let diagnostics = match result.get("diagnostics").and_then(|d| d.as_array()) {
        Some(arr) => arr,
        None => return Vec::new(),
    };

    diagnostics
        .iter()
        .filter_map(|d| {
            let severity = match d.get("severity")?.as_str()? {
                "ERROR" => DiagnosticSeverity::ERROR,
                "WARNING" => DiagnosticSeverity::WARNING,
                "INFO" | "INFORMATION" => DiagnosticSeverity::INFORMATION,
                "HINT" => DiagnosticSeverity::HINT,
                _ => DiagnosticSeverity::ERROR,
            };

            let message = d.get("message")?.as_str()?.to_string();
            let line = d.get("line")?.as_u64()?.saturating_sub(1) as u32;
            let col = d.get("column").and_then(|c| c.as_u64()).unwrap_or(0);
            let end_line = d
                .get("endLine")
                .and_then(|l| l.as_u64())
                .map(|l| l.saturating_sub(1) as u32)
                .unwrap_or(line);
            let end_col = d
                .get("endColumn")
                .and_then(|c| c.as_u64())
                .unwrap_or(col + 1) as u32;
            let col = col as u32;

            Some(Diagnostic {
                range: Range {
                    start: Position::new(line, col),
                    end: Position::new(end_line, end_col),
                },
                severity: Some(severity),
                code: d
                    .get("code")
                    .and_then(|c| c.as_str())
                    .map(|c| NumberOrString::String(c.to_string())),
                source: Some("kotlin-analyzer".into()),
                message,
                ..Default::default()
            })
        })
        .collect()
}

#[tower_lsp::async_trait]
impl LanguageServer for KotlinLanguageServer {
    async fn initialize(&self, params: InitializeParams) -> LspResult<InitializeResult> {
        tracing::info!("kotlin-analyzer: initializing");

        // Store project root (project model resolution happens in initialized())
        // Walk up from the rootUri to find the actual project root containing
        // build system markers. Zed sometimes sets rootUri to a deep source
        // directory (e.g. when opening a single file), so we need to find the
        // real project root that has build.gradle.kts, pom.xml, etc.
        if let Some(root_uri) = params.root_uri {
            if let Ok(path) = root_uri.to_file_path() {
                let resolved =
                    project::prefer_nested_build_root(&project::find_project_root(&path));
                if resolved != path {
                    tracing::info!(
                        "resolved project root from {} to {}",
                        path.display(),
                        resolved.display()
                    );
                }
                let mut project_root = self.project_root.lock().await;
                *project_root = Some(resolved);
            }
        }

        // Parse initialization options as config
        if let Some(options) = params.initialization_options {
            if let Ok(config) = serde_json::from_value::<Config>(options) {
                let mut c = self.config.lock().await;
                *c = config;
            }
        }

        // Start the debounce loop
        let tx = self.start_debounce_loop();
        {
            let mut debounce = self.debounce_tx.lock().await;
            *debounce = Some(tx);
        }

        // File watchers for build files and .editorconfig
        let file_watchers = vec![
            FileSystemWatcher {
                glob_pattern: GlobPattern::String("**/*.gradle.kts".into()),
                kind: None,
            },
            FileSystemWatcher {
                glob_pattern: GlobPattern::String("**/*.gradle".into()),
                kind: None,
            },
            FileSystemWatcher {
                glob_pattern: GlobPattern::String("**/gradle.properties".into()),
                kind: None,
            },
            FileSystemWatcher {
                glob_pattern: GlobPattern::String("**/.editorconfig".into()),
                kind: None,
            },
        ];

        let result = InitializeResult {
            capabilities: ServerCapabilities {
                text_document_sync: Some(TextDocumentSyncCapability::Options(
                    TextDocumentSyncOptions {
                        open_close: Some(true),
                        change: Some(TextDocumentSyncKind::FULL),
                        save: Some(TextDocumentSyncSaveOptions::SaveOptions(SaveOptions {
                            include_text: Some(false),
                        })),
                        ..Default::default()
                    },
                )),
                completion_provider: Some(CompletionOptions {
                    trigger_characters: Some(vec![".".into(), ":".into(), "@".into()]),
                    resolve_provider: Some(false),
                    ..Default::default()
                }),
                hover_provider: Some(HoverProviderCapability::Simple(true)),
                signature_help_provider: Some(SignatureHelpOptions {
                    trigger_characters: Some(vec!["(".into(), ",".into()]),
                    ..Default::default()
                }),
                definition_provider: Some(OneOf::Left(true)),
                references_provider: Some(OneOf::Left(true)),
                document_formatting_provider: Some(OneOf::Left(true)),
                rename_provider: Some(OneOf::Left(true)),
                code_action_provider: Some(CodeActionProviderCapability::Options(
                    CodeActionOptions {
                        code_action_kinds: Some(vec![
                            CodeActionKind::QUICKFIX,
                            CodeActionKind::REFACTOR,
                            CodeActionKind::SOURCE_ORGANIZE_IMPORTS,
                        ]),
                        ..Default::default()
                    },
                )),
                code_lens_provider: Some(CodeLensOptions {
                    resolve_provider: Some(false),
                }),
                inlay_hint_provider: Some(OneOf::Right(InlayHintServerCapabilities::Options(
                    InlayHintOptions {
                        work_done_progress_options: WorkDoneProgressOptions {
                            work_done_progress: Some(false),
                        },
                        resolve_provider: Some(false),
                    },
                ))),
                workspace: Some(WorkspaceServerCapabilities {
                    workspace_folders: None,
                    file_operations: None,
                }),
                execute_command_provider: Some(ExecuteCommandOptions {
                    commands: supported_analyzer_command_ids(),
                    work_done_progress_options: WorkDoneProgressOptions {
                        work_done_progress: Some(false),
                    },
                }),
                workspace_symbol_provider: Some(OneOf::Left(true)),
                semantic_tokens_provider: Some(
                    SemanticTokensServerCapabilities::SemanticTokensOptions(
                        SemanticTokensOptions {
                            legend: SemanticTokensLegend {
                                token_types: vec![
                                    SemanticTokenType::FUNCTION,
                                    SemanticTokenType::PARAMETER,
                                    SemanticTokenType::VARIABLE,
                                    SemanticTokenType::PROPERTY,
                                    SemanticTokenType::CLASS,
                                    SemanticTokenType::TYPE,
                                    SemanticTokenType::STRING,
                                    SemanticTokenType::COMMENT,
                                    SemanticTokenType::KEYWORD,
                                    SemanticTokenType::DECORATOR,
                                    SemanticTokenType::NUMBER,
                                    SemanticTokenType::ENUM_MEMBER,
                                    SemanticTokenType::TYPE_PARAMETER,
                                ],
                                token_modifiers: vec![],
                            },
                            full: Some(SemanticTokensFullOptions::Bool(true)),
                            range: None,
                            work_done_progress_options: WorkDoneProgressOptions {
                                work_done_progress: Some(false),
                            },
                        },
                    ),
                ),
                call_hierarchy_provider: Some(CallHierarchyServerCapability::Simple(true)),
                ..Default::default()
            },
            server_info: Some(ServerInfo {
                name: "kotlin-analyzer".into(),
                version: Some(env!("CARGO_PKG_VERSION").into()),
            }),
        };

        // Register file watchers dynamically since they need a registered client
        let client = self.client.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(100)).await;
            let registration = Registration {
                id: "watch-files".to_string(),
                method: "workspace/didChangeWatchedFiles".to_string(),
                register_options: Some(
                    serde_json::to_value(DidChangeWatchedFilesRegistrationOptions {
                        watchers: file_watchers,
                    })
                    .unwrap(),
                ),
            };

            match tokio::time::timeout(
                Duration::from_secs(5),
                client.register_capability(vec![registration]),
            )
            .await
            {
                Ok(Err(e)) => tracing::warn!("failed to register file watchers: {:?}", e),
                Err(_) => tracing::warn!("file watcher registration timed out"),
                Ok(Ok(())) => {}
            }
        });

        Ok(result)
    }

    async fn initialized(&self, _: InitializedParams) {
        tracing::info!("kotlin-analyzer: initialized");

        // Spawn sidecar startup in a background task. tower-lsp processes
        // notifications sequentially, so calling send_request (which awaits
        // the client's response) from within a notification handler deadlocks.
        let client = self.client.clone();
        let bridge_holder = Arc::clone(&self.bridge);
        let documents_holder = Arc::clone(&self.documents);
        let config = self.config.lock().await.clone();
        let project_root = self.project_root.lock().await.clone();

        tracing::debug!("about to spawn background task for sidecar startup");
        tokio::spawn(async move {
            tracing::debug!("initialized: background task started");

            // Create progress token
            let token = NumberOrString::String("kotlin-analyzer-startup".to_string());

            // Use a timeout: if the client doesn't support workDoneProgress
            // or doesn't respond, we still proceed with sidecar startup.
            match tokio::time::timeout(
                Duration::from_secs(5),
                client.send_request::<lsp_types::request::WorkDoneProgressCreate>(
                    WorkDoneProgressCreateParams {
                        token: token.clone(),
                    },
                ),
            )
            .await
            {
                Ok(Err(e)) => tracing::warn!("failed to create progress token: {:?}", e),
                Err(_) => tracing::warn!(
                    "progress token creation timed out, client may not support workDoneProgress"
                ),
                Ok(Ok(())) => {}
            }

            client
                .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                    token: token.clone(),
                    value: ProgressParamsValue::WorkDone(WorkDoneProgress::Begin(
                        WorkDoneProgressBegin {
                            title: "Starting Kotlin sidecar".to_string(),
                            message: Some("Resolving project...".to_string()),
                            percentage: None,
                            cancellable: Some(false),
                        },
                    )),
                })
                .await;

            // Resolve project model first so we can pass it to the sidecar
            let project_model = if let Some(ref root) = project_root {
                tracing::debug!("resolving project model for {:?}", root);
                match project::resolve_project_with_fallback(root, &config) {
                    Ok(model) => {
                        tracing::debug!(
                            "project resolved: {} source roots, {} classpath entries, {} compiler flags",
                            model.source_roots.len(),
                            model.classpath.len(),
                            model.compiler_flags.len()
                        );

                        Some(model)
                    }
                    Err(e) => {
                        tracing::warn!("project resolution failed: {}, using stdlib-only", e);
                        let _ = client
                            .show_message(
                                MessageType::WARNING,
                                format!("kotlin-analyzer: project resolution failed: {}. Using stdlib-only analysis.", e),
                            )
                            .await;
                        None
                    }
                }
            } else {
                tracing::debug!("no project root, using stdlib-only analysis");
                None
            };

            client
                .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                    token: token.clone(),
                    value: ProgressParamsValue::WorkDone(WorkDoneProgress::Report(
                        WorkDoneProgressReport {
                            message: Some("Starting JVM sidecar...".to_string()),
                            percentage: None,
                            cancellable: Some(false),
                        },
                    )),
                })
                .await;

            // Try to start the sidecar
            let java_path = match crate::bridge::find_java() {
                Ok(p) => p,
                Err(e) => {
                    tracing::error!("JVM not found: {}", e);
                    client
                        .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                            token: token.clone(),
                            value: ProgressParamsValue::WorkDone(WorkDoneProgress::End(
                                WorkDoneProgressEnd {
                                    message: Some(format!("Failed: {}", e)),
                                },
                            )),
                        })
                        .await;
                    client
                        .show_message(
                            MessageType::ERROR,
                            "kotlin-analyzer: JDK 17+ required but not found. Set JAVA_HOME or KOTLIN_LS_JAVA_HOME.",
                        )
                        .await;
                    return;
                }
            };

            tracing::debug!("java found at {:?}", java_path);

            let requested_kotlin_version = project_model
                .as_ref()
                .and_then(|model| model.kotlin_version.clone());

            let sidecar_runtime =
                runtime::resolve_sidecar_runtime(requested_kotlin_version.as_deref());
            let sidecar_runtime = match sidecar_runtime {
                Some(runtime) => runtime,
                None => {
                    tracing::warn!("sidecar runtime not found, semantic features unavailable");
                    client
                        .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                            token: token.clone(),
                            value: ProgressParamsValue::WorkDone(WorkDoneProgress::End(
                                WorkDoneProgressEnd {
                                    message: Some("sidecar runtime not found".to_string()),
                                },
                            )),
                        })
                        .await;
                    client
                        .show_message(
                            MessageType::WARNING,
                            "kotlin-analyzer: sidecar runtime not found. Semantic features are unavailable.",
                        )
                        .await;
                    return;
                }
            };

            tracing::info!(
                requested = requested_kotlin_version.as_deref().unwrap_or("unknown"),
                selected = sidecar_runtime.kotlin_version.as_deref().unwrap_or("unknown"),
                selection_counter = sidecar_runtime.selection_reason.counter_name(),
                reason = sidecar_runtime.selection_reason.description(),
                classpath = ?sidecar_runtime.classpath,
                main_class = sidecar_runtime.main_class.as_deref().unwrap_or("<jar>"),
                "selected sidecar runtime"
            );

            if let Some(message) = sidecar_runtime.selection_warning_message() {
                client.show_message(MessageType::WARNING, message).await;
            }

            let bridge = Arc::new(Bridge::new(sidecar_runtime, java_path, config));

            // Store the bridge BEFORE starting so LSP requests that arrive
            // during sidecar startup can reach it and wait for Ready state
            // (request buffering in bridge.rs handles the wait).
            {
                let mut b = bridge_holder.lock().await;
                *b = Some(bridge);
            }
            // Lock is released here so hover/completion handlers can access
            // the bridge while start() is running. Their requests will wait
            // for Ready via the watch channel in bridge.rs.

            // Prepare project config for the sidecar
            let project_root_str = project_root
                .as_ref()
                .map(|p| p.to_string_lossy().to_string())
                .unwrap_or_default();

            let (classpath, compiler_flags, source_roots) = match &project_model {
                Some(model) => {
                    let cp: Vec<String> = model
                        .classpath
                        .iter()
                        .map(|p| p.to_string_lossy().to_string())
                        .collect();
                    let cf: Vec<String> = model.compiler_flags.clone();
                    let sr: Vec<String> = model
                        .source_roots
                        .iter()
                        .chain(model.generated_source_roots.iter())
                        .map(|p| p.to_string_lossy().to_string())
                        .collect();
                    (cp, cf, sr)
                }
                None => (Vec::new(), Vec::new(), Vec::new()),
            };

            // Note: when no source roots are found (no build system), the sidecar
            // falls back to creating ad-hoc KtFile objects from opened files via
            // KtPsiFactory. This is faster than scanning the entire project root
            // and works well for basic features (hover, completion, diagnostics).
            if source_roots.is_empty() {
                tracing::debug!("no source roots found, sidecar will use per-file fallback");
            }

            tracing::debug!(
                "starting sidecar with project_root={}, classpath={} entries, source_roots={:?}",
                project_root_str,
                classpath.len(),
                source_roots
            );

            // Re-acquire lock briefly to call start() on the bridge
            let start_result = {
                let b = bridge_holder.lock().await;
                let bridge = b.as_ref().unwrap();
                bridge
                    .start(
                        Some(project_root_str.as_str()),
                        &classpath,
                        &compiler_flags,
                        &source_roots,
                    )
                    .await
            };

            match start_result {
                Ok(()) => {
                    tracing::info!("sidecar started successfully");
                    client
                        .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                            token: token.clone(),
                            value: ProgressParamsValue::WorkDone(WorkDoneProgress::End(
                                WorkDoneProgressEnd {
                                    message: Some("Ready".to_string()),
                                },
                            )),
                        })
                        .await;

                    // Replay all open documents: send didOpen + analyze for each
                    // file that was opened before the sidecar was ready.
                    let open_docs: Vec<(Url, String, i32, DocumentKind)> = {
                        let docs = documents_holder.lock().await;
                        docs.all()
                            .map(|(uri, doc)| {
                                (uri.clone(), doc.text.clone(), doc.version, doc.kind)
                            })
                            .collect()
                    };

                    if !open_docs.is_empty() {
                        tracing::info!(
                            "replaying {} open document(s) after sidecar startup",
                            open_docs.len()
                        );
                    }

                    // Clone the Arc<Bridge> out of the mutex so the lock is released
                    // before the replay loop. This prevents blocking hover/completion
                    // handlers during the potentially long replay.
                    let bridge_arc = {
                        let guard = bridge_holder.lock().await;
                        guard.as_ref().map(Arc::clone)
                    };
                    if let Some(bridge) = bridge_arc {
                        for (uri, text, version, kind) in &open_docs {
                            tracing::debug!("replay: sending didOpen for {}", uri);
                            let _ = bridge
                                .notify(
                                    kind.did_open_method(),
                                    Some(serde_json::json!({
                                        "uri": uri.as_str(),
                                        "version": version,
                                        "text": text,
                                    })),
                                )
                                .await;

                            // Send didChange + analyze
                            let _ = bridge
                                .notify(
                                    kind.did_change_method(),
                                    Some(serde_json::json!({
                                        "uri": uri.as_str(),
                                        "version": version,
                                        "text": text,
                                    })),
                                )
                                .await;

                            if !kind.supports_kotlin_analysis() {
                                continue;
                            }

                            match bridge
                                .request(
                                    "analyze",
                                    Some(serde_json::json!({ "uri": uri.as_str() })),
                                )
                                .await
                            {
                                Ok(result) => {
                                    let diagnostics = parse_diagnostics_static(&result);
                                    tracing::info!(
                                        "replay: {} returned {} diagnostics",
                                        uri,
                                        diagnostics.len()
                                    );
                                    client
                                        .publish_diagnostics(uri.clone(), diagnostics, None)
                                        .await;
                                }
                                Err(e) => {
                                    tracing::warn!("replay: analysis failed for {}: {}", uri, e);
                                }
                            }
                        }
                    }

                    // --- Project-wide background analysis ---
                    let bg_bridge = Arc::clone(&bridge_holder);
                    let bg_documents = Arc::clone(&documents_holder);
                    let bg_client = client.clone();
                    tokio::spawn(async move {
                        // Small delay to let open-file diagnostics settle
                        tokio::time::sleep(Duration::from_secs(2)).await;

                        // Create progress token for background analysis
                        let bg_token =
                            NumberOrString::String("kotlin-analyzer-background".to_string());
                        let _ = tokio::time::timeout(
                            Duration::from_secs(5),
                            bg_client.send_request::<lsp_types::request::WorkDoneProgressCreate>(
                                WorkDoneProgressCreateParams {
                                    token: bg_token.clone(),
                                },
                            ),
                        )
                        .await;

                        bg_client
                            .send_notification::<lsp_types::notification::Progress>(
                                ProgressParams {
                                    token: bg_token.clone(),
                                    value: ProgressParamsValue::WorkDone(WorkDoneProgress::Begin(
                                        WorkDoneProgressBegin {
                                            title: "Analyzing project".to_string(),
                                            message: Some(
                                                "Running diagnostics on all source files..."
                                                    .to_string(),
                                            ),
                                            percentage: Some(0),
                                            cancellable: Some(false),
                                        },
                                    )),
                                },
                            )
                            .await;

                        // Call analyzeAll with a generous timeout (5 minutes)
                        let bridge_arc = {
                            let guard = bg_bridge.lock().await;
                            guard.as_ref().map(Arc::clone)
                        };
                        let analyze_result = match bridge_arc {
                            Some(b) => {
                                if b.state().await != SidecarState::Ready {
                                    tracing::warn!(
                                        "background analysis: sidecar not ready, skipping"
                                    );
                                    None
                                } else {
                                    Some(
                                        b.request_with_timeout(
                                            "analyzeAll",
                                            None,
                                            Duration::from_secs(300),
                                        )
                                        .await,
                                    )
                                }
                            }
                            None => None,
                        };

                        match analyze_result {
                            Some(Ok(result)) => {
                                let files = result.get("files").and_then(|f| f.as_array());
                                let total_files = result
                                    .get("totalFiles")
                                    .and_then(|t| t.as_u64())
                                    .unwrap_or(0);
                                let total_errors = result
                                    .get("totalErrors")
                                    .and_then(|e| e.as_u64())
                                    .unwrap_or(0);
                                let total_warnings = result
                                    .get("totalWarnings")
                                    .and_then(|w| w.as_u64())
                                    .unwrap_or(0);

                                if let Some(files) = files {
                                    let mut processed = 0u64;
                                    let mut _published = 0u64;
                                    for file_entry in files {
                                        processed += 1;

                                        let uri_str =
                                            match file_entry.get("uri").and_then(|u| u.as_str()) {
                                                Some(u) => u,
                                                None => continue,
                                            };

                                        let uri = match Url::parse(uri_str) {
                                            Ok(u) => u,
                                            Err(e) => {
                                                tracing::warn!("background analysis: invalid URI from sidecar: {:?} ({})", uri_str, e);
                                                continue;
                                            }
                                        };

                                        // Skip files that are currently open — their diagnostics
                                        // from the replay loop are fresher
                                        {
                                            let docs = bg_documents.lock().await;
                                            if docs.get(&uri).is_some() {
                                                continue;
                                            }
                                        }

                                        let diagnostics = parse_diagnostics_static(file_entry);

                                        // Only publish and cache files with actual diagnostics
                                        if !diagnostics.is_empty() {
                                            {
                                                let mut docs = bg_documents.lock().await;
                                                docs.set_diagnostics(
                                                    uri.clone(),
                                                    diagnostics.clone(),
                                                );
                                            }
                                            bg_client
                                                .publish_diagnostics(uri, diagnostics, None)
                                                .await;
                                            _published += 1;
                                        }

                                        // Report progress periodically
                                        if total_files > 0 && processed % 10 == 0 {
                                            let pct = ((processed as f64 / total_files as f64)
                                                * 100.0)
                                                as u32;
                                            bg_client
                                                .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                                                    token: bg_token.clone(),
                                                    value: ProgressParamsValue::WorkDone(WorkDoneProgress::Report(
                                                        WorkDoneProgressReport {
                                                            message: Some(format!("Processed {}/{} files", processed, total_files)),
                                                            percentage: Some(pct),
                                                            cancellable: Some(false),
                                                        },
                                                    )),
                                                })
                                                .await;
                                        }
                                    }

                                    let summary = format!(
                                        "Complete — {} error(s), {} warning(s) in {} file(s)",
                                        total_errors, total_warnings, total_files
                                    );
                                    tracing::info!("background analysis: {}", summary);

                                    bg_client
                                        .send_notification::<lsp_types::notification::Progress>(
                                            ProgressParams {
                                                token: bg_token.clone(),
                                                value: ProgressParamsValue::WorkDone(
                                                    WorkDoneProgress::End(WorkDoneProgressEnd {
                                                        message: Some(summary),
                                                    }),
                                                ),
                                            },
                                        )
                                        .await;
                                }
                            }
                            Some(Err(e)) => {
                                tracing::warn!("background analysis failed: {}", e);
                                bg_client
                                    .send_notification::<lsp_types::notification::Progress>(
                                        ProgressParams {
                                            token: bg_token.clone(),
                                            value: ProgressParamsValue::WorkDone(
                                                WorkDoneProgress::End(WorkDoneProgressEnd {
                                                    message: Some(format!("Failed: {}", e)),
                                                }),
                                            ),
                                        },
                                    )
                                    .await;
                            }
                            None => {
                                bg_client
                                    .send_notification::<lsp_types::notification::Progress>(
                                        ProgressParams {
                                            token: bg_token.clone(),
                                            value: ProgressParamsValue::WorkDone(
                                                WorkDoneProgress::End(WorkDoneProgressEnd {
                                                    message: Some(
                                                        "Skipped — sidecar not ready".to_string(),
                                                    ),
                                                }),
                                            ),
                                        },
                                    )
                                    .await;
                            }
                        }
                    });
                }
                Err(e) => {
                    tracing::error!("failed to start sidecar: {:?}", e);
                    // Remove the bridge since startup failed
                    {
                        let mut b = bridge_holder.lock().await;
                        *b = None;
                    }
                    client
                        .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                            token: token.clone(),
                            value: ProgressParamsValue::WorkDone(WorkDoneProgress::End(
                                WorkDoneProgressEnd {
                                    message: Some(format!("Failed: {}", e)),
                                },
                            )),
                        })
                        .await;
                    client
                        .show_message(
                            MessageType::ERROR,
                            format!("kotlin-analyzer: failed to start sidecar: {}", e),
                        )
                        .await;
                }
            }
        });
    }

    async fn shutdown(&self) -> LspResult<()> {
        tracing::info!("kotlin-analyzer: shutting down");

        if let Some(bridge) = self.get_bridge().await {
            if let Err(e) = bridge.shutdown().await {
                tracing::error!("error shutting down sidecar: {}", e);
            }
        }

        Ok(())
    }

    async fn did_open(&self, params: DidOpenTextDocumentParams) {
        let uri = params.text_document.uri.clone();
        let text = params.text_document.text.clone();
        let version = params.text_document.version;
        let kind = DocumentKind::from_language_id(&params.text_document.language_id, &uri);

        tracing::debug!(
            "did_open: {} (version {}, {} bytes)",
            uri,
            version,
            text.len()
        );

        // Re-publish cached diagnostics immediately so they appear instantly on tab switch
        {
            let mut documents = self.documents.lock().await;
            if let Some(cached) = documents.get_diagnostics(&uri).cloned() {
                if !cached.is_empty() {
                    tracing::debug!(
                        "did_open: re-publishing {} cached diagnostics for {}",
                        cached.len(),
                        uri
                    );
                    self.client
                        .publish_diagnostics(uri.clone(), cached, None)
                        .await;
                }
            }
            documents.open(uri.clone(), text.clone(), version, kind);
        }

        // Notify sidecar
        if let Some(bridge) = self.get_bridge().await {
            let _ = bridge
                .notify(
                    kind.did_open_method(),
                    Some(serde_json::json!({
                        "uri": uri.as_str(),
                        "version": version,
                        "text": text,
                    })),
                )
                .await;
        }

        // Trigger fresh analysis (will update the cache when complete)
        self.analyze_document(&uri).await;
    }

    async fn did_change(&self, params: DidChangeTextDocumentParams) {
        let uri = params.text_document.uri.clone();
        let version = params.text_document.version;
        let mut latest_doc = None;

        // Full sync mode — take the last content change
        if let Some(change) = params.content_changes.into_iter().last() {
            let mut documents = self.documents.lock().await;
            documents.change(&uri, change.text, version);
            latest_doc = documents.get(&uri).cloned();
        }

        // Keep the sidecar's virtual file state in sync immediately so
        // completion/hover/definition requests see the latest editor buffer
        // instead of waiting for the debounced diagnostics path.
        if let Some(doc) = latest_doc {
            if let Some(bridge) = self.get_bridge().await {
                let _ = bridge
                    .notify(
                        doc.kind.did_change_method(),
                        Some(serde_json::json!({
                            "uri": uri.as_str(),
                            "version": doc.version,
                            "text": doc.text,
                        })),
                    )
                    .await;
            }
        }

        // Send to debounce loop for analysis
        let debounce = self.debounce_tx.lock().await;
        if let Some(tx) = debounce.as_ref() {
            let _ = tx.send(uri).await;
        }
    }

    async fn did_close(&self, params: DidCloseTextDocumentParams) {
        let uri = params.text_document.uri.clone();
        let kind = {
            let documents = self.documents.lock().await;
            documents
                .get(&uri)
                .map(|doc| doc.kind)
                .unwrap_or_else(|| DocumentKind::from_uri(&uri))
        };

        {
            let mut documents = self.documents.lock().await;
            documents.close(&uri);
        }

        // Notify sidecar
        if let Some(bridge) = self.get_bridge().await {
            let _ = bridge
                .notify(
                    kind.did_close_method(),
                    Some(serde_json::json!({
                        "uri": uri.as_str(),
                    })),
                )
                .await;
        }

        // Do NOT clear diagnostics on didClose — Zed sends didClose when switching
        // tabs, and clearing diagnostics here causes them to disappear. Cached
        // diagnostics will be re-published on the next didOpen.
        tracing::debug!("did_close: keeping cached diagnostics for {}", uri);
    }

    async fn did_save(&self, params: DidSaveTextDocumentParams) {
        let uri = params.text_document.uri;
        tracing::debug!("did_save: {}", uri);

        let should_analyze = {
            let documents = self.documents.lock().await;
            documents
                .get(&uri)
                .map(|doc| doc.kind.supports_kotlin_analysis())
                .unwrap_or_else(|| DocumentKind::from_uri(&uri).supports_kotlin_analysis())
        };

        if !should_analyze {
            return;
        }

        // Trigger fresh analysis on save (bypasses debounce for immediate feedback)
        self.analyze_document(&uri).await;
    }

    async fn completion(&self, params: CompletionParams) -> LspResult<Option<CompletionResponse>> {
        let uri = params.text_document_position.text_document.uri;
        let position = params.text_document_position.position;
        let trigger_character = params
            .context
            .as_ref()
            .and_then(|context| context.trigger_character.clone());

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "completion",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                    "triggerCharacter": trigger_character,
                })),
            )
            .await
        {
            Ok(result) => {
                let items = self.parse_completion_items(&result);
                if items.is_empty() {
                    let reason = result
                        .get("reason")
                        .and_then(|value| value.as_str())
                        .unwrap_or("no explicit reason");
                    tracing::warn!(
                        "completion returned no items for {}:{}:{} (reason={})",
                        uri,
                        position.line,
                        position.character,
                        reason
                    );
                }
                Ok(Some(CompletionResponse::Array(items)))
            }
            Err(e) => {
                tracing::warn!("completion failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn hover(&self, params: HoverParams) -> LspResult<Option<Hover>> {
        let uri = params.text_document_position_params.text_document.uri;
        let position = params.text_document_position_params.position;

        tracing::debug!(
            "hover request: {}:{}:{}",
            uri,
            position.line,
            position.character
        );

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => {
                tracing::warn!("hover: bridge is None, returning ServerNotInitialized error");
                return Self::server_not_initialized_error();
            }
        };

        let sidecar_state = bridge.state().await;
        tracing::debug!("hover: sidecar state is {:?}", sidecar_state);

        match bridge
            .request(
                "hover",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                })),
            )
            .await
        {
            Ok(result) => {
                tracing::debug!("hover: sidecar returned: {}", result);
                let failure_reason = result
                    .get("reason")
                    .and_then(|reason| reason.as_str())
                    .unwrap_or("no explicit reason");

                if let Some(contents) = result.get("contents").and_then(|c| c.as_str()) {
                    Ok(Some(Hover {
                        contents: HoverContents::Markup(MarkupContent {
                            kind: MarkupKind::Markdown,
                            value: contents.to_string(),
                        }),
                        range: None,
                    }))
                } else {
                    tracing::warn!(
                        "hover: sidecar result has no 'contents' string field (reason={})",
                        failure_reason
                    );
                    Ok(None)
                }
            }
            Err(e) => {
                tracing::warn!("hover: bridge request failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn goto_definition(
        &self,
        params: GotoDefinitionParams,
    ) -> LspResult<Option<GotoDefinitionResponse>> {
        let uri = params.text_document_position_params.text_document.uri;
        let position = params.text_document_position_params.position;
        let method = {
            let documents = self.documents.lock().await;
            documents
                .get(&uri)
                .map(|doc| doc.kind.definition_method())
                .unwrap_or_else(|| DocumentKind::from_uri(&uri).definition_method())
        };

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                method,
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                })),
            )
            .await
        {
            Ok(result) => {
                let locations = self.parse_locations(&result);
                if locations.is_empty() {
                    Ok(None)
                } else if locations.len() == 1 {
                    Ok(Some(GotoDefinitionResponse::Scalar(
                        locations.into_iter().next().unwrap(),
                    )))
                } else {
                    Ok(Some(GotoDefinitionResponse::Array(locations)))
                }
            }
            Err(e) => {
                tracing::warn!("goto_definition failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn references(&self, params: ReferenceParams) -> LspResult<Option<Vec<Location>>> {
        let uri = params.text_document_position.text_document.uri;
        let position = params.text_document_position.position;
        let method = {
            let documents = self.documents.lock().await;
            documents
                .get(&uri)
                .map(|doc| doc.kind.references_method())
                .unwrap_or_else(|| DocumentKind::from_uri(&uri).references_method())
        };

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                method,
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                    "includeDeclaration": params.context.include_declaration,
                })),
            )
            .await
        {
            Ok(result) => {
                let locations = self.parse_locations(&result);
                if locations.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(locations))
                }
            }
            Err(e) => {
                tracing::warn!("references failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn formatting(
        &self,
        params: DocumentFormattingParams,
    ) -> LspResult<Option<Vec<TextEdit>>> {
        let uri = params.text_document.uri;

        // Get the document text
        let original_text = {
            let documents = self.documents.lock().await;
            match documents.get(&uri) {
                Some(doc) => doc.text.clone(),
                None => {
                    tracing::warn!("formatting: document not found: {}", uri);
                    return Ok(None);
                }
            }
        };

        // Get config
        let config = self.config.lock().await.clone();

        // Check formatting tool
        match config.formatting_tool {
            FormattingTool::None => return Ok(None),
            FormattingTool::Ktfmt => {
                let binary = config
                    .formatting_path
                    .unwrap_or_else(|| "ktfmt".to_string());
                match self
                    .format_with_ktfmt(&binary, &original_text, &config.formatting_style)
                    .await
                {
                    Ok(Some(formatted)) => {
                        if formatted == original_text {
                            return Ok(None);
                        }
                        let line_count = original_text.lines().count() as u32;
                        Ok(Some(vec![TextEdit {
                            range: Range {
                                start: Position::new(0, 0),
                                end: Position::new(line_count, 0),
                            },
                            new_text: formatted,
                        }]))
                    }
                    Ok(None) => Ok(None),
                    Err(e) => {
                        tracing::warn!("ktfmt formatting failed: {}", e);
                        Ok(None)
                    }
                }
            }
            FormattingTool::Ktlint => {
                let binary = config
                    .formatting_path
                    .unwrap_or_else(|| "ktlint".to_string());
                match self.format_with_ktlint(&binary, &original_text).await {
                    Ok(Some(formatted)) => {
                        if formatted == original_text {
                            return Ok(None);
                        }
                        let line_count = original_text.lines().count() as u32;
                        Ok(Some(vec![TextEdit {
                            range: Range {
                                start: Position::new(0, 0),
                                end: Position::new(line_count, 0),
                            },
                            new_text: formatted,
                        }]))
                    }
                    Ok(None) => Ok(None),
                    Err(e) => {
                        tracing::warn!("ktlint formatting failed: {}", e);
                        Ok(None)
                    }
                }
            }
        }
    }

    async fn signature_help(
        &self,
        params: SignatureHelpParams,
    ) -> LspResult<Option<SignatureHelp>> {
        let uri = params.text_document_position_params.text_document.uri;
        let position = params.text_document_position_params.position;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "signatureHelp",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                })),
            )
            .await
        {
            Ok(result) => {
                let signatures = self.parse_signatures(&result);
                if signatures.is_empty() {
                    Ok(None)
                } else {
                    let active_signature = result
                        .get("activeSignature")
                        .and_then(|s| s.as_u64())
                        .map(|s| s as u32);
                    let active_parameter = result
                        .get("activeParameter")
                        .and_then(|p| p.as_u64())
                        .map(|p| p as u32);

                    Ok(Some(SignatureHelp {
                        signatures,
                        active_signature,
                        active_parameter,
                    }))
                }
            }
            Err(e) => {
                tracing::warn!("signature_help failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn did_change_configuration(&self, params: DidChangeConfigurationParams) {
        if let Ok(config) = serde_json::from_value::<Config>(params.settings) {
            tracing::debug!("configuration updated");
            let mut c = self.config.lock().await;
            *c = config.clone();

            if let Some(bridge) = self.get_bridge().await {
                bridge.update_config(config).await;
            }
        }
    }

    async fn did_change_watched_files(&self, params: DidChangeWatchedFilesParams) {
        for change in params.changes {
            let path = match change.uri.to_file_path() {
                Ok(p) => p,
                Err(_) => continue,
            };

            let path_str = path.to_string_lossy();

            // Check if it's a build file (ignore our own init script to avoid loops)
            let is_build_file = (path_str.ends_with(".gradle")
                || path_str.ends_with(".gradle.kts")
                || path_str.ends_with("gradle.properties"))
                && !path_str.ends_with(".kotlin-analyzer-init.gradle");
            if is_build_file {
                tracing::debug!(
                    "build file changed: {}, triggering project re-resolution",
                    path_str
                );

                let project_root = self.project_root.lock().await.clone();
                if let Some(root) = project_root {
                    let config = self.config.lock().await.clone();
                    let client = self.client.clone();

                    tokio::spawn(async move {
                        match project::resolve_project_with_fallback(&root, &config) {
                            Ok(_model) => {
                                tracing::debug!("project re-resolved after build file change");
                            }
                            Err(e) => {
                                tracing::warn!("project re-resolution failed: {}", e);
                                let _ = client
                                    .show_message(
                                        MessageType::WARNING,
                                        format!(
                                            "kotlin-analyzer: project re-resolution failed: {}",
                                            e
                                        ),
                                    )
                                    .await;
                            }
                        }
                    });
                }
            } else if path_str.ends_with(".editorconfig") {
                tracing::debug!(".editorconfig changed: {}", path_str);
                // External formatters pick up .editorconfig automatically, nothing to do
            }
        }
    }

    async fn prepare_rename(
        &self,
        _params: TextDocumentPositionParams,
    ) -> LspResult<Option<PrepareRenameResponse>> {
        // Use default word-boundary behavior for all identifier positions
        Ok(Some(PrepareRenameResponse::DefaultBehavior {
            default_behavior: true,
        }))
    }

    async fn rename(&self, params: RenameParams) -> LspResult<Option<WorkspaceEdit>> {
        let uri = params.text_document_position.text_document.uri;
        let position = params.text_document_position.position;
        let new_name = params.new_name;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "rename",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                    "newName": new_name,
                })),
            )
            .await
        {
            Ok(result) => {
                let edits = parse_workspace_edits(&result);
                if edits.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(WorkspaceEdit {
                        changes: Some(edits),
                        document_changes: None,
                        change_annotations: None,
                    }))
                }
            }
            Err(e) => {
                tracing::warn!("rename failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn code_action(&self, params: CodeActionParams) -> LspResult<Option<CodeActionResponse>> {
        let uri = params.text_document.uri;
        let range = params.range;
        let diagnostics = params.context.diagnostics;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "codeActions",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": range.start.line + 1,
                    "character": range.start.character,
                    "diagnostics": diagnostics.iter().map(|d| {
                        serde_json::json!({
                            "severity": d.severity,
                            "message": d.message,
                            "code": d.code,
                        })
                    }).collect::<Vec<_>>(),
                })),
            )
            .await
        {
            Ok(result) => {
                tracing::debug!("code_action: raw sidecar response for {}: {}", uri, result);
                let actions = parse_code_actions_result(&result);
                tracing::debug!(
                    "code_action: parsed {} action(s) for {} at L{}:{}",
                    actions.len(),
                    uri,
                    range.start.line,
                    range.start.character
                );
                if actions.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(actions))
                }
            }
            Err(e) => {
                tracing::warn!("code_action failed for {}: {}", uri, e);
                Ok(None)
            }
        }
    }

    async fn execute_command(&self, params: ExecuteCommandParams) -> LspResult<Option<Value>> {
        let request = parse_analyzer_command_request(params)?;
        self.execute_analyzer_command(request).await.map(Some)
    }

    async fn symbol(
        &self,
        params: WorkspaceSymbolParams,
    ) -> LspResult<Option<Vec<SymbolInformation>>> {
        let query = params.query;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "workspaceSymbols",
                Some(serde_json::json!({
                    "query": query,
                })),
            )
            .await
        {
            Ok(result) => {
                let symbols = self.parse_workspace_symbols(&result);
                if symbols.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(symbols))
                }
            }
            Err(e) => {
                tracing::warn!("workspace symbols failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn inlay_hint(&self, params: InlayHintParams) -> LspResult<Option<Vec<InlayHint>>> {
        let uri = params.text_document.uri;
        let range = params.range;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "inlayHints",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "startLine": range.start.line + 1,
                    "endLine": range.end.line + 1,
                })),
            )
            .await
        {
            Ok(result) => {
                let hints = self.parse_inlay_hints(&result);
                if hints.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(hints))
                }
            }
            Err(e) => {
                tracing::warn!("inlay_hint failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn code_lens(&self, params: CodeLensParams) -> LspResult<Option<Vec<CodeLens>>> {
        let uri = params.text_document.uri;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "codeLens",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                })),
            )
            .await
        {
            Ok(result) => {
                let lenses = self.parse_code_lenses(&result);
                if lenses.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(lenses))
                }
            }
            Err(e) => {
                tracing::warn!("code_lens failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn semantic_tokens_full(
        &self,
        params: SemanticTokensParams,
    ) -> LspResult<Option<SemanticTokensResult>> {
        let uri = params.text_document.uri;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "semanticTokens",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                })),
            )
            .await
        {
            Ok(result) => {
                let tokens = self.parse_semantic_tokens(&result);
                Ok(Some(SemanticTokensResult::Tokens(SemanticTokens {
                    result_id: None,
                    data: tokens,
                })))
            }
            Err(e) => {
                tracing::warn!("semantic_tokens_full failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn prepare_call_hierarchy(
        &self,
        params: CallHierarchyPrepareParams,
    ) -> LspResult<Option<Vec<CallHierarchyItem>>> {
        let uri = params.text_document_position_params.text_document.uri;
        let position = params.text_document_position_params.position;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "callHierarchy/prepare",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                })),
            )
            .await
        {
            Ok(result) => {
                let items = self.parse_call_hierarchy_items(&result);
                if items.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(items))
                }
            }
            Err(e) => {
                tracing::warn!("prepare_call_hierarchy failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn incoming_calls(
        &self,
        params: CallHierarchyIncomingCallsParams,
    ) -> LspResult<Option<Vec<CallHierarchyIncomingCall>>> {
        let item = &params.item;
        let uri = &item.uri;
        let position = item.selection_range.start;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "callHierarchy/incoming",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                    "name": item.name,
                })),
            )
            .await
        {
            Ok(result) => {
                let calls = self.parse_incoming_calls(&result);
                if calls.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(calls))
                }
            }
            Err(e) => {
                tracing::warn!("incoming_calls failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn prepare_type_hierarchy(
        &self,
        params: TypeHierarchyPrepareParams,
    ) -> LspResult<Option<Vec<TypeHierarchyItem>>> {
        let uri = params.text_document_position_params.text_document.uri;
        let position = params.text_document_position_params.position;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "typeHierarchy/prepare",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                })),
            )
            .await
        {
            Ok(result) => {
                let items = self.parse_type_hierarchy_items(&result);
                if items.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(items))
                }
            }
            Err(e) => {
                tracing::warn!("prepare_type_hierarchy failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn supertypes(
        &self,
        params: TypeHierarchySupertypesParams,
    ) -> LspResult<Option<Vec<TypeHierarchyItem>>> {
        let item = &params.item;
        let uri = &item.uri;
        let position = item.selection_range.start;

        let bridge = match self.get_bridge().await {
            Some(b) => b,
            None => return Self::server_not_initialized_error(),
        };

        match bridge
            .request(
                "typeHierarchy/supertypes",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                    "name": item.name,
                })),
            )
            .await
        {
            Ok(result) => {
                let items = self.parse_type_hierarchy_items(&result);
                if items.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(items))
                }
            }
            Err(e) => {
                tracing::warn!("supertypes failed: {}", e);
                Ok(None)
            }
        }
    }
}

// Helper methods for formatting
impl KotlinLanguageServer {
    async fn format_with_ktfmt(
        &self,
        binary: &str,
        text: &str,
        style: &str,
    ) -> Result<Option<String>, std::io::Error> {
        let style_arg = match style {
            "google" => "--google-style",
            "kotlinlang" => "--kotlinlang-style",
            "dropbox" => "--dropbox-style",
            _ => "--google-style",
        };

        let mut child = Command::new(binary)
            .arg(style_arg)
            .arg("-")
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()?;

        let mut stdin = child.stdin.take().expect("failed to open stdin");
        stdin.write_all(text.as_bytes()).await?;
        drop(stdin);

        let output = child.wait_with_output().await?;

        if output.status.success() {
            Ok(Some(String::from_utf8_lossy(&output.stdout).to_string()))
        } else {
            tracing::warn!("ktfmt stderr: {}", String::from_utf8_lossy(&output.stderr));
            Ok(None)
        }
    }

    async fn format_with_ktlint(
        &self,
        binary: &str,
        text: &str,
    ) -> Result<Option<String>, std::io::Error> {
        let mut child = Command::new(binary)
            .arg("--format")
            .arg("--stdin")
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()?;

        let mut stdin = child.stdin.take().expect("failed to open stdin");
        stdin.write_all(text.as_bytes()).await?;
        drop(stdin);

        let output = child.wait_with_output().await?;

        if output.status.success() {
            Ok(Some(String::from_utf8_lossy(&output.stdout).to_string()))
        } else {
            tracing::warn!("ktlint stderr: {}", String::from_utf8_lossy(&output.stderr));
            Ok(None)
        }
    }
}

// Helper methods for parsing sidecar responses
impl KotlinLanguageServer {
    fn parse_completion_items(&self, result: &Value) -> Vec<CompletionItem> {
        let items = match result.get("items").and_then(|i| i.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        items
            .iter()
            .filter_map(|item| {
                let label = item.get("label")?.as_str()?.to_string();
                let kind = item.get("kind").and_then(|k| k.as_str()).map(|k| match k {
                    "function" | "method" => CompletionItemKind::FUNCTION,
                    "property" | "field" => CompletionItemKind::FIELD,
                    "class" => CompletionItemKind::CLASS,
                    "interface" => CompletionItemKind::INTERFACE,
                    "variable" | "local" => CompletionItemKind::VARIABLE,
                    "keyword" => CompletionItemKind::KEYWORD,
                    "snippet" => CompletionItemKind::SNIPPET,
                    "module" | "package" => CompletionItemKind::MODULE,
                    "enum" => CompletionItemKind::ENUM,
                    "constant" => CompletionItemKind::CONSTANT,
                    _ => CompletionItemKind::TEXT,
                });

                let detail = item
                    .get("detail")
                    .and_then(|d| d.as_str())
                    .map(String::from);

                let insert_text = item
                    .get("insertText")
                    .and_then(|t| t.as_str())
                    .map(String::from);

                let sort_text = item
                    .get("sortText")
                    .and_then(|s| s.as_str())
                    .map(String::from);

                let additional_text_edits = item
                    .get("additionalTextEdits")
                    .and_then(|a| a.as_array())
                    .map(|edits| {
                        edits
                            .iter()
                            .filter_map(|e| {
                                let new_text = e.get("newText")?.as_str()?.to_string();
                                let line = e.get("line")?.as_u64()?.saturating_sub(1) as u32;
                                let col = e.get("column")?.as_u64()? as u32;
                                let end_line = e.get("endLine")?.as_u64()?.saturating_sub(1) as u32;
                                let end_col = e.get("endColumn")?.as_u64()? as u32;
                                Some(TextEdit {
                                    range: Range {
                                        start: Position::new(line, col),
                                        end: Position::new(end_line, end_col),
                                    },
                                    new_text,
                                })
                            })
                            .collect()
                    });

                Some(CompletionItem {
                    label,
                    kind,
                    detail,
                    insert_text,
                    sort_text,
                    additional_text_edits,
                    ..Default::default()
                })
            })
            .collect()
    }

    fn parse_locations(&self, result: &Value) -> Vec<Location> {
        let locations = match result.get("locations").and_then(|l| l.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        locations
            .iter()
            .filter_map(|loc| {
                let uri_str = loc.get("uri")?.as_str()?;
                let uri = Url::parse(uri_str).ok()?;
                let line = loc.get("line")?.as_u64()?.saturating_sub(1) as u32;
                let col = loc.get("column").and_then(|c| c.as_u64()).unwrap_or(0) as u32;

                Some(Location {
                    uri,
                    range: Range {
                        start: Position::new(line, col),
                        end: Position::new(line, col),
                    },
                })
            })
            .collect()
    }

    fn parse_signatures(&self, result: &Value) -> Vec<SignatureInformation> {
        let signatures = match result.get("signatures").and_then(|s| s.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        signatures
            .iter()
            .filter_map(|sig| {
                let label = sig.get("label")?.as_str()?.to_string();
                let documentation = sig.get("documentation").and_then(|d| d.as_str()).map(|d| {
                    Documentation::MarkupContent(MarkupContent {
                        kind: MarkupKind::Markdown,
                        value: d.to_string(),
                    })
                });

                let parameters = sig
                    .get("parameters")
                    .and_then(|p| p.as_array())
                    .map(|params| {
                        params
                            .iter()
                            .filter_map(|p| {
                                let label = p.get("label")?.as_str()?.to_string();
                                Some(ParameterInformation {
                                    label: ParameterLabel::Simple(label),
                                    documentation: p
                                        .get("documentation")
                                        .and_then(|d| d.as_str())
                                        .map(|d| {
                                            Documentation::MarkupContent(MarkupContent {
                                                kind: MarkupKind::Markdown,
                                                value: d.to_string(),
                                            })
                                        }),
                                })
                            })
                            .collect()
                    });

                Some(SignatureInformation {
                    label,
                    documentation,
                    parameters,
                    active_parameter: None,
                })
            })
            .collect()
    }

    fn parse_workspace_symbols(&self, result: &Value) -> Vec<SymbolInformation> {
        let symbols_array = match result.get("symbols").and_then(|s| s.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        symbols_array
            .iter()
            .filter_map(|sym| {
                let name = sym.get("name")?.as_str()?.to_string();
                let kind_str = sym.get("kind")?.as_str()?;
                let kind = match kind_str {
                    "class" => SymbolKind::CLASS,
                    "interface" => SymbolKind::INTERFACE,
                    "enum" => SymbolKind::ENUM,
                    "function" | "method" => SymbolKind::FUNCTION,
                    "property" | "field" => SymbolKind::PROPERTY,
                    "variable" => SymbolKind::VARIABLE,
                    "constant" => SymbolKind::CONSTANT,
                    "module" | "package" => SymbolKind::MODULE,
                    "constructor" => SymbolKind::CONSTRUCTOR,
                    _ => SymbolKind::FILE,
                };

                let uri_str = sym.get("uri")?.as_str()?;
                let uri = Url::parse(uri_str).ok()?;
                let line = sym.get("line")?.as_u64()?.saturating_sub(1) as u32;
                let column = sym.get("column").and_then(|c| c.as_u64()).unwrap_or(0) as u32;

                #[allow(deprecated)]
                Some(SymbolInformation {
                    name,
                    kind,
                    tags: None,
                    deprecated: None,
                    location: Location {
                        uri,
                        range: Range {
                            start: Position::new(line, column),
                            end: Position::new(line, column),
                        },
                    },
                    container_name: sym
                        .get("containerName")
                        .and_then(|c| c.as_str())
                        .map(String::from),
                })
            })
            .collect()
    }

    fn parse_inlay_hints(&self, result: &Value) -> Vec<InlayHint> {
        let hints_array = match result.get("hints").and_then(|h| h.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        hints_array
            .iter()
            .filter_map(|hint| {
                let line = hint.get("line")?.as_u64()?.saturating_sub(1) as u32;
                let character = hint.get("character")?.as_u64()? as u32;
                let label_str = hint.get("label")?.as_str()?.to_string();

                let kind = hint
                    .get("kind")
                    .and_then(|k| k.as_str())
                    .and_then(|k| match k {
                        "type" => Some(InlayHintKind::TYPE),
                        "parameter" => Some(InlayHintKind::PARAMETER),
                        _ => None,
                    });

                let padding_left = hint.get("paddingLeft").and_then(|p| p.as_bool());
                let padding_right = hint.get("paddingRight").and_then(|p| p.as_bool());

                Some(InlayHint {
                    position: Position::new(line, character),
                    label: InlayHintLabel::String(label_str),
                    kind,
                    text_edits: None,
                    tooltip: None,
                    padding_left,
                    padding_right,
                    data: None,
                })
            })
            .collect()
    }

    fn parse_code_lenses(&self, result: &Value) -> Vec<CodeLens> {
        let lenses_array = match result.get("lenses").and_then(|l| l.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        lenses_array
            .iter()
            .filter_map(|lens| {
                let line = lens.get("line")?.as_u64()?.saturating_sub(1) as u32;
                let character = lens.get("character")?.as_u64()? as u32;

                let command_obj = lens.get("command")?;
                let title = command_obj.get("title")?.as_str()?.to_string();
                let command_name = command_obj
                    .get("command")
                    .and_then(|c| c.as_str())
                    .unwrap_or("kotlin-analyzer.command")
                    .to_string();

                Some(CodeLens {
                    range: Range {
                        start: Position::new(line, character),
                        end: Position::new(line, character),
                    },
                    command: Some(lsp_types::Command {
                        title,
                        command: command_name,
                        arguments: None,
                    }),
                    data: None,
                })
            })
            .collect()
    }

    fn parse_semantic_tokens(&self, result: &Value) -> Vec<SemanticToken> {
        let data_array = match result.get("data").and_then(|d| d.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        // Get the legend mapping if available
        let legend_types = result
            .get("legend")
            .and_then(|l| l.get("tokenTypes"))
            .and_then(|t| t.as_array());

        // Local legend from initialize
        let local_legend = vec![
            "function",
            "parameter",
            "variable",
            "property",
            "class",
            "type",
            "string",
            "comment",
            "keyword",
            "decorator",
            "number",
            "enumMember",
            "typeParameter",
        ];

        // Convert data array to semantic tokens (groups of 5 ints)
        let mut tokens = Vec::new();
        let mut i = 0;
        while i + 4 < data_array.len() {
            let delta_line = data_array[i].as_u64().unwrap_or(0) as u32;
            let delta_start = data_array[i + 1].as_u64().unwrap_or(0) as u32;
            let length = data_array[i + 2].as_u64().unwrap_or(0) as u32;
            let token_type_idx = data_array[i + 3].as_u64().unwrap_or(0) as u32;
            let token_modifiers_bitset = data_array[i + 4].as_u64().unwrap_or(0) as u32;

            // Map sidecar token type to local legend index
            let mapped_token_type = if let Some(legend) = legend_types {
                if let Some(type_name) =
                    legend.get(token_type_idx as usize).and_then(|t| t.as_str())
                {
                    // Find in local legend
                    local_legend
                        .iter()
                        .position(|&t| t == type_name)
                        .unwrap_or(0) as u32
                } else {
                    token_type_idx
                }
            } else {
                token_type_idx
            };

            tokens.push(SemanticToken {
                delta_line,
                delta_start,
                length,
                token_type: mapped_token_type,
                token_modifiers_bitset,
            });

            i += 5;
        }

        tokens
    }

    fn parse_call_hierarchy_items(&self, result: &Value) -> Vec<CallHierarchyItem> {
        let items_array = match result.get("items").and_then(|i| i.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        items_array
            .iter()
            .filter_map(|item| {
                let name = item.get("name")?.as_str()?.to_string();
                let kind_str = item.get("kind")?.as_str()?;
                let kind = Self::map_symbol_kind(kind_str);
                let uri_str = item.get("uri")?.as_str()?;
                let uri = Url::parse(uri_str).ok()?;
                let line = item.get("line")?.as_u64()?.saturating_sub(1) as u32;
                let column = item.get("column").and_then(|c| c.as_u64()).unwrap_or(0) as u32;

                Some(CallHierarchyItem {
                    name,
                    kind,
                    uri,
                    range: Range {
                        start: Position::new(line, column),
                        end: Position::new(line, column),
                    },
                    selection_range: Range {
                        start: Position::new(line, column),
                        end: Position::new(line, column),
                    },
                    detail: None,
                    tags: None,
                    data: None,
                })
            })
            .collect()
    }

    fn parse_incoming_calls(&self, result: &Value) -> Vec<CallHierarchyIncomingCall> {
        let calls_array = match result.get("calls").and_then(|c| c.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        calls_array
            .iter()
            .filter_map(|call| {
                let from_obj = call.get("from")?;
                let name = from_obj.get("name")?.as_str()?.to_string();
                let kind_str = from_obj.get("kind")?.as_str()?;
                let kind = Self::map_symbol_kind(kind_str);
                let uri_str = from_obj.get("uri")?.as_str()?;
                let uri = Url::parse(uri_str).ok()?;
                let line = from_obj.get("line")?.as_u64()?.saturating_sub(1) as u32;
                let column = from_obj.get("column").and_then(|c| c.as_u64()).unwrap_or(0) as u32;

                let from_ranges = call
                    .get("fromRanges")
                    .and_then(|r| r.as_array())
                    .map(|ranges| {
                        ranges
                            .iter()
                            .filter_map(|r| {
                                let start_line =
                                    r.get("startLine")?.as_u64()?.saturating_sub(1) as u32;
                                let start_column = r.get("startColumn")?.as_u64()? as u32;
                                let end_line = r.get("endLine")?.as_u64()?.saturating_sub(1) as u32;
                                let end_column = r.get("endColumn")?.as_u64()? as u32;

                                Some(Range {
                                    start: Position::new(start_line, start_column),
                                    end: Position::new(end_line, end_column),
                                })
                            })
                            .collect()
                    })
                    .unwrap_or_default();

                Some(CallHierarchyIncomingCall {
                    from: CallHierarchyItem {
                        name,
                        kind,
                        uri,
                        range: Range {
                            start: Position::new(line, column),
                            end: Position::new(line, column),
                        },
                        selection_range: Range {
                            start: Position::new(line, column),
                            end: Position::new(line, column),
                        },
                        detail: None,
                        tags: None,
                        data: None,
                    },
                    from_ranges,
                })
            })
            .collect()
    }

    fn parse_type_hierarchy_items(&self, result: &Value) -> Vec<TypeHierarchyItem> {
        let items_array = result
            .get("items")
            .or_else(|| result.get("supertypes"))
            .and_then(|i| i.as_array());

        let items_array = match items_array {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        items_array
            .iter()
            .filter_map(|item| {
                let name = item.get("name")?.as_str()?.to_string();
                let kind_str = item.get("kind")?.as_str()?;
                let kind = Self::map_symbol_kind(kind_str);
                let uri_str = item.get("uri")?.as_str()?;
                let uri = Url::parse(uri_str).ok()?;
                let line = item.get("line")?.as_u64()?.saturating_sub(1) as u32;
                let column = item.get("column").and_then(|c| c.as_u64()).unwrap_or(0) as u32;

                Some(TypeHierarchyItem {
                    name,
                    kind,
                    uri,
                    range: Range {
                        start: Position::new(line, column),
                        end: Position::new(line, column),
                    },
                    selection_range: Range {
                        start: Position::new(line, column),
                        end: Position::new(line, column),
                    },
                    detail: None,
                    tags: None,
                    data: None,
                })
            })
            .collect()
    }

    fn map_symbol_kind(kind: &str) -> SymbolKind {
        match kind {
            "class" => SymbolKind::CLASS,
            "interface" => SymbolKind::INTERFACE,
            "function" | "method" => SymbolKind::FUNCTION,
            "property" | "field" => SymbolKind::PROPERTY,
            "variable" | "local" => SymbolKind::VARIABLE,
            "enum" => SymbolKind::ENUM,
            "enumMember" => SymbolKind::ENUM_MEMBER,
            "module" | "package" => SymbolKind::MODULE,
            "constructor" => SymbolKind::CONSTRUCTOR,
            "constant" => SymbolKind::CONSTANT,
            "object" => SymbolKind::OBJECT,
            _ => SymbolKind::VARIABLE,
        }
    }
}

fn show_document_acknowledged(result: Option<ShowDocumentResult>) -> bool {
    match result {
        Some(result) => result.success,
        None => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parse_code_actions_preserves_command_payloads() {
        let result = json!({
            "actions": [
                {
                    "title": "Open existing test",
                    "kind": "quickfix",
                    "command": {
                        "title": "Open existing test",
                        "command": "kotlin-analyzer.openTestTarget",
                        "arguments": [
                            {
                                "targetUri": "file:///tmp/Test.kt",
                                "selection": {
                                    "startLine": 3,
                                    "startCharacter": 1,
                                    "endLine": 3,
                                    "endCharacter": 5
                                }
                            }
                        ]
                    }
                }
            ]
        });

        let actions = parse_code_actions_result(&result);
        assert_eq!(actions.len(), 1);

        let CodeActionOrCommand::CodeAction(action) = &actions[0] else {
            panic!("expected code action");
        };

        let command = action
            .command
            .as_ref()
            .expect("command should be preserved");
        assert_eq!(command.command, "kotlin-analyzer.openTestTarget");
        assert_eq!(
            command
                .arguments
                .as_ref()
                .and_then(|args| args.first())
                .cloned(),
            Some(json!({
                "targetUri": "file:///tmp/Test.kt",
                "selection": {
                    "startLine": 3,
                    "startCharacter": 1,
                    "endLine": 3,
                    "endCharacter": 5
                }
            }))
        );
        assert!(
            action.edit.is_none(),
            "command-only actions should not gain empty edits"
        );
    }

    #[test]
    fn parse_code_actions_keeps_edit_only_actions_compatible() {
        let result = json!({
            "actions": [
                {
                    "title": "Add import",
                    "kind": "quickfix",
                    "edits": [
                        {
                            "uri": "file:///tmp/Test.kt",
                            "range": {
                                "startLine": 1,
                                "startColumn": 0,
                                "endLine": 1,
                                "endColumn": 0
                            },
                            "newText": "import kotlin.collections.List\n"
                        }
                    ]
                }
            ]
        });

        let actions = parse_code_actions_result(&result);
        assert_eq!(actions.len(), 1);

        let CodeActionOrCommand::CodeAction(action) = &actions[0] else {
            panic!("expected code action");
        };

        let edit = action.edit.as_ref().expect("edit should be preserved");
        let changes = edit.changes.as_ref().expect("changes should exist");
        assert_eq!(changes.len(), 1);
        assert!(
            action.command.is_none(),
            "edit-only action should remain command-free"
        );
    }

    #[test]
    fn parse_analyzer_command_rejects_unknown_command_ids() {
        let error = parse_analyzer_command_request(ExecuteCommandParams {
            command: "kotlin-analyzer.unsupported".to_string(),
            arguments: vec![json!({})],
            work_done_progress_params: Default::default(),
        })
        .expect_err("unsupported command should fail");

        assert_eq!(error.code, ErrorCode::InvalidParams);
        assert!(error.message.contains("unsupported analyzer command"));
    }

    #[test]
    fn parse_analyzer_command_validates_required_payload() {
        let error = parse_analyzer_command_request(ExecuteCommandParams {
            command: analyzer_command_contract()
                .commands
                .create_and_open_test_target
                .id
                .clone(),
            arguments: vec![json!({
                "targetUri": "file:///tmp/Test.kt",
                "targetPath": "/tmp/Test.kt"
            })],
            work_done_progress_params: Default::default(),
        })
        .expect_err("missing initialContents should fail");

        assert_eq!(error.code, ErrorCode::InvalidParams);
        assert!(error.message.contains("invalid arguments"));
    }

    #[test]
    fn analyze_edits_are_current_requires_matching_document_and_response_versions() {
        let result = json!({
            "version": 7,
            "edits": [
                {
                    "uri": "file:///tmp/Test.kt",
                    "range": {
                        "startLine": 1,
                        "startColumn": 0,
                        "endLine": 1,
                        "endColumn": 0
                    },
                    "newText": "import model.Person\n"
                }
            ]
        });

        assert!(analyze_edits_are_current(7, Some(7), &result));
        assert!(!analyze_edits_are_current(7, Some(8), &result));
        assert!(!analyze_edits_are_current(
            7,
            Some(7),
            &json!({ "version": 8 })
        ));
    }

    #[test]
    fn response_version_handles_absent_and_non_numeric_values() {
        assert_eq!(response_version(&json!({ "version": 3 })), Some(3));
        assert_eq!(response_version(&json!({})), None);
        assert_eq!(response_version(&json!({ "version": "three" })), None);
    }

    #[test]
    fn show_document_acknowledged_accepts_null_response_for_zed_compatibility() {
        assert!(show_document_acknowledged(None));
        assert!(show_document_acknowledged(Some(ShowDocumentResult {
            success: true
        })));
        assert!(!show_document_acknowledged(Some(ShowDocumentResult {
            success: false
        })));
    }
}
