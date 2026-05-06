//! Aggregation, JSONL writing, terminal table rendering, and regression
//! guard logic for `completion-burst` outcomes.

use std::fmt::Write as _;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::Path;

use serde::Serialize;

use super::runner::{BurstRecord, RequestOutcome};
use super::scenarios::Scenario;

#[derive(Debug, Clone, Serialize)]
pub struct ScenarioAggregate {
    pub scenario: String,
    pub burst_size: u32,
    pub burst_count: u32,
    pub latest_count: usize,
    pub latest_min_ms: f64,
    pub latest_avg_ms: f64,
    pub latest_p50_ms: f64,
    pub latest_p95_ms: f64,
    pub latest_max_ms: f64,
    pub before_send_total_delta: u64,
    pub in_flight_total_delta: u64,
    pub sidecar_queue_wait_p50_ms: f64,
    pub sidecar_queue_wait_p95_ms: f64,
    pub sidecar_compute_p50_ms: f64,
    pub sidecar_compute_p95_ms: f64,
}

pub fn aggregate(scenario: &Scenario, bursts: &[BurstRecord]) -> ScenarioAggregate {
    let latest_latencies: Vec<f64> = bursts
        .iter()
        .filter_map(|b| b.latest.as_ref().map(|l| l.latency_ms))
        .collect();
    let queue_waits: Vec<f64> = bursts
        .iter()
        .filter_map(|b| b.latest.as_ref().and_then(|l| l.sidecar_queue_wait_ms))
        .collect();
    let computes: Vec<f64> = bursts
        .iter()
        .filter_map(|b| b.latest.as_ref().and_then(|l| l.sidecar_total_ms))
        .collect();

    let before_send: u64 = bursts.iter().map(|b| b.before_send_delta).sum();
    let in_flight: u64 = bursts.iter().map(|b| b.in_flight_delta).sum();

    ScenarioAggregate {
        scenario: scenario.name.clone(),
        burst_size: scenario.burst_size,
        burst_count: scenario.burst_count,
        latest_count: latest_latencies.len(),
        latest_min_ms: stat_min(&latest_latencies),
        latest_avg_ms: stat_avg(&latest_latencies),
        latest_p50_ms: stat_percentile(&latest_latencies, 0.50),
        latest_p95_ms: stat_percentile(&latest_latencies, 0.95),
        latest_max_ms: stat_max(&latest_latencies),
        before_send_total_delta: before_send,
        in_flight_total_delta: in_flight,
        sidecar_queue_wait_p50_ms: stat_percentile(&queue_waits, 0.50),
        sidecar_queue_wait_p95_ms: stat_percentile(&queue_waits, 0.95),
        sidecar_compute_p50_ms: stat_percentile(&computes, 0.50),
        sidecar_compute_p95_ms: stat_percentile(&computes, 0.95),
    }
}

pub fn render_table(aggs: &[ScenarioAggregate]) -> String {
    let mut out = String::new();
    let header = format!(
        "{:<14} {:>2} {:>6} {:>10} {:>10} {:>7} {:>9} {:>13} {:>14}",
        "Scenario",
        "N",
        "bursts",
        "latest_p50",
        "latest_p95",
        "bf_send",
        "in_flight",
        "sc_queue_p95",
        "sc_compute_p95",
    );
    out.push_str(&header);
    out.push('\n');
    for a in aggs {
        let _ = writeln!(
            &mut out,
            "{:<14} {:>2} {:>6} {:>9.1}ms {:>9.1}ms {:>7} {:>9} {:>11.1}ms {:>12.1}ms",
            a.scenario,
            a.burst_size,
            a.burst_count,
            a.latest_p50_ms,
            a.latest_p95_ms,
            a.before_send_total_delta,
            a.in_flight_total_delta,
            a.sidecar_queue_wait_p95_ms,
            a.sidecar_compute_p95_ms,
        );
    }
    out
}

