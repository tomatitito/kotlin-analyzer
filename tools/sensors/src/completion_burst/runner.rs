//! Drives the LSP server through the warmup → measured-bursts sequence and
//! collects per-burst raw records.

use std::path::Path;
use std::time::{Duration, Instant};

use serde::Serialize;
use serde_json::{json, Value};

use crate::lsp_client::LspTestClient;

use super::metrics::{self, CompletionMetricsRecord, SupersessionStage};
use super::scenarios::Scenario;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RequestOutcome {
    Returned,
    SupersededBeforeSend,
    SupersededInFlight,
    SupersededUnknown,
    Empty,
    Error,
    Timeout,
}

#[derive(Debug, Clone, Serialize)]
pub struct PerRequestRecord {
    pub correlation_id: Option<String>,
    pub my_seq: Option<u64>,
    pub burst_request_index: u32,
    pub lsp_id: i64,
    pub sent_at_ms: f64,
    pub recv_at_ms: f64,
    pub latency_ms: f64,
    pub outcome: RequestOutcome,
    pub item_count: usize,
}

#[derive(Debug, Clone, Serialize)]
pub struct LatestOnlyMetrics {
    pub latency_ms: f64,
    pub bridge_response_wait_ms: Option<f64>,
    pub bridge_total_ms: Option<f64>,
    pub rust_total_ms: Option<f64>,
    pub sidecar_queue_wait_ms: Option<f64>,
    pub sidecar_total_ms: Option<f64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct BurstRecord {
    pub scenario: String,
    pub burst_index: u32,
    pub requests: Vec<PerRequestRecord>,
    pub latest: Option<LatestOnlyMetrics>,
    pub before_send_delta: u64,
    pub in_flight_delta: u64,
    pub burst_started_at_ms: f64,
    pub burst_completed_at_ms: f64,
}

pub struct ScenarioOutcome {
    pub measured: Vec<BurstRecord>,
    #[allow(dead_code)]
    pub warmup: Vec<BurstRecord>,
}

pub fn run_scenario(
    client: &mut LspTestClient,
    scenario: &Scenario,
    project_root: &Path,
    target_file: &Path,
    line: u32,
    character: u32,
    startup_budget: Duration,
) -> anyhow::Result<ScenarioOutcome> {
    let start = Instant::now();
    let _ = startup_budget; // Reserved for future explicit-readiness probes.

    let project_root_uri = path_to_file_uri(project_root);
    let target_uri = path_to_file_uri(target_file);
    let target_text = std::fs::read_to_string(target_file).map_err(|e| {
        anyhow::anyhow!(
            "failed to read --target-file {}: {e}",
            target_file.display()
        )
    })?;

    client
        .initialize_with_root_and_client_and_options(
            &project_root_uri,
            None,
            Some(json!({ "completionMetrics": true })),
        )
        .map_err(|e| anyhow::anyhow!("initialize failed: {e}"))?;
    client
        .open_document(&target_uri, &target_text)
        .map_err(|e| anyhow::anyhow!("didOpen failed: {e}"))?;

    // Mandatory single-request readiness warmup. Any response (even
    // Ok(None)) proves the LSP→bridge→sidecar pipe is alive for this file.
    let readiness = readiness_warmup(client, &target_uri, line, character)?;
    eprintln!(
        "[completion-burst] readiness warmup latency={:.1}ms",
        readiness.latency_ms
    );
    let _ = client.drain_stderr();

    let mut warmup = Vec::with_capacity(scenario.warmup_bursts as usize);
    for i in 0..scenario.warmup_bursts {
        let record = run_one_burst(
            client,
            scenario,
            &target_uri,
            line,
            character,
            start,
            i,
            /* baseline */ (0, 0),
        )?;
        warmup.push(record);
        std::thread::sleep(Duration::from_millis(scenario.inter_burst_gap_ms));
    }
    let _ = client.drain_stderr();

    // Capture totals after warmup so the first measured burst's deltas are
    // relative to a stable baseline.
    // The warmup deltas are per-burst; what we actually need is the
    // cumulative total at this point. Read it from the most recent stderr
    // record. Falls back to (0, 0) when no metrics line was emitted yet.
    let baseline = recent_metrics_total(client)
        .map(|record| {
            (
                record.before_send_total().unwrap_or(0),
                record.in_flight_total().unwrap_or(0),
            )
        })
        .unwrap_or((0, 0));

    let mut measured = Vec::with_capacity(scenario.burst_count as usize);
    let mut running_baseline = baseline;
    for i in 0..scenario.burst_count {
        let record = run_one_burst(
            client,
            scenario,
            &target_uri,
            line,
            character,
            start,
            i,
            running_baseline,
        )?;
        // Update baseline for next burst.
        running_baseline = (
            running_baseline.0 + record.before_send_delta,
            running_baseline.1 + record.in_flight_delta,
        );
        measured.push(record);
        std::thread::sleep(Duration::from_millis(scenario.inter_burst_gap_ms));
    }

    Ok(ScenarioOutcome { measured, warmup })
}

#[derive(Debug, Clone)]
struct ReadinessWarmup {
    latency_ms: f64,
}

fn readiness_warmup(
    client: &mut LspTestClient,
    target_uri: &str,
    line: u32,
    character: u32,
) -> anyhow::Result<ReadinessWarmup> {
    let sent_at = Instant::now();
    let id = client
        .send_request_no_wait(
            "textDocument/completion",
            json!({
                "textDocument": { "uri": target_uri },
                "position": { "line": line, "character": character }
            }),
        )
        .map_err(|e| anyhow::anyhow!("warmup send failed: {e}"))?;
    let _ = client
        .await_response_for(id, Duration::from_secs(60))
        .map_err(|e| anyhow::anyhow!("warmup response timed out: {e}"))?;
    Ok(ReadinessWarmup {
        latency_ms: sent_at.elapsed().as_secs_f64() * 1000.0,
    })
}

#[allow(clippy::too_many_arguments)]
fn run_one_burst(
    client: &mut LspTestClient,
    scenario: &Scenario,
    target_uri: &str,
    line: u32,
    character: u32,
    sensor_started_at: Instant,
    burst_index: u32,
    baseline_totals: (u64, u64),
) -> anyhow::Result<BurstRecord> {
    let _ = client.drain_stderr(); // discard any pre-burst noise

    let burst_started_at = Instant::now();
    let mut sent: Vec<(i64, Instant, u32)> = Vec::with_capacity(scenario.burst_size as usize);

    for i in 0..scenario.burst_size {
        let send_at = Instant::now();
        let id = client
            .send_request_no_wait(
                "textDocument/completion",
                json!({
                    "textDocument": { "uri": target_uri },
                    "position": { "line": line, "character": character }
                }),
            )
            .map_err(|e| anyhow::anyhow!("burst send {i} failed: {e}"))?;
        sent.push((id, send_at, i));
        if i + 1 < scenario.burst_size {
            std::thread::sleep(Duration::from_millis(scenario.inter_request_gap_ms));
        }
    }

    let mut responses: Vec<(i64, Instant, Value)> = Vec::with_capacity(sent.len());
    for &(id, _, _) in &sent {
        match client.await_response_for(id, Duration::from_secs(60)) {
            Ok(msg) => responses.push((id, Instant::now(), msg)),
            Err(_) => responses.push((id, Instant::now(), Value::Null)),
        }
    }
    let burst_completed_at = Instant::now();

    // Drain stderr for a short window so log lines emitted just after the
    // last response are also captured.
    let stderr_lines = client.drain_stderr_for(Duration::from_millis(200));
    let metrics_records = metrics::parse_lines(&stderr_lines);

    // Build per-request records.
    let mut requests: Vec<PerRequestRecord> = Vec::with_capacity(sent.len());
    for ((id, sent_at, burst_request_index), (resp_id, recv_at, response)) in
        sent.iter().zip(responses.iter())
    {
        debug_assert_eq!(id, resp_id);
        let latency_ms = recv_at.duration_since(*sent_at).as_secs_f64() * 1000.0;

        let (outcome, item_count) = classify_response(response);

        requests.push(PerRequestRecord {
            correlation_id: None,
            my_seq: None,
            burst_request_index: *burst_request_index,
            lsp_id: *id,
            sent_at_ms: sent_at.duration_since(sensor_started_at).as_secs_f64() * 1000.0,
            recv_at_ms: recv_at.duration_since(sensor_started_at).as_secs_f64() * 1000.0,
            latency_ms,
            outcome,
            item_count,
        });
    }

    annotate_with_metrics(&mut requests, &metrics_records);

    let latest = build_latest_metrics(&requests, &metrics_records);

    let before_send_total = metrics_records
        .iter()
        .filter_map(|r| r.before_send_total())
        .next_back();
    let in_flight_total = metrics_records
        .iter()
        .filter_map(|r| r.in_flight_total())
        .next_back();

    let before_send_delta = before_send_total
        .map(|t| t.saturating_sub(baseline_totals.0))
        .unwrap_or(0);
    let in_flight_delta = in_flight_total
        .map(|t| t.saturating_sub(baseline_totals.1))
        .unwrap_or(0);

    Ok(BurstRecord {
        scenario: scenario.name.clone(),
        burst_index,
        requests,
        latest,
        before_send_delta,
        in_flight_delta,
        burst_started_at_ms: burst_started_at
            .duration_since(sensor_started_at)
            .as_secs_f64()
            * 1000.0,
        burst_completed_at_ms: burst_completed_at
            .duration_since(sensor_started_at)
            .as_secs_f64()
            * 1000.0,
    })
}

fn classify_response(response: &Value) -> (RequestOutcome, usize) {
    if response.is_null() {
        return (RequestOutcome::Timeout, 0);
    }
    if response.get("error").is_some() {
        return (RequestOutcome::Error, 0);
    }
    let result = match response.get("result") {
        Some(r) => r,
        None => return (RequestOutcome::Error, 0),
    };
    if result.is_null() {
        // Could be supersession or "no completions" — `annotate_with_metrics`
        // refines this if a matching stderr record arrives.
        return (RequestOutcome::SupersededUnknown, 0);
    }
    let items = result
        .as_array()
        .or_else(|| result.get("items").and_then(|v| v.as_array()));
    match items {
        Some(arr) if arr.is_empty() => (RequestOutcome::Empty, 0),
        Some(arr) => (RequestOutcome::Returned, arr.len()),
        None => (RequestOutcome::Returned, 0),
    }
}

/// Match per-request records to stderr records by send-order. The Rust LSP
/// generates correlation_ids from a monotonic counter and emits one
/// `completion_metrics` record per completion request; the order in which
/// records appear on stderr is the same as the order requests entered the
/// completion handler. Within a single burst from a single client this is
/// also the send-order, so a positional match works cleanly.
fn annotate_with_metrics(requests: &mut [PerRequestRecord], records: &[CompletionMetricsRecord]) {
    let mut record_iter = records.iter();
    for req in requests.iter_mut() {
        let record = match record_iter.next() {
            Some(r) => r,
            None => continue,
        };
        req.correlation_id = Some(record.correlation_id().to_string());
        req.my_seq = match record {
            CompletionMetricsRecord::Returned {
                supersession_seq, ..
            } => *supersession_seq,
            CompletionMetricsRecord::Superseded {
                supersession_seq, ..
            } => *supersession_seq,
        };
        if matches!(req.outcome, RequestOutcome::SupersededUnknown) {
            if let CompletionMetricsRecord::Superseded { stage, .. } = record {
                req.outcome = match stage {
                    Some(SupersessionStage::BeforeSend) => RequestOutcome::SupersededBeforeSend,
                    Some(SupersessionStage::InFlight) => RequestOutcome::SupersededInFlight,
                    None => RequestOutcome::SupersededUnknown,
                };
            }
        }
    }
}

fn build_latest_metrics(
    requests: &[PerRequestRecord],
    records: &[CompletionMetricsRecord],
) -> Option<LatestOnlyMetrics> {
    let returning_record = records.iter().rev().find_map(|r| match r {
        CompletionMetricsRecord::Returned {
            bridge_response_wait_ms,
            bridge_total_ms,
            rust_total_ms,
            sidecar_queue_wait_ms,
            sidecar_total_ms,
            ..
        } => Some(LatestOnlyMetrics {
            latency_ms: 0.0,
            bridge_response_wait_ms: *bridge_response_wait_ms,
            bridge_total_ms: *bridge_total_ms,
            rust_total_ms: *rust_total_ms,
            sidecar_queue_wait_ms: *sidecar_queue_wait_ms,
            sidecar_total_ms: *sidecar_total_ms,
        }),
        _ => None,
    });

    let latency_ms = requests
        .iter()
        .filter(|r| matches!(r.outcome, RequestOutcome::Returned | RequestOutcome::Empty))
        .map(|r| r.latency_ms)
        .next_back()
        .unwrap_or(0.0);

    returning_record.map(|mut m| {
        m.latency_ms = latency_ms;
        m
    })
}

fn recent_metrics_total(client: &mut LspTestClient) -> Option<CompletionMetricsRecord> {
    let lines = client.drain_stderr();
    metrics::parse_lines(&lines).into_iter().last()
}

fn path_to_file_uri(path: &Path) -> String {
    let abs = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir().expect("cwd readable").join(path)
    };
    format!("file://{}", abs.display())
}
