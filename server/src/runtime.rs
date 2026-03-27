use std::cmp::Ordering;
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::Deserialize;

const CACHE_DIR_ENV: &str = "KOTLIN_ANALYZER_RUNTIME_CACHE_DIR";
const PROVISION_DIRS_ENV: &str = "KOTLIN_ANALYZER_RUNTIME_SOURCE_DIRS";

/// A concrete sidecar runtime that can be launched by the bridge.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SidecarRuntime {
    pub requested_kotlin_version: Option<String>,
    pub kotlin_version: Option<String>,
    pub classpath: Vec<PathBuf>,
    pub main_class: Option<String>,
    pub selection_reason: RuntimeSelectionReason,
}

/// Why a particular runtime was selected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuntimeSelectionReason {
    ExactMatch,
    SameMinorFallback,
    BundledFallback,
    DefaultBundled,
}

impl RuntimeSelectionReason {
    pub fn description(self) -> &'static str {
        match self {
            RuntimeSelectionReason::ExactMatch => "exact runtime match",
            RuntimeSelectionReason::SameMinorFallback => "same-minor fallback",
            RuntimeSelectionReason::BundledFallback => "bundled fallback",
            RuntimeSelectionReason::DefaultBundled => "default bundled runtime",
        }
    }

    pub fn counter_name(self) -> &'static str {
        match self {
            RuntimeSelectionReason::ExactMatch => "runtime_selection.exact_match",
            RuntimeSelectionReason::SameMinorFallback => "runtime_selection.same_minor_fallback",
            RuntimeSelectionReason::BundledFallback => "runtime_selection.cross_minor_fallback",
            RuntimeSelectionReason::DefaultBundled => "runtime_selection.default_bundled",
        }
    }
}

