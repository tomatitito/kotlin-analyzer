//! Named scenario presets for `completion-burst`.

use clap::ValueEnum;

use super::CompletionBurstArgs;

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
#[value(rename_all = "kebab-case")]
pub enum ScenarioName {
    TightBurst,
    MediumBurst,
    LooseBurst,
}

impl ScenarioName {
    pub fn as_str(self) -> &'static str {
        match self {
            ScenarioName::TightBurst => "tight-burst",
            ScenarioName::MediumBurst => "medium-burst",
            ScenarioName::LooseBurst => "loose-burst",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Scenario {
    pub name: String,
    pub burst_size: u32,
    pub burst_count: u32,
    pub inter_request_gap_ms: u64,
    pub inter_burst_gap_ms: u64,
    pub warmup_bursts: u32,
    /// Whether `--regression-guard` should treat this scenario as the
    /// tight-burst supersession invariant target.
    pub guard_tight_invariant: bool,
}

pub fn resolve(args: &CompletionBurstArgs) -> Scenario {
    match args.scenario {
        Some(ScenarioName::TightBurst) => Scenario {
            name: "tight-burst".to_string(),
            burst_size: 5,
            inter_request_gap_ms: 5,
            burst_count: args.burst_count,
            inter_burst_gap_ms: args.inter_burst_gap_ms,
            warmup_bursts: args.warmup_bursts,
            guard_tight_invariant: true,
        },
        Some(ScenarioName::MediumBurst) => Scenario {
            name: "medium-burst".to_string(),
            burst_size: 5,
            inter_request_gap_ms: 25,
            burst_count: args.burst_count,
            inter_burst_gap_ms: args.inter_burst_gap_ms,
            warmup_bursts: args.warmup_bursts,
            guard_tight_invariant: false,
        },
        Some(ScenarioName::LooseBurst) => Scenario {
            name: "loose-burst".to_string(),
            burst_size: 5,
            inter_request_gap_ms: 200,
            burst_count: args.burst_count,
            inter_burst_gap_ms: args.inter_burst_gap_ms,
            warmup_bursts: args.warmup_bursts,
            guard_tight_invariant: false,
        },
        None => Scenario {
            name: "ad-hoc".to_string(),
            burst_size: args.burst_size,
            inter_request_gap_ms: args.inter_request_gap_ms,
            burst_count: args.burst_count,
            inter_burst_gap_ms: args.inter_burst_gap_ms,
            warmup_bursts: args.warmup_bursts,
            // The tight-burst invariant only really makes sense when
            // requests are sent faster than the writer drains, which we
            // can't reliably assume for ad-hoc parameters. The user can
            // still flip on `--regression-guard`; we just won't apply the
            // invariant unless the chosen gap is small.
            guard_tight_invariant: args.inter_request_gap_ms <= 10,
        },
    }
}
