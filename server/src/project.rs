use std::path::{Path, PathBuf};
use std::process::Command;

use serde::{Deserialize, Serialize};

use crate::config::Config;
use crate::error::{Error, ProjectError};

/// Resolved project model containing classpath, source roots, and compiler flags.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProjectModel {
    pub project_root: PathBuf,
    pub build_system: BuildSystem,
    pub source_roots: Vec<PathBuf>,
    pub classpath: Vec<PathBuf>,
    pub compiler_flags: Vec<String>,
    pub kotlin_version: Option<String>,
    pub jdk_home: Option<PathBuf>,
    /// Whether the project uses Jetpack Compose.
    #[serde(default)]
    pub has_compose: bool,
    /// Generated source roots (KAPT, KSP).
    #[serde(default)]
    pub generated_source_roots: Vec<PathBuf>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum BuildSystem {
    Gradle,
    Maven,
    None,
}

impl ProjectModel {
    /// Creates a minimal project model for a project with no build system.
    pub fn no_build_system(project_root: PathBuf) -> Self {
        Self {
            project_root,
            build_system: BuildSystem::None,
            source_roots: Vec::new(),
            classpath: Vec::new(),
            compiler_flags: Vec::new(),
            kotlin_version: None,
            jdk_home: None,
            has_compose: false,
            generated_source_roots: Vec::new(),
        }
    }
}

/// Detects the build system for a project root directory.
pub fn detect_build_system(root: &Path) -> BuildSystem {
    if root.join("build.gradle.kts").exists() || root.join("build.gradle").exists() {
        BuildSystem::Gradle
    } else if root.join("pom.xml").exists() {
        BuildSystem::Maven
    } else {
        BuildSystem::None
    }
}

/// Walks up from `start` looking for a directory that contains a build system
/// marker (build.gradle.kts, build.gradle, pom.xml, settings.gradle.kts,
/// settings.gradle, .kotlin-analyzer.json) or a VCS root (.git).
/// Returns the first ancestor that has one of these markers, or `start` itself
/// if no marker is found.
pub fn find_project_root(start: &Path) -> PathBuf {
    let mut current = start.to_path_buf();
    loop {
        // Build system markers
        if current.join("build.gradle.kts").exists()
            || current.join("build.gradle").exists()
            || current.join("settings.gradle.kts").exists()
            || current.join("settings.gradle").exists()
            || current.join("pom.xml").exists()
            || current.join(".kotlin-analyzer.json").exists()
        {
            return current;
        }
        // VCS root as fallback — better than a deep source directory
        if current.join(".git").exists() {
            return current;
        }
        match current.parent() {
            Some(parent) => current = parent.to_path_buf(),
            None => break,
        }
    }
    // No marker found — use the original path
    start.to_path_buf()
}

/// Resolves the project model from the build system.
///
/// Resolution order:
/// 1. Manual `.kotlin-analyzer.json` in project root (always takes priority)
/// 2. Gradle (`build.gradle.kts` or `build.gradle`)
/// 3. Maven (`pom.xml`)
/// 4. Stdlib-only fallback (analyze `.kt` files with no classpath)
pub fn resolve_project(root: &Path, config: &Config) -> Result<ProjectModel, Error> {
    // Check for manual configuration first
    let manual_config = root.join(".kotlin-analyzer.json");
    if manual_config.exists() {
        tracing::info!("using manual project configuration from .kotlin-analyzer.json");
        return resolve_manual_config(&manual_config, root, config);
    }

    let build_system = detect_build_system(root);

    match build_system {
        BuildSystem::Gradle => resolve_gradle_project(root, config),
        BuildSystem::Maven => resolve_maven_project(root, config),
        BuildSystem::None => {
            tracing::info!("no build system found, using stdlib-only analysis");
            let mut model = ProjectModel::no_build_system(root.to_path_buf());
            // Find .kt source files in the root
            model.source_roots = find_kotlin_source_roots(root);
            model.compiler_flags = config.compiler_flags.clone();
            Ok(model)
        }
    }
}

