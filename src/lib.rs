use zed_extension_api::{self as zed, LanguageServerId, Result};

const COMPLETION_METRICS_ENV: &str = "KOTLIN_ANALYZER_COMPLETION_METRICS";

struct KotlinAnalyzerExtension {
    cached_binary_path: Option<String>,
}

impl KotlinAnalyzerExtension {
    fn set_status(
        language_server_id: &LanguageServerId,
        status: zed::LanguageServerInstallationStatus,
    ) {
        zed::set_language_server_installation_status(language_server_id, &status);
    }

    fn command_not_found_error() -> String {
        "kotlin-analyzer binary not found. Install it to a directory on your PATH \
        (e.g. ~/.local/bin/kotlin-analyzer) or build from source with: \
        cargo build && ln -sf $(pwd)/server/target/debug/kotlin-analyzer ~/.local/bin/"
            .into()
    }

    fn lsp_settings(worktree: &zed::Worktree) -> Option<zed::serde_json::Value> {
        zed::settings::LspSettings::for_worktree("kotlin-analyzer", worktree)
            .ok()
            .and_then(|s| s.settings)
    }

    fn completion_metrics_requested(worktree: &zed::Worktree) -> bool {
        Self::lsp_settings(worktree)
            .and_then(|settings| {
                settings
                    .get("completionMetrics")
                    .and_then(|value| value.as_bool())
            })
            .unwrap_or(false)
    }

    fn set_env_var(env: &mut Vec<(String, String)>, key: &str, value: &str) {
        if let Some((_, existing)) = env.iter_mut().find(|(k, _)| k == key) {
            *existing = value.into();
        } else {
            env.push((key.into(), value.into()));
        }
    }
}

impl zed::Extension for KotlinAnalyzerExtension {
    fn new() -> Self {
        eprintln!("kotlin-analyzer: extension initialized");
        Self {
            cached_binary_path: None,
        }
    }

    fn language_server_command(
        &mut self,
        language_server_id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        Self::set_status(
            language_server_id,
            zed::LanguageServerInstallationStatus::CheckingForUpdate,
        );

        let mut env = worktree.shell_env();
        let completion_metrics = Self::completion_metrics_requested(worktree);
        if completion_metrics {
            Self::set_env_var(&mut env, COMPLETION_METRICS_ENV, "1");
        }
        let log_file = "/tmp/kotlin-analyzer-server.log";
        let base_args = vec![
            "--log-level".into(),
            "info".into(),
            "--log-file".into(),
            log_file.into(),
        ];

        eprintln!(
            "kotlin-analyzer: PATH visible to extension: {}",
            env.iter()
                .find(|(k, _)| k == "PATH")
                .map(|(_, v)| v.as_str())
                .unwrap_or("<not set>")
        );
        eprintln!(
            "kotlin-analyzer: completion metrics {}",
            if completion_metrics {
                "enabled"
            } else {
                "disabled"
            }
        );

        // 1) Check if kotlin-analyzer is on PATH (dev override or system install).
        if let Some(path) = worktree.which("kotlin-analyzer") {
            eprintln!("kotlin-analyzer: using PATH-discovered binary: {path}");
            Self::set_status(
                language_server_id,
                zed::LanguageServerInstallationStatus::None,
            );
            return Ok(zed::Command {
                command: path,
                args: base_args,
                env,
            });
        }

        // 2) Check well-known local install path.
        //    The WASM sandbox cannot stat paths outside its mount, so we return the path
        //    and let Zed execute it.
        if let Some(home) = env
            .iter()
            .find(|(k, _)| k == "HOME")
            .map(|(_, v)| v.clone())
        {
            let local_bin = format!("{home}/.local/bin/kotlin-analyzer");
            eprintln!("kotlin-analyzer: falling back to well-known path: {local_bin}");
            Self::set_status(
                language_server_id,
                zed::LanguageServerInstallationStatus::None,
            );
            self.cached_binary_path = Some(local_bin.clone());
            return Ok(zed::Command {
                command: local_bin,
                args: base_args,
                env,
            });
        }

        // 3) Check if we already downloaded the binary.
        if let Some(path) = &self.cached_binary_path {
            Self::set_status(
                language_server_id,
                zed::LanguageServerInstallationStatus::None,
            );
            return Ok(zed::Command {
                command: path.clone(),
                args: base_args,
                env,
            });
        }

        let message = Self::command_not_found_error();
        Self::set_status(
            language_server_id,
            zed::LanguageServerInstallationStatus::Failed(message.clone()),
        );
        // 4) No local binary found — return a helpful error.
        Err(message)
    }

    fn language_server_workspace_configuration(
        &mut self,
        _server_id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<Option<zed::serde_json::Value>> {
        Ok(Self::lsp_settings(worktree))
    }
}

zed::register_extension!(KotlinAnalyzerExtension);
