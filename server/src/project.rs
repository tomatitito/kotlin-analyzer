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

/// Resolves the project model from the build system.
pub fn resolve_project(root: &Path, config: &Config) -> Result<ProjectModel, Error> {
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

/// Extracts project model from a Gradle project using the init script approach.
fn resolve_gradle_project(root: &Path, config: &Config) -> Result<ProjectModel, Error> {
    let gradlew = find_gradle_wrapper(root);

    // Use a Gradle init script to extract classpath and compiler flags
    let init_script = r#"
allprojects {
    task("kotlinAnalyzerExtract") {
        doLast {
            val sb = StringBuilder()
            sb.appendLine("---KOTLIN-ANALYZER-START---")

            // Source roots
            project.convention.findPlugin(org.gradle.api.plugins.JavaPluginConvention::class.java)?.let { jpc ->
                jpc.sourceSets.findByName("main")?.let { main ->
                    main.allSource.srcDirs.forEach { dir ->
                        if (dir.exists()) sb.appendLine("SOURCE_ROOT=${dir.absolutePath}")
                    }
                }
            }

            // Classpath
            try {
                val compileClasspath = project.configurations.getByName("compileClasspath")
                compileClasspath.resolve().forEach { file ->
                    sb.appendLine("CLASSPATH=${file.absolutePath}")
                }
            } catch (_: Exception) {}

            // Compiler flags
            project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).forEach { task ->
                task.compilerOptions.freeCompilerArgs.get().forEach { flag ->
                    sb.appendLine("COMPILER_FLAG=$flag")
                }
            }

            // Kotlin version
            try {
                val kotlinVersion = project.buildscript.configurations
                    .getByName("classpath")
                    .resolvedConfiguration
                    .resolvedArtifacts
                    .find { it.moduleVersion.id.group == "org.jetbrains.kotlin" && it.moduleVersion.id.name == "kotlin-gradle-plugin" }
                    ?.moduleVersion?.id?.version
                if (kotlinVersion != null) sb.appendLine("KOTLIN_VERSION=$kotlinVersion")
            } catch (_: Exception) {}

            sb.appendLine("---KOTLIN-ANALYZER-END---")
            println(sb.toString())
        }
    }
}
"#;

    let init_script_path = root.join(".kotlin-analyzer-init.gradle.kts");
    std::fs::write(&init_script_path, init_script)
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
    };

    let mut in_section = false;

    for line in output.lines() {
        let line = line.trim();
        if line == "---KOTLIN-ANALYZER-START---" {
            in_section = true;
            continue;
        }
        if line == "---KOTLIN-ANALYZER-END---" {
            break;
        }
        if !in_section {
            continue;
        }

        if let Some(path) = line.strip_prefix("SOURCE_ROOT=") {
            model.source_roots.push(PathBuf::from(path));
        } else if let Some(path) = line.strip_prefix("CLASSPATH=") {
            model.classpath.push(PathBuf::from(path));
        } else if let Some(flag) = line.strip_prefix("COMPILER_FLAG=") {
            model.compiler_flags.push(flag.to_string());
        } else if let Some(version) = line.strip_prefix("KOTLIN_VERSION=") {
            model.kotlin_version = Some(version.to_string());
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
    };

    // Filter to existing source roots
    model.source_roots.retain(|p| p.exists());

    Ok(model)
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

/// Loads the project model from cache.
pub fn load_cache(cache_dir: &Path) -> Option<ProjectModel> {
    let cache_file = cache_dir.join("project-model.json");
    let json = std::fs::read_to_string(&cache_file).ok()?;
    serde_json::from_str(&json).ok()
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
        let mut config = Config::default();
        config.compiler_flags = vec![
            "-Xcontext-parameters".into(), // duplicate
            "-Xmulti-dollar-interpolation".into(),
        ];
        let model = parse_gradle_output(output, Path::new("/project"), &config).unwrap();
        assert_eq!(model.compiler_flags.len(), 2);
    }

    #[test]
    fn no_build_system_model() {
        let model = ProjectModel::no_build_system(PathBuf::from("/project"));
        assert_eq!(model.build_system, BuildSystem::None);
        assert!(model.classpath.is_empty());
    }
}
