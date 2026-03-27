use std::cmp::Ordering;
use std::path::{Path, PathBuf};

use serde::Deserialize;

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
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AvailableSidecarRuntime {
    pub kotlin_version: Option<String>,
    pub classpath: Vec<PathBuf>,
    pub main_class: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RuntimeManifest {
    #[serde(rename = "kotlinVersion")]
    kotlin_version: String,
    #[serde(rename = "mainClass")]
    main_class: String,
    classpath: Vec<PathBuf>,
}

pub fn resolve_sidecar_runtime(requested_kotlin_version: Option<&str>) -> Option<SidecarRuntime> {
    let available = discover_available_sidecar_runtimes()?;
    select_sidecar_runtime(requested_kotlin_version, &available)
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
                    if parsed.same_minor(&requested_version) {
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

fn discover_available_sidecar_runtimes() -> Option<Vec<AvailableSidecarRuntime>> {
    let exe = std::env::current_exe().ok()?;
    let exe = std::fs::canonicalize(&exe).unwrap_or(exe);
    runtimes_from_executable(&exe)
}

fn runtimes_from_executable(exe: &Path) -> Option<Vec<AvailableSidecarRuntime>> {
    let exe_dir = exe.parent()?;
    let mut runtimes = Vec::new();

    runtimes.extend(discover_manifest_runtimes(&exe_dir.join("sidecar-runtimes")));

    let bundled = exe_dir.join("sidecar.jar");
    if bundled.exists() {
        runtimes.push(AvailableSidecarRuntime {
            kotlin_version: None,
            classpath: vec![bundled],
            main_class: None,
        });
    }

    let repo_root = exe_dir.parent()?.parent()?.parent()?;
    runtimes.extend(discover_manifest_runtimes(
        &repo_root.join("sidecar/build/runtime"),
    ));

    let dev_jar = repo_root.join("sidecar/build/libs/sidecar-all.jar");
    if dev_jar.exists() {
        runtimes.push(AvailableSidecarRuntime {
            kotlin_version: read_dev_kotlin_version(repo_root.join("sidecar/build.gradle.kts")),
            classpath: vec![dev_jar],
            main_class: None,
        });
    }

    if runtimes.is_empty() {
        None
    } else {
        Some(runtimes)
    }
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

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn runtime(version: &str) -> AvailableSidecarRuntime {
        AvailableSidecarRuntime {
            kotlin_version: Some(version.to_string()),
            classpath: vec![PathBuf::from(format!("{version}.jar"))],
            main_class: None,
        }
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
        let available = vec![runtime("2.2.10"), runtime("2.2.21"), runtime("2.3.0")];
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
        std::fs::write(runtime_dir.join("launcher/sidecar-launcher.jar"), b"launcher").unwrap();
        std::fs::write(runtime_dir.join("payload/sidecar-impl.jar"), b"payload").unwrap();
        std::fs::write(
            runtime_dir.join("manifest.json"),
            r#"{
  "kotlinVersion": "2.2.21",
  "mainClass": "dev.kouros.sidecar.launcher.LauncherMain",
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
    }
}