pub fn render_aggregate(a: &ScenarioAggregate) -> String {
    format!(
        "Scenario {} — bursts={} burst_size={} latest_count={}\n  \
         latest latency  min={:.1}ms  avg={:.1}ms  p50={:.1}ms  p95={:.1}ms  max={:.1}ms\n  \
         supersession  BeforeSend={}  InFlight={}\n  \
         sidecar_queue_wait  p50={:.1}ms  p95={:.1}ms\n  \
         sidecar_compute     p50={:.1}ms  p95={:.1}ms",
        a.scenario,
        a.burst_count,
        a.burst_size,
        a.latest_count,
        a.latest_min_ms,
        a.latest_avg_ms,
        a.latest_p50_ms,
        a.latest_p95_ms,
        a.latest_max_ms,
        a.before_send_total_delta,
        a.in_flight_total_delta,
        a.sidecar_queue_wait_p50_ms,
        a.sidecar_queue_wait_p95_ms,
        a.sidecar_compute_p50_ms,
        a.sidecar_compute_p95_ms,
    )
}

pub fn write_jsonl(
    path: &Path,
    _scenario: &Scenario,
    bursts: &[BurstRecord],
) -> anyhow::Result<()> {
    let file = File::create(path)
        .map_err(|e| anyhow::anyhow!("failed to create {}: {e}", path.display()))?;
    let mut writer = BufWriter::new(file);
    for burst in bursts {
        let line = serde_json::to_string(burst)
            .map_err(|e| anyhow::anyhow!("serialize burst {}: {e}", burst.burst_index))?;
        writeln!(writer, "{line}").map_err(|e| anyhow::anyhow!("write burst: {e}"))?;
    }
    writer
        .flush()
        .map_err(|e| anyhow::anyhow!("flush jsonl: {e}"))?;
    Ok(())
}

#[derive(Debug, Clone)]
pub struct GuardViolation {
    pub burst_index: u32,
    pub expected_superseded: u64,
    pub before_send: u64,
    pub in_flight: u64,
}

/// Tight-burst supersession invariant: each burst should result in
/// `burst_size - 1` supersessions (the final request is the "latest" and
/// reaches the sidecar; every earlier one is dropped or discarded).
///
/// Returns the bursts that violate the invariant.
pub fn regression_guard_violations(
    scenario: &Scenario,
    bursts: &[BurstRecord],
) -> Vec<GuardViolation> {
    if !scenario.guard_tight_invariant {
        return Vec::new();
    }
    let expected = (scenario.burst_size as u64).saturating_sub(1);
    let mut violations = Vec::new();
    for burst in bursts {
        let observed = burst.before_send_delta + burst.in_flight_delta;

        // Cross-check by counting per-request outcomes that look like
        // supersessions; this catches missing-stderr cases the delta-only
        // path would miss.
        let observed_outcomes = burst
            .requests
            .iter()
            .filter(|r| {
                matches!(
                    r.outcome,
                    RequestOutcome::SupersededBeforeSend
                        | RequestOutcome::SupersededInFlight
                        | RequestOutcome::SupersededUnknown
                )
            })
            .count() as u64;

        let satisfied = observed == expected || observed_outcomes == expected;
        if !satisfied {
            violations.push(GuardViolation {
                burst_index: burst.burst_index,
                expected_superseded: expected,
                before_send: burst.before_send_delta,
                in_flight: burst.in_flight_delta,
            });
        }
    }
    violations
}

fn stat_min(xs: &[f64]) -> f64 {
    xs.iter()
        .cloned()
        .fold(f64::INFINITY, f64::min)
        .min_or_zero()
}

fn stat_max(xs: &[f64]) -> f64 {
    xs.iter()
        .cloned()
        .fold(f64::NEG_INFINITY, f64::max)
        .max_or_zero()
}

fn stat_avg(xs: &[f64]) -> f64 {
    if xs.is_empty() {
        return 0.0;
    }
    xs.iter().copied().sum::<f64>() / xs.len() as f64
}

