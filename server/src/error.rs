use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    #[error("bridge: {0}")]
    Bridge(#[from] BridgeError),

    #[error("protocol: {0}")]
    Protocol(#[from] ProtocolError),

    #[error("project: {0}")]
    Project(#[from] ProjectError),

    #[error("io: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug, Error)]
pub enum BridgeError {
    #[error("sidecar not ready: {0}")]
    NotReady(String),

    #[error("sidecar crashed: {0}")]
    Crashed(String),

    #[error("sidecar response timeout after {0}ms")]
    Timeout(u64),

    #[error("malformed response: {0}")]
    MalformedResponse(String),

    #[error("spawn failed: {0}")]
    SpawnFailed(String),
}

#[derive(Debug, Error)]
pub enum ProtocolError {
    #[error("invalid json-rpc: {0}")]
    InvalidJsonRpc(String),

    #[error("missing content-length header")]
    MissingContentLength,

    #[error("content-length mismatch: expected {expected}, got {actual}")]
    ContentLengthMismatch { expected: usize, actual: usize },

    #[error("json parse error: {0}")]
    JsonParse(#[from] serde_json::Error),
}

#[derive(Debug, Error)]
pub enum ProjectError {
    #[error("gradle execution failed: {0}")]
    GradleFailed(String),

    #[error("no build system found in {0}")]
    NoBuildSystem(String),

    #[error("classpath extraction failed: {0}")]
    ClasspathExtraction(String),

    #[error("jvm not found: {0}")]
    JvmNotFound(String),
}

pub type Result<T> = std::result::Result<T, Error>;
