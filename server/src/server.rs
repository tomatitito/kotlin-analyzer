use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use lsp_types::*;
use serde_json::Value;
use tokio::io::AsyncWriteExt;
use tokio::process::Command;
use tokio::sync::Mutex;
use tower_lsp::jsonrpc::Result as LspResult;
use tower_lsp::lsp_types;
use tower_lsp::{Client, LanguageServer};

use crate::bridge::{Bridge, SidecarState};
use crate::config::{Config, FormattingTool};
use crate::project;
use crate::state::DocumentStore;

/// The main language server implementation.
pub struct KotlinLanguageServer {
    client: Client,
    documents: Arc<Mutex<DocumentStore>>,
    bridge: Arc<Mutex<Option<Bridge>>>,
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

    /// Publishes diagnostics for a document by requesting analysis from the sidecar.
    async fn analyze_document(&self, uri: &Url) {
        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return,
        };

        if bridge.state().await != SidecarState::Ready {
            return;
        }

        let (text, version) = {
            let documents = self.documents.lock().await;
            match documents.get(uri) {
                Some(d) => (d.text.clone(), d.version),
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
                })),
            )
            .await
        {
            Ok(result) => {
                let diagnostics = self.parse_diagnostics(&result);
                self.client
                    .publish_diagnostics(uri.clone(), diagnostics, None)
                    .await;
            }
            Err(e) => {
                tracing::warn!("analysis failed for {}: {}", uri, e);
            }
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
                            let bridge = bridge.lock().await;
                            if let Some(bridge) = bridge.as_ref() {
                                if bridge.state().await == SidecarState::Ready {
                                    let documents = documents.lock().await;
                                    if let Some(doc) = documents.get(&uri) {
                                        let text = doc.text.clone();
                                        let version = doc.version;
                                        drop(documents);

                                        let _ = bridge.notify("textDocument/didChange", Some(serde_json::json!({
                                            "uri": uri.as_str(),
                                            "version": version,
                                            "text": text,
                                        }))).await;

                                        match bridge.request("analyze", Some(serde_json::json!({
                                            "uri": uri.as_str(),
                                        }))).await {
                                            Ok(result) => {
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

        // Store project root
        if let Some(root_uri) = params.root_uri {
            if let Ok(path) = root_uri.to_file_path() {
                let mut project_root = self.project_root.lock().await;
                *project_root = Some(path.clone());

                // Resolve project model in background
                let config = self.config.lock().await.clone();
                let client = self.client.clone();

                tokio::spawn(async move {
                    match project::resolve_project(&path, &config) {
                        Ok(model) => {
                            tracing::info!(
                                "project resolved: {} source roots, {} classpath entries, {} compiler flags",
                                model.source_roots.len(),
                                model.classpath.len(),
                                model.compiler_flags.len()
                            );

                            // Cache the project model
                            let cache_dir = path.join(".kotlin-analyzer");
                            if let Err(e) = project::save_cache(&model, &cache_dir) {
                                tracing::warn!("failed to cache project model: {}", e);
                            }
                        }
                        Err(e) => {
                            tracing::warn!("project resolution failed: {}", e);
                            let _ = client
                                .show_message(
                                    MessageType::WARNING,
                                    format!("kotlin-analyzer: {}", e),
                                )
                                .await;
                        }
                    }
                });
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
                code_action_provider: Some(CodeActionProviderCapability::Simple(true)),
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

            if let Err(e) = client
                .register_capability(vec![registration])
                .await
            {
                tracing::warn!("failed to register file watchers: {:?}", e);
            }
        });

        Ok(result)
    }

    async fn initialized(&self, _: InitializedParams) {
        tracing::info!("kotlin-analyzer: initialized");

        // Create progress token
        let token = NumberOrString::String("kotlin-analyzer-startup".to_string());

        // Create work done progress
        if let Err(e) = self
            .client
            .send_request::<lsp_types::request::WorkDoneProgressCreate>(
                WorkDoneProgressCreateParams {
                    token: token.clone(),
                },
            )
            .await
        {
            tracing::warn!("failed to create progress token: {:?}", e);
        }

        // Send begin progress
        self.client
            .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                token: token.clone(),
                value: ProgressParamsValue::WorkDone(WorkDoneProgress::Begin(
                    WorkDoneProgressBegin {
                        title: "Starting Kotlin sidecar".to_string(),
                        message: Some("Initializing JVM...".to_string()),
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
                self.client
                    .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                        token: token.clone(),
                        value: ProgressParamsValue::WorkDone(WorkDoneProgress::End(
                            WorkDoneProgressEnd {
                                message: Some(format!("Failed: {}", e)),
                            },
                        )),
                    })
                    .await;
                self.client
                    .show_message(
                        MessageType::ERROR,
                        "kotlin-analyzer: JDK 17+ required but not found. Set JAVA_HOME or KOTLIN_LS_JAVA_HOME.",
                    )
                    .await;
                return;
            }
        };

        // Find sidecar JAR - look relative to the server binary
        let sidecar_jar = find_sidecar_jar();
        let sidecar_jar = match sidecar_jar {
            Some(p) => p,
            None => {
                tracing::warn!("sidecar JAR not found, semantic features unavailable");
                self.client
                    .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                        token: token.clone(),
                        value: ProgressParamsValue::WorkDone(WorkDoneProgress::End(
                            WorkDoneProgressEnd {
                                message: Some("sidecar.jar not found".to_string()),
                            },
                        )),
                    })
                    .await;
                self.client
                    .show_message(
                        MessageType::WARNING,
                        "kotlin-analyzer: sidecar.jar not found. Semantic features are unavailable.",
                    )
                    .await;
                return;
            }
        };

        let config = self.config.lock().await.clone();
        let bridge = Bridge::new(sidecar_jar, java_path, config);

        // Set up replay callback for document restoration after restart
        // Note: Replay is currently logged but not fully implemented
        // Full restart with replay would require restructuring the bridge's stdin handling
        bridge
            .set_replay_callback(move || {
                // This is a placeholder - a real implementation would need to
                // coordinate with the bridge to send documents to the new sidecar process
                Vec::new()
            })
            .await;

        match bridge.start().await {
            Ok(()) => {
                tracing::info!("sidecar started successfully");
                self.client
                    .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                        token: token.clone(),
                        value: ProgressParamsValue::WorkDone(WorkDoneProgress::End(
                            WorkDoneProgressEnd {
                                message: Some("Ready".to_string()),
                            },
                        )),
                    })
                    .await;

                let mut b = self.bridge.lock().await;
                *b = Some(bridge);
            }
            Err(e) => {
                tracing::error!("failed to start sidecar: {}", e);
                self.client
                    .send_notification::<lsp_types::notification::Progress>(ProgressParams {
                        token: token.clone(),
                        value: ProgressParamsValue::WorkDone(WorkDoneProgress::End(
                            WorkDoneProgressEnd {
                                message: Some(format!("Failed: {}", e)),
                            },
                        )),
                    })
                    .await;
                self.client
                    .show_message(
                        MessageType::ERROR,
                        format!("kotlin-analyzer: failed to start sidecar: {}", e),
                    )
                    .await;
            }
        }
    }

    async fn shutdown(&self) -> LspResult<()> {
        tracing::info!("kotlin-analyzer: shutting down");

        let bridge = self.bridge.lock().await;
        if let Some(bridge) = bridge.as_ref() {
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

        {
            let mut documents = self.documents.lock().await;
            documents.open(uri.clone(), text.clone(), version);
        }

        // Notify sidecar
        let bridge = self.bridge.lock().await;
        if let Some(bridge) = bridge.as_ref() {
            let _ = bridge
                .notify(
                    "textDocument/didOpen",
                    Some(serde_json::json!({
                        "uri": uri.as_str(),
                        "version": version,
                        "text": text,
                    })),
                )
                .await;
        }
        drop(bridge);

        // Trigger analysis
        self.analyze_document(&uri).await;
    }

    async fn did_change(&self, params: DidChangeTextDocumentParams) {
        let uri = params.text_document.uri.clone();
        let version = params.text_document.version;

        // Full sync mode — take the last content change
        if let Some(change) = params.content_changes.into_iter().last() {
            let mut documents = self.documents.lock().await;
            documents.change(&uri, change.text, version);
        }

        // Send to debounce loop for analysis
        let debounce = self.debounce_tx.lock().await;
        if let Some(tx) = debounce.as_ref() {
            let _ = tx.send(uri).await;
        }
    }

    async fn did_close(&self, params: DidCloseTextDocumentParams) {
        let uri = params.text_document.uri.clone();

        {
            let mut documents = self.documents.lock().await;
            documents.close(&uri);
        }

        // Notify sidecar
        let bridge = self.bridge.lock().await;
        if let Some(bridge) = bridge.as_ref() {
            let _ = bridge
                .notify(
                    "textDocument/didClose",
                    Some(serde_json::json!({
                        "uri": uri.as_str(),
                    })),
                )
                .await;
        }

        // Clear diagnostics for closed document
        self.client.publish_diagnostics(uri, Vec::new(), None).await;
    }

    async fn completion(&self, params: CompletionParams) -> LspResult<Option<CompletionResponse>> {
        let uri = params.text_document_position.text_document.uri;
        let position = params.text_document_position.position;

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
        };

        match bridge
            .request(
                "completion",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "line": position.line + 1,
                    "character": position.character,
                })),
            )
            .await
        {
            Ok(result) => {
                let items = self.parse_completion_items(&result);
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
        };

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
                if let Some(contents) = result.get("contents").and_then(|c| c.as_str()) {
                    Ok(Some(Hover {
                        contents: HoverContents::Markup(MarkupContent {
                            kind: MarkupKind::Markdown,
                            value: contents.to_string(),
                        }),
                        range: None,
                    }))
                } else {
                    Ok(None)
                }
            }
            Err(e) => {
                tracing::warn!("hover failed: {}", e);
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
        };

        match bridge
            .request(
                "definition",
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
        };

        match bridge
            .request(
                "references",
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
                let binary = config.formatting_path.unwrap_or_else(|| "ktfmt".to_string());
                match self.format_with_ktfmt(&binary, &original_text, &config.formatting_style).await {
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
                let binary = config.formatting_path.unwrap_or_else(|| "ktlint".to_string());
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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
            tracing::info!("configuration updated");
            let mut c = self.config.lock().await;
            *c = config.clone();

            let bridge = self.bridge.lock().await;
            if let Some(bridge) = bridge.as_ref() {
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

            // Check if it's a build file
            if path_str.ends_with(".gradle")
                || path_str.ends_with(".gradle.kts")
                || path_str.ends_with("gradle.properties")
            {
                tracing::info!("build file changed: {}, triggering project re-resolution", path_str);

                let project_root = self.project_root.lock().await.clone();
                if let Some(root) = project_root {
                    let config = self.config.lock().await.clone();
                    let client = self.client.clone();

                    tokio::spawn(async move {
                        match project::resolve_project(&root, &config) {
                            Ok(model) => {
                                tracing::info!("project re-resolved after build file change");
                                let cache_dir = root.join(".kotlin-analyzer");
                                if let Err(e) = project::save_cache(&model, &cache_dir) {
                                    tracing::warn!("failed to cache project model: {}", e);
                                }
                            }
                            Err(e) => {
                                tracing::warn!("project re-resolution failed: {}", e);
                                let _ = client
                                    .show_message(
                                        MessageType::WARNING,
                                        format!("kotlin-analyzer: project re-resolution failed: {}", e),
                                    )
                                    .await;
                            }
                        }
                    });
                }
            } else if path_str.ends_with(".editorconfig") {
                tracing::info!(".editorconfig changed: {}", path_str);
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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
                let edits = self.parse_workspace_edits(&result);
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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
                let actions = self.parse_code_actions(&result);
                if actions.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(actions))
                }
            }
            Err(e) => {
                tracing::warn!("code_action failed: {}", e);
                Ok(None)
            }
        }
    }

    async fn symbol(&self, params: WorkspaceSymbolParams) -> LspResult<Option<Vec<SymbolInformation>>> {
        let query = params.query;

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
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

                Some(CompletionItem {
                    label,
                    kind,
                    detail,
                    insert_text,
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

    fn parse_text_edits(&self, result: &Value) -> Vec<TextEdit> {
        let edits = match result.get("edits").and_then(|e| e.as_array()) {
            Some(arr) => arr,
            None => return Vec::new(),
        };

        edits
            .iter()
            .filter_map(|edit| {
                let range = edit.get("range")?;
                let start_line = range.get("startLine")?.as_u64()? as u32;
                let start_col = range.get("startColumn")?.as_u64()? as u32;
                let end_line = range.get("endLine")?.as_u64()? as u32;
                let end_col = range.get("endColumn")?.as_u64()? as u32;
                let new_text = edit.get("newText")?.as_str()?.to_string();

                Some(TextEdit {
                    range: Range {
                        start: Position::new(start_line, start_col),
                        end: Position::new(end_line, end_col),
                    },
                    new_text,
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

    fn parse_workspace_edits(&self, result: &Value) -> HashMap<Url, Vec<TextEdit>> {
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
            let end_col = range
                .get("endColumn")
                .and_then(|c| c.as_u64())
                .unwrap_or(0) as u32;

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

    fn parse_code_actions(&self, result: &Value) -> CodeActionResponse {
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

                let edits = self.parse_workspace_edits(action);

                Some(CodeActionOrCommand::CodeAction(CodeAction {
                    title,
                    kind,
                    diagnostics: None,
                    edit: Some(WorkspaceEdit {
                        changes: Some(edits),
                        document_changes: None,
                        change_annotations: None,
                    }),
                    command: None,
                    is_preferred: None,
                    disabled: None,
                    data: None,
                }))
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
                    container_name: sym.get("containerName").and_then(|c| c.as_str()).map(String::from),
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

                let kind = hint.get("kind").and_then(|k| k.as_str()).and_then(|k| match k {
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
                if let Some(type_name) = legend.get(token_type_idx as usize).and_then(|t| t.as_str()) {
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
                                let start_line = r.get("startLine")?.as_u64()?.saturating_sub(1) as u32;
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

/// Finds the sidecar JAR relative to the server binary.
fn find_sidecar_jar() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let exe_dir = exe.parent()?;

    // Check next to the binary
    let jar = exe_dir.join("sidecar.jar");
    if jar.exists() {
        return Some(jar);
    }

    // Check in the sidecar build output (development)
    let dev_jar = exe_dir
        .parent()?
        .parent()?
        .parent()?
        .join("sidecar/build/libs/sidecar-all.jar");
    if dev_jar.exists() {
        return Some(dev_jar);
    }

    None
}