fn stat_percentile(xs: &[f64], q: f64) -> f64 {
    if xs.is_empty() {
        return 0.0;
    }
    let mut sorted: Vec<f64> = xs.to_vec();
    sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let idx = ((sorted.len() as f64 - 1.0) * q).round() as usize;
    sorted[idx.min(sorted.len() - 1)]
}

trait FloatGuard {
    fn min_or_zero(self) -> f64;
    fn max_or_zero(self) -> f64;
}

impl FloatGuard for f64 {
    fn min_or_zero(self) -> f64 {
        if self.is_finite() {
            self
        } else {
            0.0
        }
    }
    fn max_or_zero(self) -> f64 {
        if self.is_finite() {
            self
        } else {
            0.0
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::completion_burst::runner::{
        BurstRecord, LatestOnlyMetrics, PerRequestRecord, RequestOutcome,
    };
    use crate::completion_burst::scenarios::Scenario;

    fn tight_scenario(burst_size: u32) -> Scenario {
        Scenario {
            name: "tight-burst".to_string(),
            burst_size,
            burst_count: 1,
            inter_request_gap_ms: 5,
            inter_burst_gap_ms: 0,
            warmup_bursts: 0,
            guard_tight_invariant: true,
        }
    }

    fn make_request(idx: u32, outcome: RequestOutcome) -> PerRequestRecord {
        PerRequestRecord {
            correlation_id: Some(format!("c{idx}")),
            my_seq: Some(idx as u64),
            burst_request_index: idx,
            lsp_id: idx as i64,
            sent_at_ms: idx as f64,
            recv_at_ms: idx as f64 + 5.0,
            latency_ms: 5.0,
            outcome,
            item_count: 0,
        }
    }

    fn make_burst(burst_index: u32, before_send: u64, in_flight: u64) -> BurstRecord {
        BurstRecord {
            scenario: "tight-burst".into(),
            burst_index,
            requests: vec![
                make_request(0, RequestOutcome::SupersededBeforeSend),
                make_request(1, RequestOutcome::SupersededBeforeSend),
                make_request(2, RequestOutcome::SupersededInFlight),
                make_request(3, RequestOutcome::SupersededInFlight),
                make_request(4, RequestOutcome::Returned),
            ],
            latest: Some(LatestOnlyMetrics {
                latency_ms: 100.0,
                bridge_response_wait_ms: Some(95.0),
                bridge_total_ms: Some(100.0),
                rust_total_ms: Some(100.0),
                sidecar_queue_wait_ms: Some(80.0),
                sidecar_total_ms: Some(50.0),
            }),
            before_send_delta: before_send,
            in_flight_delta: in_flight,
            burst_started_at_ms: 0.0,
            burst_completed_at_ms: 100.0,
        }
    }

    #[test]
    fn regression_guard_passes_on_tight_invariant() {
        let scenario = tight_scenario(5);
        // 0 + 4 = 4 == burst_size - 1
        let bursts = vec![make_burst(0, 0, 4), make_burst(1, 2, 2)];
        let violations = regression_guard_violations(&scenario, &bursts);
        assert!(
            violations.is_empty(),
            "expected no violations, got {violations:?}"
        );
    }

    #[test]
    fn regression_guard_flags_invariant_break() {
        let scenario = tight_scenario(5);
        // No supersession at all — invariant should fail.
        let mut bad_burst = make_burst(0, 0, 0);
        for r in bad_burst.requests.iter_mut() {
            r.outcome = RequestOutcome::Returned;
        }
        let bursts = vec![bad_burst];
        let violations = regression_guard_violations(&scenario, &bursts);
        assert_eq!(violations.len(), 1);
        assert_eq!(violations[0].expected_superseded, 4);
        assert_eq!(violations[0].before_send + violations[0].in_flight, 0);
    }

    #[test]
    fn regression_guard_off_for_loose_scenario() {
        let mut scenario = tight_scenario(5);
        scenario.guard_tight_invariant = false;
        let bursts = vec![make_burst(0, 0, 0)];
        assert!(regression_guard_violations(&scenario, &bursts).is_empty());
    }
}