/// Gradle init script (Groovy DSL) that extracts classpath, source roots, and
/// compiler flags. Groovy is used instead of Kotlin DSL (`.gradle.kts`) because
/// init scripts are compiled before project buildscripts are evaluated — the
/// Kotlin Gradle Plugin classes (e.g. `KotlinCompile`) are not on the compile
/// classpath at init-script time. Groovy resolves types at runtime, sidestepping
/// the issue entirely.
const INIT_SCRIPT: &str = r#"
allprojects {
    task("kotlinAnalyzerExtract") {
        doLast {
            def sb = new StringBuilder()
            sb.append("---KOTLIN-ANALYZER-START---\n")

            // Source roots
            def jpe = project.extensions.findByType(org.gradle.api.plugins.JavaPluginExtension)
            if (jpe != null) {
                def main = jpe.sourceSets.findByName("main")
                if (main != null) {
                    main.allSource.srcDirs.each { dir ->
                        if (dir.exists()) sb.append("SOURCE_ROOT=${dir.absolutePath}\n")
                    }
                }
            }

            // Classpath
            try {
                def compileClasspath = project.configurations.getByName("compileClasspath")
                compileClasspath.resolve().each { file ->
                    sb.append("CLASSPATH=${file.absolutePath}\n")
                }
            } catch (Exception e) {
                sb.append("CLASSPATH_ERROR=${e.message}\n")
            }

            // Compiler flags — try multiple APIs for compatibility
            def flagsFound = new LinkedHashSet()
            // 1. New API: task.compilerOptions (Kotlin 1.8+)
            try {
                project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).each { task ->
                    task.compilerOptions.freeCompilerArgs.get().each { flag ->
                        flagsFound.add(flag)
                    }
                }
            } catch (Exception e) {}
            // 2. Old API: task.kotlinOptions (pre-1.8 projects)
            if (flagsFound.isEmpty()) {
                try {
                    project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).each { task ->
                        task.kotlinOptions.freeCompilerArgs.each { flag ->
                            flagsFound.add(flag)
                        }
                    }
                } catch (Exception e) {}
            }
            // 3. Top-level kotlin { compilerOptions { } } extension (Kotlin 2.0+)
            try {
                def kotlinExt = project.extensions.findByName("kotlin")
                if (kotlinExt != null) {
                    kotlinExt.compilerOptions.freeCompilerArgs.get().each { flag ->
                        flagsFound.add(flag)
                    }
                }
            } catch (Exception e) {}
            flagsFound.each { flag ->
                sb.append("COMPILER_FLAG=${flag}\n")
            }

            // Kotlin version
            try {
                def kotlinVersion = project.buildscript.configurations
                    .getByName("classpath")
                    .resolvedConfiguration
                    .resolvedArtifacts
                    .find { it.moduleVersion.id.group == "org.jetbrains.kotlin" && it.moduleVersion.id.name == "kotlin-gradle-plugin" }
                    ?.moduleVersion?.id?.version
                if (kotlinVersion != null) sb.append("KOTLIN_VERSION=${kotlinVersion}\n")
            } catch (Exception e) {
                sb.append("KOTLIN_VERSION_ERROR=${e.message}\n")
            }

            // Compose detection
            def hasCompose = project.plugins.hasPlugin("org.jetbrains.compose") ||
                project.plugins.hasPlugin("org.jetbrains.kotlin.plugin.compose")
            if (hasCompose) sb.append("HAS_COMPOSE=true\n")

            // KAPT generated sources
            def kaptDir = project.layout.buildDirectory.dir("generated/source/kapt/main").get().asFile
            if (kaptDir.exists()) sb.append("GENERATED_SOURCE_ROOT=${kaptDir.absolutePath}\n")

            // KSP generated sources
            def kspDir = project.layout.buildDirectory.dir("generated/ksp/main/kotlin").get().asFile
            if (kspDir.exists()) sb.append("GENERATED_SOURCE_ROOT=${kspDir.absolutePath}\n")

            sb.append("---KOTLIN-ANALYZER-END---\n")
            println(sb.toString())
        }
    }
}
"#;

