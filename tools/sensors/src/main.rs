use std::path::PathBuf;
use std::process::Command as ProcessCommand;

use anyhow::{anyhow, Context};
use clap::{Args, Parser, Subcommand};

use kotlin_analyzer_sensors::completion_burst::{self, CompletionBurstArgs};

/// Top-level kotlin-analyzer sensors CLI. Today the in-tree Rust sensor is
/// `completion-burst`; lighter probes live as Python scripts under
/// `.sensors/` and are dispatched via the `run` subcommand so the
/// user-facing vocabulary stays "sensor" regardless of implementation
/// language.
#[derive(Debug, Parser)]
#[command(name = "sensors", about = "kotlin-analyzer observability sensors")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Drive the LSP server with a typing-burst workload and report
    /// user-visible completion latency under supersession pressure.
    CompletionBurst(CompletionBurstArgs),
    /// Run a script-based sensor from `.sensors/<name>.py`. Remaining
    /// arguments after the sensor name are forwarded verbatim to the
    /// script. Use `--` to disambiguate flags meant for the script.
    ///
    /// Example: `sensors run log-analysis --verbose`
    Run(RunArgs),
}

#[derive(Debug, Args)]
struct RunArgs {
    /// Sensor script name. Resolved to `<repo-root>/.sensors/<name>.py`.
    name: String,

    /// Override the repo root used to resolve the `.sensors/` directory.
    /// Defaults to walking upwards from the current working directory
    /// looking for a directory that contains `.sensors/`.
    #[arg(long, value_name = "DIR")]
    repo_root: Option<PathBuf>,

    /// Extra arguments forwarded to the Python script.
    #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
    forwarded: Vec<String>,
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    match cli.command {
        Command::CompletionBurst(args) => completion_burst::run(args),
        Command::Run(args) => run_script_sensor(args),
    }
}

fn run_script_sensor(args: RunArgs) -> anyhow::Result<()> {
    let repo_root = match args.repo_root {
        Some(p) => p
            .canonicalize()
            .with_context(|| format!("--repo-root {} does not exist", p.display()))?,
        None => discover_repo_root()
            .context("could not locate `.sensors/` from cwd; pass --repo-root explicitly")?,
    };
    let script = repo_root.join(".sensors").join(format!("{}.py", args.name));
    if !script.is_file() {
        return Err(anyhow!(
            "sensor script not found: {} (expected at .sensors/{}.py)",
            script.display(),
            args.name,
        ));
    }

    // Use `python3` from PATH; the scripts target the system interpreter
    // and avoid third-party dependencies.
    let status = ProcessCommand::new("python3")
        .arg(&script)
        .args(&args.forwarded)
        .status()
        .with_context(|| format!("failed to spawn python3 for {}", script.display()))?;
    if !status.success() {
        std::process::exit(status.code().unwrap_or(1));
    }
    Ok(())
}

fn discover_repo_root() -> Option<PathBuf> {
    let mut cur = std::env::current_dir().ok()?;
    loop {
        if cur.join(".sensors").is_dir() {
            return Some(cur);
        }
        if !cur.pop() {
            return None;
        }
    }
}
