use std::collections::HashMap;

use tower_lsp::lsp_types::{Diagnostic, Url};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DocumentKind {
    Kotlin,
    Pebble,
}

impl DocumentKind {
    pub fn from_language_id(language_id: &str, uri: &Url) -> Self {
        if language_id.eq_ignore_ascii_case("pebble") {
            Self::Pebble
        } else {
            Self::from_uri(uri)
        }
    }

    pub fn from_uri(uri: &Url) -> Self {
        if let Ok(path) = uri.to_file_path() {
            let path = path.to_string_lossy().to_ascii_lowercase();
            if path.ends_with(".peb") || path.ends_with(".pebble") {
                return Self::Pebble;
            }
        }

        Self::Kotlin
    }

    pub fn did_open_method(self) -> &'static str {
        match self {
            Self::Kotlin => "textDocument/didOpen",
            Self::Pebble => "pebble/textDocument/didOpen",
        }
    }

    pub fn did_change_method(self) -> &'static str {
        match self {
            Self::Kotlin => "textDocument/didChange",
            Self::Pebble => "pebble/textDocument/didChange",
        }
    }

    pub fn did_close_method(self) -> &'static str {
        match self {
            Self::Kotlin => "textDocument/didClose",
            Self::Pebble => "pebble/textDocument/didClose",
        }
    }

    pub fn definition_method(self) -> &'static str {
        match self {
            Self::Kotlin => "definition",
            Self::Pebble => "pebble/definition",
        }
    }

    pub fn references_method(self) -> &'static str {
        match self {
            Self::Kotlin => "references",
            Self::Pebble => "pebble/references",
        }
    }

    pub fn supports_kotlin_analysis(self) -> bool {
        matches!(self, Self::Kotlin)
    }
}

/// Stores the full text and version for every open document.
/// This is the single source of truth for document state —
/// used for replay after sidecar restart.
#[derive(Debug, Default)]
pub struct DocumentStore {
    documents: HashMap<Url, Document>,
    /// Cached diagnostics per URI — persists across didClose/didOpen cycles
    /// so that diagnostics survive tab switches in Zed.
    diagnostics: HashMap<Url, Vec<Diagnostic>>,
}

#[derive(Debug, Clone)]
pub struct Document {
    pub text: String,
    pub version: i32,
    pub kind: DocumentKind,
}

impl DocumentStore {
    pub fn open(&mut self, uri: Url, text: String, version: i32, kind: DocumentKind) {
        self.documents.insert(
            uri,
            Document {
                text,
                version,
                kind,
            },
        );
    }

    pub fn change(&mut self, uri: &Url, text: String, version: i32) -> bool {
        if let Some(doc) = self.documents.get_mut(uri) {
            doc.text = text;
            doc.version = version;
            true
        } else {
            false
        }
    }

    pub fn close(&mut self, uri: &Url) -> bool {
        self.documents.remove(uri).is_some()
    }

    pub fn get(&self, uri: &Url) -> Option<&Document> {
        self.documents.get(uri)
    }

    #[allow(dead_code)]
    pub fn all_documents(&self) -> impl Iterator<Item = &Document> {
        self.documents.values()
    }

    pub fn all(&self) -> impl Iterator<Item = (&Url, &Document)> {
        self.documents.iter()
    }

    #[allow(dead_code)]
    pub fn is_open(&self, uri: &Url) -> bool {
        self.documents.contains_key(uri)
    }

    pub fn set_diagnostics(&mut self, uri: Url, diags: Vec<Diagnostic>) {
        self.diagnostics.insert(uri, diags);
    }

    pub fn get_diagnostics(&self, uri: &Url) -> Option<&Vec<Diagnostic>> {
        self.diagnostics.get(uri)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_uri(path: &str) -> Url {
        Url::parse(&format!("file:///{path}")).unwrap()
    }

    #[test]
    fn open_and_retrieve() {
        let mut store = DocumentStore::default();
        let uri = test_uri("test.kt");
        store.open(uri.clone(), "fun main() {}".into(), 1, DocumentKind::Kotlin);

        let doc = store.get(&uri).unwrap();
        assert_eq!(doc.text, "fun main() {}");
        assert_eq!(doc.version, 1);
        assert_eq!(doc.kind, DocumentKind::Kotlin);
    }

    #[test]
    fn change_updates_content() {
        let mut store = DocumentStore::default();
        let uri = test_uri("test.kt");
        store.open(uri.clone(), "fun main() {}".into(), 1, DocumentKind::Kotlin);

        assert!(store.change(&uri, "fun main() { println() }".into(), 2));
        let doc = store.get(&uri).unwrap();
        assert_eq!(doc.text, "fun main() { println() }");
        assert_eq!(doc.version, 2);
    }

    #[test]
    fn change_nonexistent_returns_false() {
        let mut store = DocumentStore::default();
        let uri = test_uri("missing.kt");
        assert!(!store.change(&uri, "text".into(), 1));
    }

    #[test]
    fn close_removes_document() {
        let mut store = DocumentStore::default();
        let uri = test_uri("test.kt");
        store.open(uri.clone(), "fun main() {}".into(), 1, DocumentKind::Kotlin);

        assert!(store.close(&uri));
        assert!(store.get(&uri).is_none());
    }

    #[test]
    fn close_nonexistent_returns_false() {
        let mut store = DocumentStore::default();
        let uri = test_uri("missing.kt");
        assert!(!store.close(&uri));
    }

    #[test]
    fn is_open_tracks_state() {
        let mut store = DocumentStore::default();
        let uri = test_uri("test.kt");

        assert!(!store.is_open(&uri));
        store.open(uri.clone(), "text".into(), 1, DocumentKind::Kotlin);
        assert!(store.is_open(&uri));
        store.close(&uri);
        assert!(!store.is_open(&uri));
    }

    #[test]
    fn all_documents_iterates_open_docs() {
        let mut store = DocumentStore::default();
        store.open(test_uri("a.kt"), "a".into(), 1, DocumentKind::Kotlin);
        store.open(test_uri("b.kt"), "b".into(), 1, DocumentKind::Kotlin);
        store.open(test_uri("c.kt"), "c".into(), 1, DocumentKind::Kotlin);

        assert_eq!(store.all_documents().count(), 3);
    }

    #[test]
    fn multiple_changes() {
        let mut store = DocumentStore::default();
        let uri = test_uri("test.kt");
        store.open(uri.clone(), "v1".into(), 1, DocumentKind::Kotlin);
        store.change(&uri, "v2".into(), 2);
        store.change(&uri, "v3".into(), 3);

        let doc = store.get(&uri).unwrap();
        assert_eq!(doc.text, "v3");
        assert_eq!(doc.version, 3);
    }

    #[test]
    fn pebble_language_id_takes_precedence() {
        let uri = test_uri("test.kt");
        assert_eq!(
            DocumentKind::from_language_id("pebble", &uri),
            DocumentKind::Pebble
        );
    }

    #[test]
    fn pebble_extension_is_detected_from_uri() {
        let uri = test_uri("templates/page.pebble");
        assert_eq!(DocumentKind::from_uri(&uri), DocumentKind::Pebble);
    }
}