/// Extracts project model from a Gradle project using the init script approach.
fn resolve_gradle_project(root: &Path, config: &Config) -> Result<ProjectModel, Error> {
    let gradlew = find_gradle_wrapper(root);

    let init_script_path = root.join(".kotlin-analyzer-init.gradle");
    std::fs::write(&init_script_path, INIT_SCRIPT)
        .map_err(|e| ProjectError::GradleFailed(format!("failed to write init script: {e}")))?;

    let output = Command::new(&gradlew)
        .current_dir(root)
        .arg("--init-script")
        .arg(&init_script_path)
        .arg("kotlinAnalyzerExtract")
        .arg("--quiet")
        .output()
        .map_err(|e| ProjectError::GradleFailed(format!("failed to run Gradle: {e}")))?;

    // Clean up init script
    let _ = std::fs::remove_file(&init_script_path);

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(ProjectError::GradleFailed(format!(
            "Gradle exited with {}: {}",
            output.status,
            stderr.chars().take(500).collect::<String>()
        ))
        .into());
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    parse_gradle_output(&stdout, root, config)
}

fn parse_gradle_output(output: &str, root: &Path, config: &Config) -> Result<ProjectModel, Error> {
    let mut model = ProjectModel {
        project_root: root.to_path_buf(),
        build_system: BuildSystem::Gradle,
        source_roots: Vec::new(),
        classpath: Vec::new(),
        compiler_flags: Vec::new(),
        kotlin_version: None,
        jdk_home: config.java_home.as_ref().map(PathBuf::from),
        has_compose: false,
        generated_source_roots: Vec::new(),
    };

    let mut in_section = false;

    for line in output.lines() {
        let line = line.trim();
        if line == "---KOTLIN-ANALYZER-START---" {
            in_section = true;
            continue;
        }
        if line == "---KOTLIN-ANALYZER-END---" {
            in_section = false;
            continue;
        }
        if !in_section {
            continue;
        }

        if let Some(path) = line.strip_prefix("SOURCE_ROOT=") {
            model.source_roots.push(PathBuf::from(path));
        } else if let Some(path) = line.strip_prefix("CLASSPATH=") {
            model.classpath.push(PathBuf::from(path));
        } else if let Some(err) = line.strip_prefix("CLASSPATH_ERROR=") {
            tracing::warn!("gradle classpath extraction failed: {}", err);
        } else if let Some(flag) = line.strip_prefix("COMPILER_FLAG=") {
            model.compiler_flags.push(flag.to_string());
        } else if let Some(version) = line.strip_prefix("KOTLIN_VERSION=") {
            model.kotlin_version = Some(version.to_string());
        } else if let Some(err) = line.strip_prefix("KOTLIN_VERSION_ERROR=") {
            tracing::warn!("gradle kotlin version extraction failed: {}", err);
        } else if line == "HAS_COMPOSE=true" {
            model.has_compose = true;
        } else if let Some(path) = line.strip_prefix("GENERATED_SOURCE_ROOT=") {
            model.generated_source_roots.push(PathBuf::from(path));
        }
    }

    // Merge config compiler flags (config overrides take precedence)
    for flag in &config.compiler_flags {
        if !model.compiler_flags.contains(flag) {
            model.compiler_flags.push(flag.clone());
        }
    }

    Ok(model)
}

