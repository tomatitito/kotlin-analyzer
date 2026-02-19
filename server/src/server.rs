use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use lsp_types::*;
use serde_json::Value;
use tokio::sync::Mutex;
use tower_lsp::jsonrpc::Result as LspResult;
use tower_lsp::lsp_types;
use tower_lsp::{Client, LanguageServer};

use crate::bridge::{Bridge, SidecarState};
use crate::config::Config;
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

        Ok(InitializeResult {
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
                ..Default::default()
            },
            server_info: Some(ServerInfo {
                name: "kotlin-analyzer".into(),
                version: Some(env!("CARGO_PKG_VERSION").into()),
            }),
        })
    }

    async fn initialized(&self, _: InitializedParams) {
        tracing::info!("kotlin-analyzer: initialized");

        // Try to start the sidecar
        let java_path = match crate::bridge::find_java() {
            Ok(p) => p,
            Err(e) => {
                tracing::error!("JVM not found: {}", e);
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

        match bridge.start().await {
            Ok(()) => {
                tracing::info!("sidecar started successfully");
                let mut b = self.bridge.lock().await;
                *b = Some(bridge);
            }
            Err(e) => {
                tracing::error!("failed to start sidecar: {}", e);
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

        let bridge = self.bridge.lock().await;
        let bridge = match bridge.as_ref() {
            Some(b) => b,
            None => return Ok(None),
        };

        match bridge
            .request(
                "formatting",
                Some(serde_json::json!({
                    "uri": uri.as_str(),
                    "options": {
                        "tabSize": params.options.tab_size,
                        "insertSpaces": params.options.insert_spaces,
                    },
                })),
            )
            .await
        {
            Ok(result) => {
                let edits = self.parse_text_edits(&result);
                if edits.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(edits))
                }
            }
            Err(e) => {
                tracing::warn!("formatting failed: {}", e);
                Ok(None)
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
