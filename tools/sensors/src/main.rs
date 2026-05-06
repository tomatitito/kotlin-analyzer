use clap::{Parser, Subcommand};

use kotlin_analyzer_sensors::completion_burst::{self, CompletionBurstArgs};

/// Top-level kotlin-analyzer sensors CLI. Today only `completion-burst` is
/// implemented; future sensors will live alongside it under the same
/// binary so the eventual `sensors metrics run <name>` UX is a thin wrapper
/// over the same subcommand surface.
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
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    match cli.command {
        Command::CompletionBurst(args) => completion_burst::run(args),
    }
}