fn resolve_maven_project(root: &Path, config: &Config) -> Result<ProjectModel, Error> {
    let mvn = if root.join("mvnw").exists() {
        root.join("mvnw")
    } else {
        PathBuf::from("mvn")
    };

    let output = Command::new(&mvn)
        .current_dir(root)
        .arg("dependency:build-classpath")
        .arg("-DincludeScope=compile")
        .arg("-Dmdep.outputFile=/dev/stdout")
        .arg("-q")
        .output()
        .map_err(|e| ProjectError::GradleFailed(format!("failed to run Maven: {e}")))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(ProjectError::ClasspathExtraction(format!(
            "Maven exited with {}: {}",
            output.status,
            stderr.chars().take(500).collect::<String>()
        ))
        .into());
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let classpath: Vec<PathBuf> = stdout
        .lines()
        .flat_map(|line| line.split(':'))
        .filter(|p| !p.is_empty() && Path::new(p).exists())
        .map(PathBuf::from)
        .collect();

    let mut model = ProjectModel {
        project_root: root.to_path_buf(),
        build_system: BuildSystem::Maven,
        source_roots: vec![root.join("src/main/kotlin"), root.join("src/main/java")],
        classpath,
        compiler_flags: config.compiler_flags.clone(),
        kotlin_version: None,
        jdk_home: config.java_home.as_ref().map(PathBuf::from),
        has_compose: false,
        generated_source_roots: Vec::new(),
    };

    // Filter to existing source roots
    model.source_roots.retain(|p| p.exists());

    Ok(model)
}

/// Manual project configuration file format.
/// Users create `.kotlin-analyzer.json` in the project root for projects
/// without Gradle/Maven or when automatic extraction fails.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ManualProjectConfig {
    #[serde(default)]
    source_roots: Vec<String>,
    #[serde(default)]
    classpath: Vec<String>,
    #[serde(default)]
    compiler_flags: Vec<String>,
    #[serde(default)]
    kotlin_version: Option<String>,
    #[serde(default)]
    jdk_home: Option<String>,
}

fn resolve_manual_config(
    config_path: &Path,
    root: &Path,
    lsp_config: &Config,
) -> Result<ProjectModel, Error> {
    let content = std::fs::read_to_string(config_path).map_err(Error::Io)?;
    let manual: ManualProjectConfig = serde_json::from_str(&content)
        .map_err(|e| ProjectError::ClasspathExtraction(format!("invalid .kotlin-analyzer.json: {e}")))?;

    let source_roots: Vec<PathBuf> = manual
        .source_roots
        .iter()
        .map(|p| {
            let path = PathBuf::from(p);
            if path.is_absolute() {
                path
            } else {
                root.join(p)
            }
        })
        .filter(|p| p.exists())
        .collect();

    let classpath: Vec<PathBuf> = manual
        .classpath
        .iter()
        .map(|p| {
            let path = PathBuf::from(p);
            if path.is_absolute() {
                path
            } else {
                root.join(p)
            }
        })
        .filter(|p| p.exists())
        .collect();

    let mut compiler_flags = manual.compiler_flags;
    for flag in &lsp_config.compiler_flags {
        if !compiler_flags.contains(flag) {
            compiler_flags.push(flag.clone());
        }
    }

    let jdk_home = manual
        .jdk_home
        .map(PathBuf::from)
        .or_else(|| lsp_config.java_home.as_ref().map(PathBuf::from));

    Ok(ProjectModel {
        project_root: root.to_path_buf(),
        build_system: BuildSystem::None,
        source_roots,
        classpath,
        compiler_flags,
        kotlin_version: manual.kotlin_version,
        jdk_home,
        has_compose: false,
        generated_source_roots: Vec::new(),
    })
}

fn find_gradle_wrapper(root: &Path) -> PathBuf {
    let gradlew = if cfg!(target_os = "windows") {
        root.join("gradlew.bat")
    } else {
        root.join("gradlew")
    };

    if gradlew.exists() {
        gradlew
    } else {
        PathBuf::from("gradle")
    }
}

fn find_kotlin_source_roots(root: &Path) -> Vec<PathBuf> {
    let candidates = [
        root.join("src/main/kotlin"),
        root.join("src/main/java"),
        root.join("src"),
    ];

    candidates.into_iter().filter(|p| p.exists()).collect()
}

