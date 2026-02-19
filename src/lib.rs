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
        language_server_id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        let env = worktree.shell_env();

        // 1. Check if kotlin-analyzer is on PATH (dev override or system install)
        if let Some(path) = worktree.which("kotlin-analyzer") {
            eprintln!("kotlin-analyzer: using binary from PATH at {}", path);
            return Ok(zed::Command {
                command: path,
                args: vec!["--log-level".into(), "info".into()],
                env,
            });
        }

        // 2. Check if we already downloaded the binary
        if let Some(path) = &self.cached_binary_path {
            if fs::metadata(path).is_ok() {
                return Ok(zed::Command {
                    command: path.clone(),
                    args: vec!["--log-level".into(), "info".into()],
                    env,
                });
            }
        }

        // 3. Download from GitHub releases
        let (platform, arch) = zed::current_platform();
        let target = match (platform, arch) {
            (zed::Os::Mac, zed::Architecture::Aarch64) => "aarch64-apple-darwin",
            (zed::Os::Mac, zed::Architecture::X8664) => "x86_64-apple-darwin",
            (zed::Os::Linux, zed::Architecture::X8664) => "x86_64-unknown-linux-gnu",
            (zed::Os::Linux, zed::Architecture::Aarch64) => "aarch64-unknown-linux-gnu",
            _ => return Err("Unsupported platform".into()),
        };

        let version = "0.1.0";
        let asset_name = format!("kotlin-analyzer-{version}-{target}.tar.gz");
        let release = zed::latest_github_release(
            "jenskouros/kotlin-analyzer",
            zed::GithubReleaseOptions {
                require_assets: true,
                pre_release: false,
            },
        )?;

        let asset = release
            .assets
            .iter()
            .find(|a| a.name == asset_name)
            .ok_or_else(|| format!("No asset found for {target}"))?;

        let version_dir = format!("kotlin-analyzer-{}", release.version);
        let binary_path = format!("{version_dir}/kotlin-analyzer");

        if fs::metadata(&binary_path).is_err() {
            zed::set_language_server_installation_status(
                language_server_id,
                &zed::LanguageServerInstallationStatus::Downloading,
            );

            zed::download_file(
                &asset.download_url,
                &version_dir,
                zed::DownloadedFileType::GzipTar,
            )
            .map_err(|e| format!("Failed to download kotlin-analyzer: {e}"))?;

            zed::set_language_server_installation_status(
                language_server_id,
                &zed::LanguageServerInstallationStatus::None,
            );
        }

        self.cached_binary_path = Some(binary_path.clone());

        Ok(zed::Command {
            command: binary_path,
            args: vec!["--log-level".into(), "info".into()],
            env,
        })
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
