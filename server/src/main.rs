mod bridge;
mod config;
mod error;
mod jsonrpc;
mod project;
mod runtime;
mod server;
mod state;

use std::path::PathBuf;

use time::format_description::FormatItem;
use time::macros::format_description;
use time::OffsetDateTime;
use tower_lsp::{LspService, Server};
use tracing_appender::non_blocking::WorkerGuard;
use tracing_subscriber::filter::filter_fn;
use tracing_subscriber::fmt;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::{EnvFilter, Layer, Registry};

const COMPLETION_METRICS_ENV: &str = "KOTLIN_ANALYZER_COMPLETION_METRICS";
const REPO_MARKER: &str = "extension.toml";
const METRICS_TARGET: &str = "completion_metrics";
const TIMESTAMP_FORMAT: &[FormatItem<'static>] =
    format_description!("[year]-[month]-[day]T[hour]-[minute]-[second]Z");

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

    let general_filter =
        EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(&log_level));

    let general_layer: Box<dyn Layer<Registry> + Send + Sync> = if let Some(ref log_path) =
        log_file
    {
        let file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log_path)?;
        fmt::layer()
            .with_writer(file)
            .with_ansi(false)
            .with_filter(general_filter)
            .boxed()
    } else {
        fmt::layer()
            .with_writer(std::io::stderr)
            .with_filter(general_filter)
            .boxed()
    };

    let metrics_setup = if metrics_enabled_via_env() {
        match build_metrics_layer() {
            Ok(setup) => setup,
            Err(err) => MetricsSetup {
                layer: None,
                guard: None,
                status: Some(format!("metrics file disabled: {}", err)),
            },
        }
    } else {
        MetricsSetup::disabled()
    };

    let MetricsSetup {
        layer: metrics_layer,
        guard: _metrics_guard,
        status: metrics_status,
    } = metrics_setup;

    let mut layers: Vec<Box<dyn Layer<Registry> + Send + Sync>> = vec![general_layer];
    if let Some(layer) = metrics_layer {
        layers.push(layer);
    }
    tracing_subscriber::registry().with(layers).init();

    if let Some(msg) = metrics_status {
        tracing::info!("{}", msg);
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

    tracing::info!(
        "kotlin-analyzer: server loop exited (pid={})",
        std::process::id()
    );

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

fn metrics_enabled_via_env() -> bool {
    match std::env::var(COMPLETION_METRICS_ENV) {
        Ok(value) => {
            let trimmed = value.trim();
            !trimmed.is_empty() && trimmed != "0" && !trimmed.eq_ignore_ascii_case("false")
        }
        Err(_) => false,
    }
}

struct MetricsSetup {
    layer: Option<Box<dyn Layer<Registry> + Send + Sync>>,
    guard: Option<WorkerGuard>,
    status: Option<String>,
}

impl MetricsSetup {
    fn disabled() -> Self {
        Self {
            layer: None,
            guard: None,
            status: None,
        }
    }
}

fn build_metrics_layer() -> Result<MetricsSetup, String> {
    let repo_root = match find_repo_root() {
        Some(path) => path,
        None => return Err("no repo root found".to_string()),
    };

    let logs_dir = repo_root.join("logs");
    if let Err(err) = std::fs::create_dir_all(&logs_dir) {
        return Err(format!(
            "could not create {}: {}",
            logs_dir.display(),
            err
        ));
    }

    let timestamp = OffsetDateTime::now_utc()
        .format(TIMESTAMP_FORMAT)
        .map_err(|err| format!("timestamp formatting failed: {}", err))?;
    let log_path = logs_dir.join(format!("kotlin-analyzer-{}.log", timestamp));

    let file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
        .map_err(|err| format!("could not open {}: {}", log_path.display(), err))?;

    let (writer, guard) = tracing_appender::non_blocking(file);

    let layer = fmt::layer()
        .with_writer(writer)
        .with_ansi(false)
        .with_target(false)
        .with_filter(filter_fn(|meta| meta.target() == METRICS_TARGET))
        .boxed();

    Ok(MetricsSetup {
        layer: Some(layer),
        guard: Some(guard),
        status: Some(format!("metrics file: {}", log_path.display())),
    })
}

fn find_repo_root() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let canonical = exe.canonicalize().ok()?;
    canonical
        .ancestors()
        .find(|dir| dir.join(REPO_MARKER).is_file())
        .map(|dir| dir.to_path_buf())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn with_env<R>(key: &str, value: Option<&str>, body: impl FnOnce() -> R) -> R {
        let prev = std::env::var(key).ok();
        match value {
            Some(v) => std::env::set_var(key, v),
            None => std::env::remove_var(key),
        }
        let result = body();
        match prev {
            Some(v) => std::env::set_var(key, v),
            None => std::env::remove_var(key),
        }
        result
    }

    #[test]
    fn metrics_disabled_when_env_unset() {
        with_env(COMPLETION_METRICS_ENV, None, || {
            assert!(!metrics_enabled_via_env());
        });
    }

    #[test]
    fn metrics_disabled_for_zero_or_false() {
        for value in ["0", "false", "FALSE", "  0  ", ""] {
            with_env(COMPLETION_METRICS_ENV, Some(value), || {
                assert!(
                    !metrics_enabled_via_env(),
                    "expected disabled for value {value:?}"
                );
            });
        }
    }

    #[test]
    fn metrics_enabled_for_truthy_values() {
        for value in ["1", "true", "yes", "on"] {
            with_env(COMPLETION_METRICS_ENV, Some(value), || {
                assert!(
                    metrics_enabled_via_env(),
                    "expected enabled for value {value:?}"
                );
            });
        }
    }

    #[test]
    fn timestamp_format_is_filesystem_safe() {
        let stamp = OffsetDateTime::now_utc().format(TIMESTAMP_FORMAT).unwrap();
        assert!(!stamp.contains(':'), "timestamp must not contain colons");
        assert!(stamp.ends_with('Z'), "timestamp must be UTC-marked");
        assert_eq!(stamp.len(), "YYYY-MM-DDTHH-MM-SSZ".len());
    }
}
