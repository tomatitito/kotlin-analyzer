//! Tolerant parser for the `completion_metrics` tracing target emitted by the
//! Rust LSP server.
//!
//! The default `tracing-subscriber` `fmt` formatter emits lines roughly like:
//!
//! ```text
//! 2026-05-06T12:00:00.123Z  INFO completion_metrics: completion timing
//!     correlation_id=42 uri=file:///x.kt line=10 character=5
//!     bridge_response_wait_ms=159 rust_total_ms=161 supersession_seq=5
//!     superseded_before_send_total=4 superseded_in_flight_total=0
//!     sidecar={"stages":{"sidecar_queue_wait":103.2},"totalMs":51.3}
//!     ...
//! ```
//!
//! For `Ok(None)` outcomes the message is `completion superseded` and the
//! payload includes `stage=BeforeSend` or `stage=InFlight`.
//!
//! We use a small hand-rolled regex parser rather than committing to the
//! exact field order, because the format string is not part of any contract
//! we control.

use regex::Regex;
use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SupersessionStage {
    BeforeSend,
    InFlight,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "snake_case", tag = "kind")]
pub enum CompletionMetricsRecord {
    /// Emitted when a completion request returned (with or without items).
    Returned {
        correlation_id: String,
        supersession_seq: Option<u64>,
        bridge_response_wait_ms: Option<f64>,
        bridge_total_ms: Option<f64>,
        rust_total_ms: Option<f64>,
        sidecar_queue_wait_ms: Option<f64>,
        sidecar_total_ms: Option<f64>,
        superseded_before_send_total: Option<u64>,
        superseded_in_flight_total: Option<u64>,
    },
    /// Emitted when a completion request was dropped or discarded.
    Superseded {
        correlation_id: String,
        supersession_seq: Option<u64>,
        latest_seq: Option<u64>,
        stage: Option<SupersessionStage>,
        rust_total_ms: Option<f64>,
        superseded_before_send_total: Option<u64>,
        superseded_in_flight_total: Option<u64>,
    },
}

impl CompletionMetricsRecord {
    pub fn correlation_id(&self) -> &str {
        match self {
            CompletionMetricsRecord::Returned { correlation_id, .. } => correlation_id,
            CompletionMetricsRecord::Superseded { correlation_id, .. } => correlation_id,
        }
    }

    pub fn before_send_total(&self) -> Option<u64> {
        match self {
            CompletionMetricsRecord::Returned {
                superseded_before_send_total,
                ..
            } => *superseded_before_send_total,
            CompletionMetricsRecord::Superseded {
                superseded_before_send_total,
                ..
            } => *superseded_before_send_total,
        }
    }

    pub fn in_flight_total(&self) -> Option<u64> {
        match self {
            CompletionMetricsRecord::Returned {
                superseded_in_flight_total,
                ..
            } => *superseded_in_flight_total,
            CompletionMetricsRecord::Superseded {
                superseded_in_flight_total,
                ..
            } => *superseded_in_flight_total,
        }
    }
}

