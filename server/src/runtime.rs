use std::cmp::Ordering;
use std::path::{Path, PathBuf};

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

    let bundled = exe_dir.join("sidecar.jar");
    if bundled.exists() {
        runtimes.push(AvailableSidecarRuntime {
            kotlin_version: None,
            classpath: vec![bundled],
            main_class: None,
        });
    }

    let repo_root = exe_dir.parent()?.parent()?.parent()?;
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
}