impl SidecarRuntime {
    pub fn selection_warning_message(&self) -> Option<String> {
        let requested = self.requested_kotlin_version.as_deref()?;
        let selected = self.kotlin_version.as_deref().unwrap_or("unknown");

        match self.selection_reason {
            RuntimeSelectionReason::ExactMatch | RuntimeSelectionReason::DefaultBundled => None,
            RuntimeSelectionReason::SameMinorFallback => Some(format!(
                "kotlin-analyzer: project requests Kotlin {requested}, but that exact runtime is unavailable. Using Kotlin {selected} from the same minor line instead."
            )),
            RuntimeSelectionReason::BundledFallback => Some(format!(
                "kotlin-analyzer: project requests Kotlin {requested}, but that runtime is unavailable. Using bundled Kotlin {selected} instead; analysis may be inaccurate until a closer runtime is installed."
            )),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AvailableSidecarRuntime {
    pub kotlin_version: Option<String>,
    pub classpath: Vec<PathBuf>,
    pub main_class: Option<String>,
    validated_same_minor_lines: Vec<KotlinVersionLine>,
}

#[derive(Debug, Deserialize)]
struct RuntimeManifest {
    #[serde(rename = "kotlinVersion")]
    kotlin_version: String,
    #[serde(rename = "mainClass")]
    main_class: String,
    #[serde(rename = "analyzerVersion")]
    analyzer_version: Option<String>,
    #[serde(rename = "targetPlatform")]
    target_platform: Option<String>,
    #[serde(rename = "validatedSameMinor", default)]
    validated_same_minor: Vec<String>,
    classpath: Vec<PathBuf>,
}

pub fn resolve_sidecar_runtime(requested_kotlin_version: Option<&str>) -> Option<SidecarRuntime> {
    let context = RuntimeDiscoveryContext::for_current_process()?;
    let mut available = discover_available_sidecar_runtimes(&context);

    if let Some(requested_version) = requested_kotlin_version {
        let has_exact_match = available.iter().any(|runtime| {
            runtime
                .kotlin_version
                .as_deref()
                .is_some_and(|version| version == requested_version)
        });

        if !has_exact_match {
            if let Some(runtime) = provision_cached_runtime(&context, requested_version) {
                available.push(runtime);
            }
        }
    }

    if available.is_empty() {
        return None;
    }

    let selected = select_sidecar_runtime(requested_kotlin_version, &available);
    if let Some(runtime) = selected.as_ref() {
        tracing::info!(
            counter = runtime.selection_reason.counter_name(),
            count = 1u64,
            requested = runtime
                .requested_kotlin_version
                .as_deref()
                .unwrap_or("unknown"),
            selected = runtime.kotlin_version.as_deref().unwrap_or("unknown"),
            reason = runtime.selection_reason.description(),
            "sidecar runtime selection counter"
        );
    }
    selected
}

pub fn select_sidecar_runtime(
    requested_kotlin_version: Option<&str>,
    available: &[AvailableSidecarRuntime],
) -> Option<SidecarRuntime> {
    let requested = requested_kotlin_version.map(str::to_string);
    let requested_parsed = requested_kotlin_version.and_then(KotlinVersion::parse);

    if let Some(requested_version) = requested_kotlin_version {
        if let Some(runtime) = available.iter().find(|runtime| {
            runtime
                .kotlin_version
                .as_deref()
                .is_some_and(|version| version == requested_version)
        }) {
            return Some(materialize_runtime(
                runtime,
                requested,
                RuntimeSelectionReason::ExactMatch,
            ));
        }

        if let Some(requested_version) = requested_parsed {
            let same_minor = available
                .iter()
                .filter_map(|runtime| {
                    let version = runtime.kotlin_version.as_deref()?;
                    let parsed = KotlinVersion::parse(version)?;
                    if parsed.same_minor(&requested_version)
                        && runtime.supports_same_minor_fallback(&requested_version)
                    {
                        Some((parsed, runtime))
                    } else {
                        None
                    }
                })
                .max_by(|(left, _), (right, _)| left.cmp(right));

            if let Some((_, runtime)) = same_minor {
                return Some(materialize_runtime(
                    runtime,
                    requested,
                    RuntimeSelectionReason::SameMinorFallback,
                ));
            }
        }

        if let Some(runtime) = newest_known_runtime(available) {
            return Some(materialize_runtime(
                runtime,
                requested,
                RuntimeSelectionReason::BundledFallback,
            ));
        }

        return available.first().map(|runtime| {
            materialize_runtime(runtime, requested, RuntimeSelectionReason::BundledFallback)
        });
    }

    if let Some(runtime) = newest_known_runtime(available) {
        return Some(materialize_runtime(
            runtime,
            None,
            RuntimeSelectionReason::DefaultBundled,
        ));
    }

    available
        .first()
        .map(|runtime| materialize_runtime(runtime, None, RuntimeSelectionReason::DefaultBundled))
}

fn materialize_runtime(
    runtime: &AvailableSidecarRuntime,
    requested_kotlin_version: Option<String>,
    selection_reason: RuntimeSelectionReason,
) -> SidecarRuntime {
    SidecarRuntime {
        requested_kotlin_version,
        kotlin_version: runtime.kotlin_version.clone(),
        classpath: runtime.classpath.clone(),
        main_class: runtime.main_class.clone(),
        selection_reason,
    }
}

fn newest_known_runtime(available: &[AvailableSidecarRuntime]) -> Option<&AvailableSidecarRuntime> {
    available
        .iter()
        .filter_map(|runtime| {
            let version = runtime.kotlin_version.as_deref()?;
            Some((KotlinVersion::parse(version)?, runtime))
        })
        .max_by(|(left, _), (right, _)| left.cmp(right))
        .map(|(_, runtime)| runtime)
}

#[derive(Debug, Clone)]
struct RuntimeDiscoveryContext {
    exe: PathBuf,
    exe_dir: PathBuf,
    repo_root: Option<PathBuf>,
    cache_root: Option<PathBuf>,
    provision_roots: Vec<PathBuf>,
}

impl RuntimeDiscoveryContext {
    fn for_current_process() -> Option<Self> {
        let exe = std::env::current_exe().ok()?;
        let exe = std::fs::canonicalize(&exe).unwrap_or(exe);
        let exe_dir = exe.parent()?.to_path_buf();
        let repo_root = infer_repo_root(&exe);
        let cache_root = runtime_cache_root();
        let provision_roots = provision_source_roots(repo_root.as_deref());

        Some(Self {
            exe,
            exe_dir,
            repo_root,
            cache_root,
            provision_roots,
        })
    }
}

impl AvailableSidecarRuntime {
    fn supports_same_minor_fallback(&self, requested_version: &KotlinVersion) -> bool {
        let requested_line = requested_version.line();
        self.validated_same_minor_lines
            .iter()
            .any(|line| line == &requested_line)
    }
}

fn discover_available_sidecar_runtimes(
    context: &RuntimeDiscoveryContext,
) -> Vec<AvailableSidecarRuntime> {
    let mut runtimes = Vec::new();

    runtimes.extend(discover_manifest_runtimes(
        &context.exe_dir.join("sidecar-runtimes"),
    ));

    if let Some(cache_root) = &context.cache_root {
        runtimes.extend(discover_manifest_runtimes(cache_root));
    }

    let bundled = context.exe_dir.join("sidecar.jar");
    if bundled.exists() {
        runtimes.push(AvailableSidecarRuntime {
            kotlin_version: None,
            classpath: vec![bundled],
            main_class: None,
            validated_same_minor_lines: Vec::new(),
        });
    }

    if let Some(repo_root) = &context.repo_root {
        runtimes.extend(discover_manifest_runtimes(
            &repo_root.join("sidecar/build/runtime"),
        ));

        let dev_jar = repo_root.join("sidecar/build/libs/sidecar-all.jar");
        if dev_jar.exists() {
            runtimes.push(AvailableSidecarRuntime {
                kotlin_version: read_dev_kotlin_version(repo_root.join("sidecar/build.gradle.kts")),
                classpath: vec![dev_jar],
                main_class: None,
                validated_same_minor_lines: Vec::new(),
            });
        }
    }

    runtimes
}

fn infer_repo_root(exe: &Path) -> Option<PathBuf> {
    let mut candidate = exe.parent()?.to_path_buf();
    while candidate.parent().is_some() {
        if candidate.join("Cargo.toml").exists() && candidate.join("sidecar").is_dir() {
            return Some(candidate);
        }
        candidate = candidate.parent()?.to_path_buf();
    }
    None
}

fn provision_source_roots(repo_root: Option<&Path>) -> Vec<PathBuf> {
    let mut roots = std::env::var_os(PROVISION_DIRS_ENV)
        .map(|value| std::env::split_paths(&value).collect::<Vec<_>>())
        .unwrap_or_default();

    if let Some(repo_root) = repo_root {
        roots.push(repo_root.join("sidecar/build/runtime"));
    }

    roots.retain(|path| path.exists());
    roots.sort();
    roots.dedup();
    roots
}

fn runtime_cache_root() -> Option<PathBuf> {
    if let Some(path) = std::env::var_os(CACHE_DIR_ENV) {
        let path = PathBuf::from(path);
        if path.as_os_str().is_empty() {
            return None;
        }
        return Some(runtime_cache_bucket(&path));
    }

    default_cache_base_dir().map(|base| runtime_cache_bucket(&base))
}

fn runtime_cache_bucket(base: &Path) -> PathBuf {
    base.join(env!("CARGO_PKG_VERSION"))
        .join(target_platform_key())
}

fn default_cache_base_dir() -> Option<PathBuf> {
    #[cfg(target_os = "macos")]
    {
        let home = std::env::var_os("HOME")?;
        return Some(
            PathBuf::from(home)
                .join("Library")
                .join("Caches")
                .join("kotlin-analyzer")
                .join("runtimes"),
        );
    }

    #[cfg(target_os = "windows")]
    {
        if let Some(local_app_data) = std::env::var_os("LOCALAPPDATA") {
            return Some(
                PathBuf::from(local_app_data)
                    .join("kotlin-analyzer")
                    .join("runtimes"),
            );
        }
        let home = std::env::var_os("USERPROFILE")?;
        return Some(
            PathBuf::from(home)
                .join("AppData")
                .join("Local")
                .join("kotlin-analyzer")
                .join("runtimes"),
        );
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        if let Some(xdg_cache_home) = std::env::var_os("XDG_CACHE_HOME") {
            return Some(
                PathBuf::from(xdg_cache_home)
                    .join("kotlin-analyzer")
                    .join("runtimes"),
            );
        }
        let home = std::env::var_os("HOME")?;
        return Some(
            PathBuf::from(home)
                .join(".cache")
                .join("kotlin-analyzer")
                .join("runtimes"),
        );
    }
}

fn target_platform_key() -> String {
    option_env!("TARGET")
        .map(str::to_string)
        .unwrap_or_else(|| format!("{}-{}", std::env::consts::ARCH, std::env::consts::OS))
}

fn provision_cached_runtime(
    context: &RuntimeDiscoveryContext,
    requested_kotlin_version: &str,
) -> Option<AvailableSidecarRuntime> {
    let cache_root = context.cache_root.as_ref()?;

    let source_manifest = context
        .provision_roots
        .iter()
        .find_map(|root| find_runtime_manifest(root, requested_kotlin_version))?;

    let source_runtime = load_runtime_manifest(&source_manifest)?;
    let source_runtime_dir = source_manifest.parent()?;
    let destination_runtime_dir = cache_root.join(requested_kotlin_version);
    let destination_manifest = destination_runtime_dir.join("manifest.json");

    if destination_manifest.exists() {
        return load_runtime_manifest(&destination_manifest);
    }

    std::fs::create_dir_all(cache_root).ok()?;
    let staging_dir = cache_root.join(staging_directory_name(requested_kotlin_version));
    if staging_dir.exists() {
        let _ = std::fs::remove_dir_all(&staging_dir);
    }

    if copy_runtime_tree(source_runtime_dir, &staging_dir).is_err() {
        tracing::warn!(
            counter = "runtime_provision.failure",
            count = 1u64,
            requested = requested_kotlin_version,
            source = %source_runtime_dir.display(),
            destination = %destination_runtime_dir.display(),
            "failed to stage sidecar runtime into cache"
        );
        let _ = std::fs::remove_dir_all(&staging_dir);
        return None;
    }

    match std::fs::rename(&staging_dir, &destination_runtime_dir) {
        Ok(()) => {
            tracing::info!(
                requested = requested_kotlin_version,
                source = %source_runtime_dir.display(),
                destination = %destination_runtime_dir.display(),
                executable = %context.exe.display(),
                "provisioned sidecar runtime into local cache"
            );
        }
        Err(err) if destination_runtime_dir.exists() => {
            let _ = std::fs::remove_dir_all(&staging_dir);
            tracing::debug!(
                requested = requested_kotlin_version,
                destination = %destination_runtime_dir.display(),
                error = %err,
                "sidecar runtime cache entry already materialized"
            );
        }
        Err(err) => {
            let _ = std::fs::remove_dir_all(&staging_dir);
            tracing::warn!(
                counter = "runtime_provision.failure",
                count = 1u64,
                requested = requested_kotlin_version,
                source = %source_runtime_dir.display(),
                destination = %destination_runtime_dir.display(),
                error = %err,
                "failed to provision sidecar runtime into cache"
            );
            return None;
        }
    }

    load_runtime_manifest(&destination_manifest).or(Some(source_runtime))
}

fn find_runtime_manifest(root: &Path, kotlin_version: &str) -> Option<PathBuf> {
    let direct = root.join(kotlin_version).join("manifest.json");
    if direct.exists() {
        return Some(direct);
    }

    let Ok(entries) = std::fs::read_dir(root) else {
        return None;
    };

    entries.flatten().find_map(|entry| {
        let manifest = entry.path().join(kotlin_version).join("manifest.json");
        manifest.exists().then_some(manifest)
    })
}

fn staging_directory_name(kotlin_version: &str) -> PathBuf {
    let mut suffix = OsString::from(".");
    suffix.push(kotlin_version);
    suffix.push(".tmp-");
    suffix.push(std::process::id().to_string());
    suffix.push("-");
    suffix.push(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_nanos().to_string())
            .unwrap_or_else(|_| "0".to_string()),
    );
    PathBuf::from(suffix)
}

fn copy_runtime_tree(source: &Path, destination: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(destination)?;
    for entry in std::fs::read_dir(source)? {
        let entry = entry?;
        let entry_type = entry.file_type()?;
        let source_path = entry.path();
        let destination_path = destination.join(entry.file_name());
        if entry_type.is_dir() {
            copy_runtime_tree(&source_path, &destination_path)?;
        } else if entry_type.is_file() {
            std::fs::copy(&source_path, &destination_path)?;
        }
    }
    Ok(())
}

fn discover_manifest_runtimes(root: &Path) -> Vec<AvailableSidecarRuntime> {
    let Ok(entries) = std::fs::read_dir(root) else {
        return Vec::new();
    };

    let mut manifests = entries
        .flatten()
        .map(|entry| entry.path().join("manifest.json"))
        .filter(|path| path.exists())
        .collect::<Vec<_>>();
    manifests.sort();

    manifests
        .into_iter()
        .filter_map(|manifest| load_runtime_manifest(&manifest))
        .collect()
}

fn load_runtime_manifest(path: &Path) -> Option<AvailableSidecarRuntime> {
    let manifest_dir = path.parent()?;
    let manifest = std::fs::read_to_string(path).ok()?;
    let manifest: RuntimeManifest = serde_json::from_str(&manifest).ok()?;
    let expected_runtime_dir = manifest_dir.file_name()?.to_str()?;
    if expected_runtime_dir != manifest.kotlin_version {
        tracing::warn!(
            manifest = %path.display(),
            manifest_version = manifest.kotlin_version,
            directory_version = expected_runtime_dir,
            "ignoring sidecar runtime manifest with mismatched cache key"
        );
        return None;
    }

    if let Some(analyzer_version) = manifest.analyzer_version.as_deref() {
        if analyzer_version != env!("CARGO_PKG_VERSION") {
            tracing::warn!(
                manifest = %path.display(),
                manifest_version = analyzer_version,
                expected_version = env!("CARGO_PKG_VERSION"),
                "ignoring sidecar runtime manifest built for a different kotlin-analyzer version"
            );
            return None;
        }
    }

    if let Some(target_platform) = manifest.target_platform.as_deref() {
        let expected_platform = target_platform_key();
        if target_platform != expected_platform && target_platform != "any" {
            tracing::warn!(
                manifest = %path.display(),
                manifest_platform = target_platform,
                expected_platform = expected_platform,
                "ignoring sidecar runtime manifest built for a different platform"
            );
            return None;
        }
    }

    let classpath = manifest
        .classpath
        .into_iter()
        .map(|entry| manifest_dir.join(entry))
        .collect::<Vec<_>>();

    if classpath.is_empty() || classpath.iter().any(|entry| !entry.exists()) {
        tracing::warn!(manifest = %path.display(), "ignoring incomplete sidecar runtime manifest");
        return None;
    }

    Some(AvailableSidecarRuntime {
        kotlin_version: Some(manifest.kotlin_version),
        classpath,
        main_class: Some(manifest.main_class),
        validated_same_minor_lines: manifest
            .validated_same_minor
            .into_iter()
            .filter_map(|line| match KotlinVersionLine::parse(&line) {
                Some(parsed) => Some(parsed),
                None => {
                    tracing::warn!(
                        manifest = %path.display(),
                        invalid_line = line,
                        "ignoring invalid validated same-minor entry in sidecar runtime manifest"
                    );
                    None
                }
            })
            .collect(),
    })
}

fn read_dev_kotlin_version(build_file: PathBuf) -> Option<String> {
    let contents = std::fs::read_to_string(build_file).ok()?;
    contents.lines().find_map(|line| {
        let line = line.trim();
        if !line.starts_with("val kotlinVersion") {
            return None;
        }
        let (_, value) = line.split_once('=')?;
        let value = value.trim().trim_matches('"');
        if value.is_empty() {
            None
        } else {
            Some(value.to_string())
        }
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct KotlinVersion {
    major: u32,
    minor: u32,
    patch: u32,
    pre_release: bool,
}

impl KotlinVersion {
    fn parse(raw: &str) -> Option<Self> {
        let mut parts = raw.splitn(2, '-');
        let numbers = parts.next()?;
        let pre_release = parts.next().is_some();
        let mut numbers = numbers.split('.');
        Some(Self {
            major: numbers.next()?.parse().ok()?,
            minor: numbers.next()?.parse().ok()?,
            patch: numbers.next()?.parse().ok()?,
            pre_release,
        })
    }

    fn same_minor(&self, other: &Self) -> bool {
        self.major == other.major && self.minor == other.minor
    }

    fn line(&self) -> KotlinVersionLine {
        KotlinVersionLine {
            major: self.major,
            minor: self.minor,
        }
    }
}

impl Ord for KotlinVersion {
    fn cmp(&self, other: &Self) -> Ordering {
        (self.major, self.minor, self.patch, !self.pre_release).cmp(&(
            other.major,
            other.minor,
            other.patch,
            !other.pre_release,
        ))
    }
}

impl PartialOrd for KotlinVersion {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct KotlinVersionLine {
    major: u32,
    minor: u32,
}

impl KotlinVersionLine {
    fn parse(raw: &str) -> Option<Self> {
        let mut parts = raw.splitn(2, '-');
        let numbers = parts.next()?;
        let mut numbers = numbers.split('.');
        Some(Self {
            major: numbers.next()?.parse().ok()?,
            minor: numbers.next()?.parse().ok()?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn runtime(version: &str) -> AvailableSidecarRuntime {
        AvailableSidecarRuntime {
            kotlin_version: Some(version.to_string()),
            classpath: vec![PathBuf::from(format!("{version}.jar"))],
            main_class: None,
            validated_same_minor_lines: Vec::new(),
        }
    }

    fn validated_runtime(version: &str, validated_lines: &[&str]) -> AvailableSidecarRuntime {
        let mut runtime = runtime(version);
        runtime.validated_same_minor_lines = validated_lines
            .iter()
            .filter_map(|line| KotlinVersionLine::parse(line))
            .collect();
        runtime
    }

    #[test]
    fn selection_prefers_exact_match() {
        let available = vec![runtime("2.2.0"), runtime("2.2.21"), runtime("2.3.0")];
        let selected = select_sidecar_runtime(Some("2.2.0"), &available).unwrap();

        assert_eq!(selected.kotlin_version.as_deref(), Some("2.2.0"));
        assert_eq!(
            selected.selection_reason,
            RuntimeSelectionReason::ExactMatch
        );
    }

    #[test]
    fn selection_prefers_newest_same_minor_fallback() {
        let available = vec![
            validated_runtime("2.2.10", &["2.2"]),
            validated_runtime("2.2.21", &["2.2"]),
            runtime("2.3.0"),
        ];
        let selected = select_sidecar_runtime(Some("2.2.5"), &available).unwrap();

        assert_eq!(selected.kotlin_version.as_deref(), Some("2.2.21"));
        assert_eq!(
            selected.selection_reason,
            RuntimeSelectionReason::SameMinorFallback
        );
    }

    #[test]
    fn selection_falls_back_to_newest_bundled_runtime() {
        let available = vec![runtime("2.1.20"), runtime("2.2.21"), runtime("2.3.0-RC2")];
        let selected = select_sidecar_runtime(Some("1.9.25"), &available).unwrap();

        assert_eq!(selected.kotlin_version.as_deref(), Some("2.3.0-RC2"));
        assert_eq!(
            selected.selection_reason,
            RuntimeSelectionReason::BundledFallback
        );
    }

    #[test]
    fn selection_uses_default_bundled_runtime_without_project_version() {
        let available = vec![runtime("2.2.0"), runtime("2.2.21")];
        let selected = select_sidecar_runtime(None, &available).unwrap();

        assert_eq!(selected.kotlin_version.as_deref(), Some("2.2.21"));
        assert_eq!(
            selected.selection_reason,
            RuntimeSelectionReason::DefaultBundled
        );
    }

    #[test]
    fn load_runtime_manifest_resolves_relative_classpath_entries() {
        let dir = tempdir().unwrap();
        let runtime_dir = dir.path().join("2.2.21");
        std::fs::create_dir_all(runtime_dir.join("launcher")).unwrap();
        std::fs::create_dir_all(runtime_dir.join("payload")).unwrap();
        std::fs::write(
            runtime_dir.join("launcher/sidecar-launcher.jar"),
            b"launcher",
        )
        .unwrap();
        std::fs::write(runtime_dir.join("payload/sidecar-impl.jar"), b"payload").unwrap();
        std::fs::write(
            runtime_dir.join("manifest.json"),
            r#"{
  "kotlinVersion": "2.2.21",
  "mainClass": "dev.kouros.sidecar.launcher.LauncherMain",
  "validatedSameMinor": ["2.2"],
  "classpath": [
    "launcher/sidecar-launcher.jar",
    "payload/sidecar-impl.jar"
  ]
}"#,
        )
        .unwrap();

        let runtime = load_runtime_manifest(&runtime_dir.join("manifest.json")).unwrap();

        assert_eq!(runtime.kotlin_version.as_deref(), Some("2.2.21"));
        assert_eq!(
            runtime.main_class.as_deref(),
            Some("dev.kouros.sidecar.launcher.LauncherMain")
        );
        assert_eq!(
            runtime.classpath,
            vec![
                runtime_dir.join("launcher/sidecar-launcher.jar"),
                runtime_dir.join("payload/sidecar-impl.jar"),
            ]
        );
        assert_eq!(
            runtime.validated_same_minor_lines,
            vec![KotlinVersionLine { major: 2, minor: 2 }]
        );
    }

    #[test]
    fn load_runtime_manifest_rejects_version_mismatch() {
        let dir = tempdir().unwrap();
        let runtime_dir = dir.path().join("2.2.21");
        std::fs::create_dir_all(runtime_dir.join("launcher")).unwrap();
        std::fs::write(
            runtime_dir.join("launcher/sidecar-launcher.jar"),
            b"launcher",
        )
        .unwrap();
        std::fs::write(
            runtime_dir.join("manifest.json"),
            r#"{
  "kotlinVersion": "2.2.20",
  "mainClass": "dev.kouros.sidecar.launcher.LauncherMain",
  "classpath": ["launcher/sidecar-launcher.jar"]
}"#,
        )
        .unwrap();

        assert!(load_runtime_manifest(&runtime_dir.join("manifest.json")).is_none());
    }

    #[test]
    fn cached_runtimes_are_discovered() {
        let dir = tempdir().unwrap();
        let cache_root = dir
            .path()
            .join(env!("CARGO_PKG_VERSION"))
            .join(target_platform_key());
        let runtime_dir = cache_root.join("2.2.21");
        std::fs::create_dir_all(runtime_dir.join("payload")).unwrap();
        std::fs::write(runtime_dir.join("payload/sidecar-impl.jar"), b"payload").unwrap();
        std::fs::write(
            runtime_dir.join("manifest.json"),
            format!(
                r#"{{
  "kotlinVersion": "2.2.21",
  "mainClass": "dev.kouros.sidecar.launcher.LauncherMain",
  "analyzerVersion": "{}",
  "targetPlatform": "{}",
  "classpath": ["payload/sidecar-impl.jar"]
}}"#,
                env!("CARGO_PKG_VERSION"),
                target_platform_key()
            ),
        )
        .unwrap();

        let context = RuntimeDiscoveryContext {
            exe: dir.path().join("bin/kotlin-analyzer"),
            exe_dir: dir.path().join("bin"),
            repo_root: None,
            cache_root: Some(cache_root),
            provision_roots: Vec::new(),
        };

        let runtimes = discover_available_sidecar_runtimes(&context);
        assert_eq!(runtimes.len(), 1);
        assert_eq!(runtimes[0].kotlin_version.as_deref(), Some("2.2.21"));
    }

    #[test]
    fn provision_exact_runtime_installs_into_cache() {
        let dir = tempdir().unwrap();
        let source_root = dir.path().join("source-runtimes");
        let cache_root = dir.path().join("cache");
        let runtime_dir = source_root.join("2.2.21");
        std::fs::create_dir_all(runtime_dir.join("payload")).unwrap();
        std::fs::write(runtime_dir.join("payload/sidecar-impl.jar"), b"payload").unwrap();
        std::fs::write(
            runtime_dir.join("manifest.json"),
            r#"{
  "kotlinVersion": "2.2.21",
  "mainClass": "dev.kouros.sidecar.launcher.LauncherMain",
  "classpath": ["payload/sidecar-impl.jar"]
}"#,
        )
        .unwrap();

        let context = RuntimeDiscoveryContext {
            exe: dir.path().join("bin/kotlin-analyzer"),
            exe_dir: dir.path().join("bin"),
            repo_root: None,
            cache_root: Some(cache_root.clone()),
            provision_roots: vec![source_root],
        };

        let runtime = provision_cached_runtime(&context, "2.2.21").unwrap();
        assert_eq!(runtime.kotlin_version.as_deref(), Some("2.2.21"));
        assert!(cache_root.join("2.2.21/manifest.json").exists());
        assert!(cache_root.join("2.2.21/payload/sidecar-impl.jar").exists());
    }

    #[test]
    fn same_minor_fallback_requires_validation() {
        let available = vec![runtime("2.2.21"), runtime("2.3.0")];
        let selected = select_sidecar_runtime(Some("2.2.5"), &available).unwrap();

        assert_eq!(selected.kotlin_version.as_deref(), Some("2.3.0"));
        assert_eq!(
            selected.selection_reason,
            RuntimeSelectionReason::BundledFallback
        );
    }

    #[test]
    fn same_minor_fallback_emits_warning_message() {
        let runtime = SidecarRuntime {
            requested_kotlin_version: Some("2.2.5".into()),
            kotlin_version: Some("2.2.21".into()),
            classpath: vec![PathBuf::from("2.2.21.jar")],
            main_class: None,
            selection_reason: RuntimeSelectionReason::SameMinorFallback,
        };

        assert_eq!(
            runtime.selection_warning_message().as_deref(),
            Some(
                "kotlin-analyzer: project requests Kotlin 2.2.5, but that exact runtime is unavailable. Using Kotlin 2.2.21 from the same minor line instead."
            )
        );
    }

    #[test]
    fn cross_minor_fallback_emits_warning_message() {
        let runtime = SidecarRuntime {
            requested_kotlin_version: Some("1.9.25".into()),
            kotlin_version: Some("2.2.21".into()),
            classpath: vec![PathBuf::from("2.2.21.jar")],
            main_class: None,
            selection_reason: RuntimeSelectionReason::BundledFallback,
        };

        assert_eq!(
            runtime.selection_warning_message().as_deref(),
            Some(
                "kotlin-analyzer: project requests Kotlin 1.9.25, but that runtime is unavailable. Using bundled Kotlin 2.2.21 instead; analysis may be inaccurate until a closer runtime is installed."
            )
        );
    }

    #[test]
    fn exact_match_does_not_emit_warning_message() {
        let runtime = SidecarRuntime {
            requested_kotlin_version: Some("2.2.21".into()),
            kotlin_version: Some("2.2.21".into()),
            classpath: vec![PathBuf::from("2.2.21.jar")],
            main_class: None,
            selection_reason: RuntimeSelectionReason::ExactMatch,
        };

        assert_eq!(runtime.selection_warning_message(), None);
    }
}
