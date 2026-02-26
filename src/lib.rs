use std::fs;
use zed_extension_api::{self as zed, LanguageServerId, Result};

struct KotlinAnalyzerExtension {
    cached_binary_path: Option<String>,
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
        _language_server_id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        let env = worktree.shell_env();

        eprintln!(
            "kotlin-analyzer: PATH visible to extension: {}",
            env.iter()
                .find(|(k, _)| k == "PATH")
                .map(|(_, v)| v.as_str())
                .unwrap_or("<not set>")
        );

        let log_file = "/tmp/kotlin-analyzer-server.log";
        let base_args = vec![
            "--log-level".into(),
            "info".into(),
            "--log-file".into(),
            log_file.into(),
        ];

        // 1. Check if kotlin-analyzer is on PATH (dev override or system install)
        if let Some(path) = worktree.which("kotlin-analyzer") {
            eprintln!("kotlin-analyzer: found binary via which() at {}", path);
            return Ok(zed::Command {
                command: path,
                args: base_args,
                env,
            });
        }
        eprintln!("kotlin-analyzer: which() did not find binary");

        // 2. Check well-known local install path.
        //    The WASM sandbox cannot stat paths outside its mount, so we
        //    return the path unconditionally and let Zed (which runs the
        //    command outside the sandbox) handle a missing binary.
        let home = env
            .iter()
            .find(|(k, _)| k == "HOME")
            .map(|(_, v)| v.clone());
        if let Some(home) = &home {
            let local_bin = format!("{home}/.local/bin/kotlin-analyzer");
            eprintln!("kotlin-analyzer: using well-known path {}", local_bin);
            return Ok(zed::Command {
                command: local_bin,
                args: base_args,
                env,
            });
        }

        // 3. Check if we already downloaded the binary
        if let Some(path) = &self.cached_binary_path {
            if fs::metadata(path).is_ok() {
                return Ok(zed::Command {
                    command: path.clone(),
                    args: base_args,
                    env,
                });
            }
        }

        // 4. No local binary found — return a helpful error.
        //    GitHub releases are not yet published, so don't attempt a download.
        Err("kotlin-analyzer binary not found. \
             Install it to a directory on your PATH \
             (e.g. ~/.local/bin/kotlin-analyzer) \
             or build from source with: \
             cargo build && ln -sf $(pwd)/server/target/debug/kotlin-analyzer ~/.local/bin/"
            .into())
    }

    fn language_server_workspace_configuration(
        &mut self,
        _server_id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<Option<zed::serde_json::Value>> {
        let settings = zed::settings::LspSettings::for_worktree("kotlin-analyzer", worktree)
            .ok()
            .and_then(|s| s.settings);

        Ok(settings)
    }
}

zed::register_extension!(KotlinAnalyzerExtension);
