mod bridge;
mod config;
mod error;
mod jsonrpc;
mod project;
mod server;
mod state;

use tower_lsp::{LspService, Server};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Install panic hook that writes to a log file before aborting
    std::panic::set_hook(Box::new(|info| {
        let msg = format!("kotlin-analyzer PANIC: {}\n", info);
        let _ = std::fs::write("/tmp/kotlin-analyzer-panic.log", &msg);
        eprintln!("{}", msg);
    }));

    // Parse CLI arguments
    let args: Vec<String> = std::env::args().collect();
    let log_level = parse_log_level(&args);
    let log_file = parse_log_file(&args);

    // Initialize tracing (logs to stderr, stdout is reserved for LSP transport)
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(&log_level));

    if let Some(ref log_path) = log_file {
        let file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log_path)?;

        tracing_subscriber::fmt()
            .with_env_filter(filter)
            .with_writer(file)
            .with_ansi(false)
            .init();
    } else {
        tracing_subscriber::fmt()
            .with_env_filter(filter)
            .with_writer(std::io::stderr)
            .init();
    }

    tracing::info!(
        "kotlin-analyzer {} starting (pid={}, args={:?})",
        env!("CARGO_PKG_VERSION"),
        std::process::id(),
        args
    );

    let stdin = tokio::io::stdin();
    let stdout = tokio::io::stdout();

    let (service, socket) = LspService::new(server::KotlinLanguageServer::new);

    Server::new(stdin, stdout, socket).serve(service).await;

    tracing::info!("kotlin-analyzer: server loop exited (pid={})", std::process::id());

    Ok(())
}

fn parse_log_level(args: &[String]) -> String {
    for (i, arg) in args.iter().enumerate() {
        if arg == "--log-level" {
            if let Some(level) = args.get(i + 1) {
                return level.clone();
            }
        }
        if let Some(level) = arg.strip_prefix("--log-level=") {
            return level.to_string();
        }
    }
    "info".to_string()
}

fn parse_log_file(args: &[String]) -> Option<String> {
    for (i, arg) in args.iter().enumerate() {
        if arg == "--log-file" {
            return args.get(i + 1).cloned();
        }
        if let Some(path) = arg.strip_prefix("--log-file=") {
            return Some(path.to_string());
        }
    }
    None
}
