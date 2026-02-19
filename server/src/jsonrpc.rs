use std::io;

use serde::{Deserialize, Serialize};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::process::{ChildStdin, ChildStdout};

use crate::error::ProtocolError;

/// JSON-RPC 2.0 request.
#[derive(Debug, Serialize, Deserialize)]
pub struct Request {
    pub jsonrpc: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<u64>,
    pub method: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub params: Option<serde_json::Value>,
}

/// JSON-RPC 2.0 response.
#[derive(Debug, Serialize, Deserialize)]
pub struct Response {
    pub jsonrpc: String,
    pub id: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ResponseError>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ResponseError {
    pub code: i32,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data: Option<serde_json::Value>,
}

impl Request {
    pub fn new(id: u64, method: &str, params: Option<serde_json::Value>) -> Self {
        Self {
            jsonrpc: "2.0".into(),
            id: Some(id),
            method: method.into(),
            params,
        }
    }

    pub fn notification(method: &str, params: Option<serde_json::Value>) -> Self {
        Self {
            jsonrpc: "2.0".into(),
            id: None,
            method: method.into(),
            params,
        }
    }
}

/// Writes a JSON-RPC message with Content-Length framing to an async writer.
pub async fn write_message(
    writer: &mut ChildStdin,
    message: &impl Serialize,
) -> Result<(), crate::error::Error> {
    let body = serde_json::to_string(message).map_err(ProtocolError::JsonParse)?;
    let header = format!("Content-Length: {}\r\n\r\n", body.len());

    writer
        .write_all(header.as_bytes())
        .await
        .map_err(crate::error::Error::Io)?;
    writer
        .write_all(body.as_bytes())
        .await
        .map_err(crate::error::Error::Io)?;
    writer.flush().await.map_err(crate::error::Error::Io)?;

    Ok(())
}

/// Reads a JSON-RPC message with Content-Length framing from an async reader.
/// Returns `None` on EOF (sidecar exited).
pub async fn read_message(
    reader: &mut BufReader<ChildStdout>,
) -> Result<Option<Response>, crate::error::Error> {
    let content_length = match read_content_length(reader).await? {
        Some(len) => len,
        None => return Ok(None), // EOF
    };

    let mut body = vec![0u8; content_length];
    match reader.read_exact(&mut body).await {
        Ok(_) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(crate::error::Error::Io(e)),
    }

    let response: Response =
        serde_json::from_slice(&body).map_err(ProtocolError::JsonParse)?;

    Ok(Some(response))
}

/// Reads headers until the empty line separator, extracts Content-Length.
async fn read_content_length(
    reader: &mut BufReader<ChildStdout>,
) -> Result<Option<usize>, crate::error::Error> {
    let mut content_length: Option<usize> = None;

    loop {
        let mut line = String::new();
        let bytes_read = reader
            .read_line(&mut line)
            .await
            .map_err(crate::error::Error::Io)?;

        if bytes_read == 0 {
            return Ok(None); // EOF
        }

        let trimmed = line.trim();
        if trimmed.is_empty() {
            break; // End of headers
        }

        if let Some(value) = trimmed.strip_prefix("Content-Length: ") {
            content_length = Some(value.parse().map_err(|_| {
                ProtocolError::InvalidJsonRpc(format!("invalid Content-Length: {value}"))
            })?);
        }
    }

    match content_length {
        Some(len) => Ok(Some(len)),
        None => Err(ProtocolError::MissingContentLength.into()),
    }
}

/// Encodes a message into Content-Length framed bytes.
#[cfg(test)]
pub fn encode_message(message: &impl Serialize) -> Result<Vec<u8>, ProtocolError> {
    let body = serde_json::to_string(message)?;
    let header = format!("Content-Length: {}\r\n\r\n", body.len());
    let mut buf = Vec::with_capacity(header.len() + body.len());
    buf.extend_from_slice(header.as_bytes());
    buf.extend_from_slice(body.as_bytes());
    Ok(buf)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serialize_request() {
        let req = Request::new(1, "hover", Some(serde_json::json!({"uri": "file:///a.kt"})));
        let json = serde_json::to_string(&req).unwrap();
        assert!(json.contains("\"jsonrpc\":\"2.0\""));
        assert!(json.contains("\"id\":1"));
        assert!(json.contains("\"method\":\"hover\""));
    }

    #[test]
    fn serialize_notification() {
        let req = Request::notification("textDocument/didOpen", None);
        let json = serde_json::to_string(&req).unwrap();
        assert!(!json.contains("\"id\""));
    }

    #[test]
    fn encode_message_format() {
        let req = Request::new(1, "ping", None);
        let encoded = encode_message(&req).unwrap();
        let text = String::from_utf8(encoded).unwrap();
        assert!(text.starts_with("Content-Length: "));
        assert!(text.contains("\r\n\r\n"));
    }

    #[test]
    fn deserialize_response() {
        let json = r#"{"jsonrpc":"2.0","id":1,"result":{"pong":true}}"#;
        let resp: Response = serde_json::from_str(json).unwrap();
        assert_eq!(resp.id, Some(1));
        assert!(resp.result.is_some());
        assert!(resp.error.is_none());
    }

    #[test]
    fn deserialize_error_response() {
        let json =
            r#"{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}"#;
        let resp: Response = serde_json::from_str(json).unwrap();
        assert_eq!(resp.id, Some(1));
        assert!(resp.result.is_none());
        let err = resp.error.unwrap();
        assert_eq!(err.code, -32601);
        assert_eq!(err.message, "Method not found");
    }

    #[test]
    fn request_with_params() {
        let params = serde_json::json!({
            "uri": "file:///test.kt",
            "line": 10,
            "character": 5
        });
        let req = Request::new(42, "textDocument/hover", Some(params));
        assert_eq!(req.id, Some(42));
        assert_eq!(req.method, "textDocument/hover");
        let p = req.params.unwrap();
        assert_eq!(p["line"], 10);
    }
}
