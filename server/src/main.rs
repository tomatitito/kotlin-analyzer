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
const METRICS_DIR_ENV: &str = "KOTLIN_ANALYZER_METRICS_DIR";
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
        build_metrics_layer()
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

    fn disabled_with_status(msg: String) -> Self {
        Self {
            layer: None,
            guard: None,
            status: Some(msg),
        }
    }
}

fn metrics_dir_from_env() -> Option<PathBuf> {
    match std::env::var(METRICS_DIR_ENV) {
        Ok(value) => {
            let trimmed = value.trim();
            if trimmed.is_empty() {
                None
            } else {
                Some(PathBuf::from(trimmed))
            }
        }
        Err(_) => None,
    }
}

fn build_metrics_layer() -> MetricsSetup {
    let logs_dir = match metrics_dir_from_env() {
        Some(dir) => dir,
        None => return MetricsSetup::disabled(),
    };

    if let Err(err) = std::fs::create_dir_all(&logs_dir) {
        return MetricsSetup::disabled_with_status(format!(
            "metrics file disabled: cannot create {}: {}",
            logs_dir.display(),
            err
        ));
    }

    let timestamp = match OffsetDateTime::now_utc().format(TIMESTAMP_FORMAT) {
        Ok(stamp) => stamp,
        Err(err) => {
            return MetricsSetup::disabled_with_status(format!(
                "metrics file disabled: timestamp formatting failed: {}",
                err
            ));
        }
    };
    let log_path = logs_dir.join(format!("kotlin-analyzer-{}.log", timestamp));

    let file = match std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
    {
        Ok(file) => file,
        Err(err) => {
            return MetricsSetup::disabled_with_status(format!(
                "metrics file disabled: cannot create {}: {}",
                log_path.display(),
                err
            ));
        }
    };

    let (writer, guard) = tracing_appender::non_blocking(file);

    let layer = fmt::layer()
        .with_writer(writer)
        .with_ansi(false)
        .with_target(false)
        .with_filter(filter_fn(|meta| meta.target() == METRICS_TARGET))
        .boxed();

    MetricsSetup {
        layer: Some(layer),
        guard: Some(guard),
        status: Some(format!("metrics file: {}", log_path.display())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    // Tests in this module mutate process-wide environment variables, which
    // interferes with parallel execution. Serialise them with a mutex.
    static ENV_LOCK: Mutex<()> = Mutex::new(());

    fn with_env<R>(key: &str, value: Option<&str>, body: impl FnOnce() -> R) -> R {
        let _guard = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
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

    #[test]
    fn metrics_dir_unset_returns_none() {
        with_env(METRICS_DIR_ENV, None, || {
            assert!(metrics_dir_from_env().is_none());
        });
    }

    #[test]
    fn metrics_dir_empty_or_whitespace_returns_none() {
        for value in ["", "   ", "\t\n"] {
            with_env(METRICS_DIR_ENV, Some(value), || {
                assert!(
                    metrics_dir_from_env().is_none(),
                    "expected None for value {value:?}"
                );
            });
        }
    }

    #[test]
    fn metrics_dir_set_returns_trimmed_path() {
        with_env(METRICS_DIR_ENV, Some("  /tmp/foo  "), || {
            assert_eq!(metrics_dir_from_env(), Some(PathBuf::from("/tmp/foo")));
        });
    }

    #[test]
    fn build_metrics_layer_disabled_when_env_unset() {
        with_env(METRICS_DIR_ENV, None, || {
            let setup = build_metrics_layer();
            assert!(setup.layer.is_none());
            assert!(setup.guard.is_none());
            assert!(setup.status.is_none());
        });
    }

    #[test]
    fn build_metrics_layer_creates_file_in_existing_dir() {
        let temp = tempfile::tempdir().expect("create tempdir");
        let dir_str = temp.path().to_string_lossy().into_owned();

        let setup = with_env(METRICS_DIR_ENV, Some(&dir_str), build_metrics_layer);

        assert!(setup.layer.is_some(), "expected layer to be built");
        assert!(setup.guard.is_some(), "expected non-blocking guard");
        let status = setup.status.expect("expected status message");
        assert!(
            status.starts_with("metrics file: "),
            "unexpected status: {status}",
        );

        // Drop guard to flush any pending writes (none here, but be tidy).
        drop(setup.guard);

        let entries: Vec<_> = std::fs::read_dir(temp.path())
            .expect("read tempdir")
            .filter_map(|e| e.ok())
            .collect();
        assert_eq!(entries.len(), 1, "expected exactly one file in dir");
        let name = entries[0].file_name().into_string().unwrap();
        assert!(
            name.starts_with("kotlin-analyzer-") && name.ends_with(".log"),
            "unexpected filename: {name}",
        );
    }

    #[test]
    fn build_metrics_layer_creates_missing_intermediate_dirs() {
        let temp = tempfile::tempdir().expect("create tempdir");
        let nested = temp.path().join("a").join("b").join("c");
        let dir_str = nested.to_string_lossy().into_owned();

        let setup = with_env(METRICS_DIR_ENV, Some(&dir_str), build_metrics_layer);

        assert!(setup.layer.is_some(), "expected layer to be built");
        assert!(nested.is_dir(), "expected nested dirs to be created");

        drop(setup.guard);
        let entries: Vec<_> = std::fs::read_dir(&nested)
            .expect("read nested dir")
            .filter_map(|e| e.ok())
            .collect();
        assert_eq!(entries.len(), 1);
    }

    #[test]
    fn build_metrics_layer_logs_info_on_unwritable_path() {
        // Use a path whose parent is a regular file — create_dir_all cannot
        // create a directory inside a file, which mimics permission-denied
        // failure modes portably across CI environments.
        let temp = tempfile::tempdir().expect("create tempdir");
        let file_path = temp.path().join("not-a-dir");
        std::fs::write(&file_path, b"x").expect("create blocking file");
        let bad_dir = file_path.join("logs");
        let dir_str = bad_dir.to_string_lossy().into_owned();

        let setup = with_env(METRICS_DIR_ENV, Some(&dir_str), build_metrics_layer);

        assert!(
            setup.layer.is_none(),
            "expected no layer when dir creation fails"
        );
        assert!(setup.guard.is_none());
        let status = setup.status.expect("expected status message");
        assert!(
            status.starts_with("metrics file disabled: cannot create "),
            "unexpected status: {status}",
        );
    }
}