/// Parse a single stderr line, returning a record if the line carries a
/// `completion_metrics` payload.
///
/// Strips ANSI escape sequences first because the default `tracing`
/// formatter colourises field names and even the `key=value` separator,
/// which would otherwise wreck simple regex matches.
pub fn parse_line(line: &str) -> Option<CompletionMetricsRecord> {
    let stripped = strip_ansi(line);
    let line = stripped.as_str();
    if !line.contains("completion_metrics") {
        return None;
    }

    let correlation_id = capture_string(line, r"correlation_id=([^\s]+)")?;

    if line.contains("completion superseded") {
        return Some(CompletionMetricsRecord::Superseded {
            correlation_id,
            supersession_seq: capture_u64(line, r"supersession_seq=(\d+)"),
            latest_seq: capture_u64(line, r"latest_seq=(\d+)"),
            stage: capture_stage(line),
            rust_total_ms: capture_f64(line, r"rust_total_ms=([\d.]+)"),
            superseded_before_send_total: capture_u64(line, r"superseded_before_send_total=(\d+)"),
            superseded_in_flight_total: capture_u64(line, r"superseded_in_flight_total=(\d+)"),
        });
    }

    if line.contains("completion timing") {
        return Some(CompletionMetricsRecord::Returned {
            correlation_id,
            supersession_seq: capture_u64(line, r"supersession_seq=(\d+)"),
            bridge_response_wait_ms: capture_f64(line, r"bridge_response_wait_ms=([\d.]+)"),
            bridge_total_ms: capture_f64(line, r"bridge_total_ms=([\d.]+)"),
            rust_total_ms: capture_f64(line, r"rust_total_ms=([\d.]+)"),
            sidecar_queue_wait_ms: capture_f64(line, r#""sidecar_queue_wait":([\d.]+)"#),
            sidecar_total_ms: capture_f64(line, r#""totalMs":([\d.]+)"#),
            superseded_before_send_total: capture_u64(line, r"superseded_before_send_total=(\d+)"),
            superseded_in_flight_total: capture_u64(line, r"superseded_in_flight_total=(\d+)"),
        });
    }

    None
}

/// Parse all metrics records out of a slice of stderr lines, preserving the
/// order in which they were emitted.
pub fn parse_lines(lines: &[String]) -> Vec<CompletionMetricsRecord> {
    lines.iter().filter_map(|l| parse_line(l)).collect()
}

/// Strip ANSI CSI escape sequences (`ESC [ ... letter`). Intentionally
/// minimal — we only need it to make `key=value` payloads regex-clean.
fn strip_ansi(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == 0x1b && i + 1 < bytes.len() && bytes[i + 1] == b'[' {
            // Skip ESC, [, then params/intermediate bytes until a final byte
            // in 0x40..=0x7e.
            i += 2;
            while i < bytes.len() {
                let b = bytes[i];
                i += 1;
                if (0x40..=0x7e).contains(&b) {
                    break;
                }
            }
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn capture_string(line: &str, pattern: &str) -> Option<String> {
    let re = Regex::new(pattern).ok()?;
    re.captures(line)
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().to_string())
}

fn capture_u64(line: &str, pattern: &str) -> Option<u64> {
    capture_string(line, pattern).and_then(|s| s.parse().ok())
}

fn capture_f64(line: &str, pattern: &str) -> Option<f64> {
    capture_string(line, pattern).and_then(|s| s.parse().ok())
}

fn capture_stage(line: &str) -> Option<SupersessionStage> {
    let raw = capture_string(line, r"stage=(\w+)")?;
    match raw.as_str() {
        "BeforeSend" => Some(SupersessionStage::BeforeSend),
        "InFlight" => Some(SupersessionStage::InFlight),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_completion_timing_with_sidecar_blob() {
        let line = r#"2026-05-06T12:00:00.123Z  INFO completion_metrics: completion timing correlation_id=42 uri=file:///x.kt bridge_response_wait_ms=159.4 bridge_total_ms=161.0 rust_total_ms=161.3 supersession_seq=5 superseded_before_send_total=4 superseded_in_flight_total=0 sidecar={"stages":{"sidecar_queue_wait":103.2},"totalMs":51.3}"#;
        let parsed = parse_line(line).expect("should parse");
        match parsed {
            CompletionMetricsRecord::Returned {
                correlation_id,
                bridge_response_wait_ms,
                rust_total_ms,
                sidecar_queue_wait_ms,
                sidecar_total_ms,
                superseded_before_send_total,
                superseded_in_flight_total,
                supersession_seq,
                ..
            } => {
                assert_eq!(correlation_id, "42");
                assert_eq!(bridge_response_wait_ms, Some(159.4));
                assert_eq!(rust_total_ms, Some(161.3));
                assert_eq!(sidecar_queue_wait_ms, Some(103.2));
                assert_eq!(sidecar_total_ms, Some(51.3));
                assert_eq!(superseded_before_send_total, Some(4));
                assert_eq!(superseded_in_flight_total, Some(0));
                assert_eq!(supersession_seq, Some(5));
            }
            other => panic!("expected Returned, got {other:?}"),
        }
    }

    #[test]
    fn parses_completion_superseded_in_flight() {
        let line = r#"2026-05-06T12:00:00Z  INFO completion_metrics: completion superseded correlation_id=41 supersession_seq=4 latest_seq=5 stage=InFlight rust_total_ms=160.0 superseded_before_send_total=0 superseded_in_flight_total=4"#;
        let parsed = parse_line(line).expect("should parse");
        match parsed {
            CompletionMetricsRecord::Superseded {
                correlation_id,
                stage,
                supersession_seq,
                latest_seq,
                rust_total_ms,
                superseded_in_flight_total,
                ..
            } => {
                assert_eq!(correlation_id, "41");
                assert_eq!(stage, Some(SupersessionStage::InFlight));
                assert_eq!(supersession_seq, Some(4));
                assert_eq!(latest_seq, Some(5));
                assert_eq!(rust_total_ms, Some(160.0));
                assert_eq!(superseded_in_flight_total, Some(4));
            }
            other => panic!("expected Superseded, got {other:?}"),
        }
    }

    #[test]
    fn parses_completion_superseded_before_send() {
        let line = r#"INFO completion_metrics: completion superseded correlation_id=39 supersession_seq=2 latest_seq=5 stage=BeforeSend rust_total_ms=10.0 superseded_before_send_total=4 superseded_in_flight_total=0"#;
        let parsed = parse_line(line).expect("should parse");
        match parsed {
            CompletionMetricsRecord::Superseded { stage, .. } => {
                assert_eq!(stage, Some(SupersessionStage::BeforeSend));
            }
            other => panic!("expected Superseded, got {other:?}"),
        }
    }

    #[test]
    fn parses_ansi_colourised_completion_timing() {
        // Reproduces the actual `tracing-subscriber` default formatter
        // output that the LSP server emits to stderr in production.
        let line = "\x1b[2m2026-05-06T13:37:40.703048Z\x1b[0m \x1b[32m INFO\x1b[0m \x1b[2mcompletion_metrics\x1b[0m\x1b[2m:\x1b[0m completion timing \x1b[3mcorrelation_id\x1b[0m\x1b[2m=\x1b[0mcompletion-2 \x1b[3muri\x1b[0m\x1b[2m=\x1b[0mfile:///x.kt \x1b[3mbridge_response_wait_ms\x1b[0m\x1b[2m=\x1b[0m1.815708 \x1b[3mrust_total_ms\x1b[0m\x1b[2m=\x1b[0m1.832 \x1b[3msupersession_seq\x1b[0m\x1b[2m=\x1b[0m2 \x1b[3msuperseded_before_send_total\x1b[0m\x1b[2m=\x1b[0m0 \x1b[3msuperseded_in_flight_total\x1b[0m\x1b[2m=\x1b[0m0 \x1b[3msidecar\x1b[0m\x1b[2m=\x1b[0mnull";
        let parsed = parse_line(line).expect("ANSI line should parse");
        match parsed {
            CompletionMetricsRecord::Returned {
                correlation_id,
                bridge_response_wait_ms,
                rust_total_ms,
                supersession_seq,
                superseded_before_send_total,
                ..
            } => {
                assert_eq!(correlation_id, "completion-2");
                assert_eq!(bridge_response_wait_ms, Some(1.815708));
                assert_eq!(rust_total_ms, Some(1.832));
                assert_eq!(supersession_seq, Some(2));
                assert_eq!(superseded_before_send_total, Some(0));
            }
            other => panic!("expected Returned, got {other:?}"),
        }
    }

    #[test]
    fn ignores_unrelated_lines() {
        let lines = [
            "2026-05-06T12:00:00Z  INFO kotlin_analyzer_server: starting up",
            "completion_metrics: but wrong shape no correlation_id",
            "completely unrelated",
        ];
        for line in lines {
            assert!(parse_line(line).is_none(), "unexpected match: {line}");
        }
    }
}