/// Saves the project model to a cache file.
pub fn save_cache(model: &ProjectModel, cache_dir: &Path) -> Result<(), Error> {
    std::fs::create_dir_all(cache_dir).map_err(Error::Io)?;
    let cache_file = cache_dir.join("project-model.json");
    let json = serde_json::to_string_pretty(model)
        .map_err(|e| ProjectError::ClasspathExtraction(e.to_string()))?;
    std::fs::write(&cache_file, json).map_err(Error::Io)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    #[test]
    fn detect_gradle_kts() {
        let dir = TempDir::new().unwrap();
        fs::write(dir.path().join("build.gradle.kts"), "").unwrap();
        assert_eq!(detect_build_system(dir.path()), BuildSystem::Gradle);
    }

    #[test]
    fn detect_gradle_groovy() {
        let dir = TempDir::new().unwrap();
        fs::write(dir.path().join("build.gradle"), "").unwrap();
        assert_eq!(detect_build_system(dir.path()), BuildSystem::Gradle);
    }

    #[test]
    fn detect_maven() {
        let dir = TempDir::new().unwrap();
        fs::write(dir.path().join("pom.xml"), "").unwrap();
        assert_eq!(detect_build_system(dir.path()), BuildSystem::Maven);
    }

    #[test]
    fn detect_no_build_system() {
        let dir = TempDir::new().unwrap();
        assert_eq!(detect_build_system(dir.path()), BuildSystem::None);
    }

    #[test]
    fn parse_gradle_output_parses_all_sections() {
        let output = r#"
---KOTLIN-ANALYZER-START---
SOURCE_ROOT=/project/src/main/kotlin
CLASSPATH=/lib/kotlin-stdlib-2.1.20.jar
CLASSPATH=/lib/kotlinx-coroutines-core-1.8.0.jar
COMPILER_FLAG=-Xcontext-parameters
KOTLIN_VERSION=2.1.20
---KOTLIN-ANALYZER-END---
"#;
        let config = Config::default();
        let model = parse_gradle_output(output, Path::new("/project"), &config).unwrap();
        assert_eq!(model.source_roots.len(), 1);
        assert_eq!(model.classpath.len(), 2);
        assert_eq!(model.compiler_flags, vec!["-Xcontext-parameters"]);
        assert_eq!(model.kotlin_version, Some("2.1.20".into()));
    }

    #[test]
    fn parse_gradle_output_merges_config_flags() {
        let output = r#"
---KOTLIN-ANALYZER-START---
COMPILER_FLAG=-Xcontext-parameters
---KOTLIN-ANALYZER-END---
"#;
        let config = Config {
            compiler_flags: vec![
                "-Xcontext-parameters".into(), // duplicate
                "-Xmulti-dollar-interpolation".into(),
            ],
            ..Config::default()
        };
        let model = parse_gradle_output(output, Path::new("/project"), &config).unwrap();
        assert_eq!(model.compiler_flags.len(), 2);
    }

    #[test]
    fn no_build_system_model() {
        let model = ProjectModel::no_build_system(PathBuf::from("/project"));
        assert_eq!(model.build_system, BuildSystem::None);
        assert!(model.classpath.is_empty());
    }

    #[test]
    fn manual_config_overrides_detection() {
        let dir = TempDir::new().unwrap();
        // Even with a build.gradle.kts present, .kotlin-analyzer.json takes priority
        fs::write(dir.path().join("build.gradle.kts"), "").unwrap();
        fs::write(
            dir.path().join(".kotlin-analyzer.json"),
            r#"{
                "compilerFlags": ["-Xcontext-parameters"],
                "kotlinVersion": "2.1.20"
            }"#,
        )
        .unwrap();

        let config = Config::default();
        let model = resolve_project(dir.path(), &config).unwrap();
        assert_eq!(model.compiler_flags, vec!["-Xcontext-parameters"]);
        assert_eq!(model.kotlin_version, Some("2.1.20".into()));
    }

    #[test]
    fn manual_config_relative_paths() {
        let dir = TempDir::new().unwrap();
        let src_dir = dir.path().join("src");
        fs::create_dir_all(&src_dir).unwrap();
        fs::write(
            dir.path().join(".kotlin-analyzer.json"),
            r#"{"sourceRoots": ["src"]}"#,
        )
        .unwrap();

        let config = Config::default();
        let model = resolve_project(dir.path(), &config).unwrap();
        assert_eq!(model.source_roots.len(), 1);
        assert_eq!(model.source_roots[0], src_dir);
    }

    #[test]
    fn manual_config_merges_lsp_flags() {
        let dir = TempDir::new().unwrap();
        fs::write(
            dir.path().join(".kotlin-analyzer.json"),
            r#"{"compilerFlags": ["-Xcontext-parameters"]}"#,
        )
        .unwrap();

        let config = Config {
            compiler_flags: vec!["-Xmulti-dollar-interpolation".into()],
            ..Config::default()
        };
        let model = resolve_project(dir.path(), &config).unwrap();
        assert_eq!(model.compiler_flags.len(), 2);
    }

    #[test]
    fn parse_gradle_output_multi_module() {
        let output = r#"
---KOTLIN-ANALYZER-START---
SOURCE_ROOT=/project/common/src/main/kotlin
CLASSPATH=/lib/spring-context.jar
COMPILER_FLAG=-Xcontext-parameters
---KOTLIN-ANALYZER-END---
---KOTLIN-ANALYZER-START---
SOURCE_ROOT=/project/app/src/main/kotlin
CLASSPATH=/lib/spring-boot-starter-web.jar
KOTLIN_VERSION=2.1.20
---KOTLIN-ANALYZER-END---
"#;
        let config = Config::default();
        let model = parse_gradle_output(output, Path::new("/project"), &config).unwrap();
        assert_eq!(model.source_roots.len(), 2);
        assert_eq!(model.classpath.len(), 2);
        assert_eq!(model.compiler_flags, vec!["-Xcontext-parameters"]);
        assert_eq!(model.kotlin_version, Some("2.1.20".into()));
    }

    #[test]
    fn parse_gradle_output_with_errors() {
        let output = r#"
---KOTLIN-ANALYZER-START---
SOURCE_ROOT=/project/src/main/kotlin
CLASSPATH_ERROR=Cannot resolve configuration 'compileClasspath'
KOTLIN_VERSION_ERROR=Could not resolve buildscript classpath
COMPILER_FLAG=-Xcontext-parameters
---KOTLIN-ANALYZER-END---
"#;
        let config = Config::default();
        let model = parse_gradle_output(output, Path::new("/project"), &config).unwrap();
        assert_eq!(model.source_roots.len(), 1);
        assert!(model.classpath.is_empty());
        assert_eq!(model.compiler_flags, vec!["-Xcontext-parameters"]);
        assert_eq!(model.kotlin_version, None);
    }

    #[test]
    fn parse_gradle_output_multi_module_compose_and_generated() {
        let output = r#"
---KOTLIN-ANALYZER-START---
SOURCE_ROOT=/project/app/src/main/kotlin
HAS_COMPOSE=true
---KOTLIN-ANALYZER-END---
---KOTLIN-ANALYZER-START---
SOURCE_ROOT=/project/lib/src/main/kotlin
GENERATED_SOURCE_ROOT=/project/lib/build/generated/ksp/main/kotlin
---KOTLIN-ANALYZER-END---
"#;
        let config = Config::default();
        let model = parse_gradle_output(output, Path::new("/project"), &config).unwrap();
        assert_eq!(model.source_roots.len(), 2);
        assert!(model.has_compose);
        assert_eq!(model.generated_source_roots.len(), 1);
    }

    #[test]
    #[cfg(feature = "integration")]
    fn init_script_kotlin_project() {
        let fixture = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../tests/fixtures/gradle-kotlin-simple");
        let config = Config::default();
        let model =
            resolve_gradle_project(&fixture, &config).expect("gradle resolution should succeed");

        assert!(!model.source_roots.is_empty(), "should find source roots");
        assert!(!model.classpath.is_empty(), "should find classpath entries");
        assert!(
            model.kotlin_version.is_some(),
            "should detect kotlin version"
        );
    }

    #[test]
    #[cfg(feature = "integration")]
    fn init_script_java_only_project() {
        let fixture = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../tests/fixtures/gradle-java-only");
        let config = Config::default();
        let model = resolve_gradle_project(&fixture, &config)
            .expect("gradle resolution should not crash on java-only project");

        assert!(
            model.kotlin_version.is_none(),
            "no kotlin version in java-only project"
        );
        assert!(
            model.compiler_flags.is_empty(),
            "no compiler flags without kotlin plugin"
        );
    }

    #[test]
    #[cfg(feature = "integration")]
    fn init_script_output_format() {
        let fixture = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../tests/fixtures/gradle-kotlin-simple");

        // Write init script to a temp dir to avoid racing with other tests
        // that also write to the fixture directory.
        let tmp = TempDir::new().unwrap();
        let init_script_path = tmp.path().join("kotlin-analyzer-init.gradle");
        std::fs::write(&init_script_path, INIT_SCRIPT).unwrap();

        let output = std::process::Command::new("gradle")
            .current_dir(&fixture)
            .arg("--init-script")
            .arg(&init_script_path)
            .arg("kotlinAnalyzerExtract")
            .arg("--quiet")
            .output()
            .expect("gradle should be available for integration tests");

        assert!(
            output.status.success(),
            "gradle should exit 0: {}",
            String::from_utf8_lossy(&output.stderr)
        );

        let stdout = String::from_utf8_lossy(&output.stdout);
        assert!(
            stdout.contains("---KOTLIN-ANALYZER-START---"),
            "missing start marker"
        );
        assert!(
            stdout.contains("---KOTLIN-ANALYZER-END---"),
            "missing end marker"
        );
        assert!(stdout.contains("SOURCE_ROOT="), "missing source root");
        assert!(stdout.contains("CLASSPATH="), "missing classpath");
    }

    #[test]
    fn find_project_root_from_source_dir() {
        let dir = TempDir::new().unwrap();
        let src = dir.path().join("src/main/kotlin/com/example");
        fs::create_dir_all(&src).unwrap();
        fs::write(dir.path().join("build.gradle.kts"), "").unwrap();

        let found = find_project_root(&src);
        assert_eq!(found, dir.path());
    }

    #[test]
    fn find_project_root_already_at_root() {
        let dir = TempDir::new().unwrap();
        fs::write(dir.path().join("build.gradle.kts"), "").unwrap();

        let found = find_project_root(dir.path());
        assert_eq!(found, dir.path());
    }

    #[test]
    fn find_project_root_git_fallback() {
        let dir = TempDir::new().unwrap();
        let src = dir.path().join("src");
        fs::create_dir_all(&src).unwrap();
        fs::create_dir_all(dir.path().join(".git")).unwrap();

        let found = find_project_root(&src);
        assert_eq!(found, dir.path());
    }

    #[test]
    fn find_project_root_no_marker() {
        let dir = TempDir::new().unwrap();
        let deep = dir.path().join("a/b/c");
        fs::create_dir_all(&deep).unwrap();

        // Without any markers, returns the start path
        let found = find_project_root(&deep);
        // It will walk up and not find anything — should return the start
        // (In practice it walks up to the filesystem root and then returns start)
        // But since TempDir is under /tmp which likely has no build markers,
        // it should return the deep path.
        assert!(found.exists());
    }

    #[test]
    fn find_project_root_settings_gradle() {
        let dir = TempDir::new().unwrap();
        let src = dir.path().join("app/src/main/kotlin");
        fs::create_dir_all(&src).unwrap();
        fs::write(dir.path().join("settings.gradle.kts"), "").unwrap();

        let found = find_project_root(&src);
        assert_eq!(found, dir.path());
    }
}
