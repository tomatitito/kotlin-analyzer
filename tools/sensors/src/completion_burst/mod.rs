//! `completion-burst` sensor: drives the real `kotlin-analyzer` LSP binary
//! end-to-end with configurable typing-burst loads and reports the
//! user-visible latency metrics that matter for completion under burst
//! pressure (see ka-g342, ka-rr3u).

pub mod metrics;
pub mod report;
pub mod runner;
pub mod scenarios;

use std::path::PathBuf;

use clap::Args;

use crate::lsp_client::{discover_lsp_binary, LspTestClient};

#[derive(Debug, Clone, Args)]
pub struct CompletionBurstArgs {
    /// Project root passed to LSP `initialize.rootUri`.
    #[arg(long)]
    pub project_root: PathBuf,

    /// Kotlin file to drive completion against (absolute path).
    #[arg(long)]
    pub target_file: PathBuf,

    /// Zero-based line number in `--target-file` where completion is requested.
    #[arg(long)]
    pub line: u32,

    /// Zero-based character offset on `--line`.
    #[arg(long)]
    pub character: u32,

    /// Number of completion requests per burst.
    #[arg(long, default_value_t = 5)]
    pub burst_size: u32,

    /// Number of measured bursts.
    #[arg(long, default_value_t = 10)]
    pub burst_count: u32,

    /// Sleep between requests within a burst.
    #[arg(long, default_value_t = 5)]
    pub inter_request_gap_ms: u64,

    /// Sleep between bursts.
    #[arg(long, default_value_t = 2000)]
    pub inter_burst_gap_ms: u64,

    /// Number of warmup bursts to run before measurement (excluded from
    /// aggregates). A separate single-request readiness warmup always runs
    /// regardless of this value.
    #[arg(long, default_value_t = 1)]
    pub warmup_bursts: u32,

    /// Optional named scenario preset (mutually exclusive with the per-burst
    /// flags above; if set, scenario values override the defaults).
    #[arg(long, conflicts_with_all = ["burst_size", "inter_request_gap_ms"])]
    pub scenario: Option<scenarios::ScenarioName>,

    /// Path to write per-burst raw records as JSONL.
    #[arg(long)]
    pub jsonl_output: Option<PathBuf>,

    /// Enable the tight-burst supersession invariant assertion. Exits
    /// non-zero on violation.
    #[arg(long, default_value_t = false)]
    pub regression_guard: bool,

    /// Override the kotlin-analyzer LSP binary path. Falls back to `KA_BIN`
    /// then `<repo>/server/target/release/kotlin-analyzer`, building the
    /// server crate if needed.
    #[arg(long)]
    pub lsp_binary: Option<PathBuf>,

    /// Maximum seconds to wait for the LSP server to come up after spawn.
    #[arg(long, default_value_t = 30)]
    pub max_startup_secs: u64,
}

pub fn run(args: CompletionBurstArgs) -> anyhow::Result<()> {
    let scenario = scenarios::resolve(&args);

    let repo_root = repo_root_from_cwd();
    let binary = discover_lsp_binary(args.lsp_binary.as_deref(), &repo_root)
        .map_err(|e| anyhow::anyhow!("LSP binary discovery failed: {e}"))?;

    eprintln!("[completion-burst] using LSP binary {}", binary.display());
    eprintln!(
        "[completion-burst] scenario={} burst_size={} burst_count={} \
         inter_request_gap_ms={} inter_burst_gap_ms={} warmup_bursts={}",
        scenario.name,
        scenario.burst_size,
        scenario.burst_count,
        scenario.inter_request_gap_ms,
        scenario.inter_burst_gap_ms,
        scenario.warmup_bursts,
    );

    let mut client = LspTestClient::spawn_binary(&binary)
        .map_err(|e| anyhow::anyhow!("failed to spawn LSP binary: {e}"))?;

    let outcome = runner::run_scenario(
        &mut client,
        &scenario,
        &args.project_root,
        &args.target_file,
        args.line,
        args.character,
        std::time::Duration::from_secs(args.max_startup_secs),
    )?;

    if let Some(path) = args.jsonl_output.as_ref() {
        report::write_jsonl(path, &scenario, &outcome.measured)?;
        eprintln!(
            "[completion-burst] wrote {} per-burst records to {}",
            outcome.measured.len(),
            path.display()
        );
    }

    let aggregate = report::aggregate(&scenario, &outcome.measured);
    println!("{}", report::render_table(std::slice::from_ref(&aggregate)));
    println!();
    println!("{}", report::render_aggregate(&aggregate));

    if args.regression_guard {
        let violations = report::regression_guard_violations(&scenario, &outcome.measured);
        if !violations.is_empty() {
            eprintln!("[completion-burst] regression-guard FAILED:");
            for v in &violations {
                eprintln!("  burst={} expected_superseded={} got_before_send={} got_in_flight={} (sum={})",
                    v.burst_index,
                    v.expected_superseded,
                    v.before_send,
                    v.in_flight,
                    v.before_send + v.in_flight,
                );
            }
            return Err(anyhow::anyhow!(
                "regression guard violated by {} burst(s)",
                violations.len()
            ));
        }
        eprintln!("[completion-burst] regression-guard OK");
    }

    Ok(())
}

fn repo_root_from_cwd() -> PathBuf {
    let mut dir = std::env::current_dir().expect("cwd readable");
    loop {
        if dir.join(".git").exists() {
            return dir;
        }
        if !dir.pop() {
            return std::env::current_dir().expect("cwd readable");
        }
    }
}
