use serde_json::Value;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let root = repo_root()?;
    let mut args = env::args().skip(1);
    match args.next().as_deref() {
        Some("set") => {
            let version = args.next().ok_or_else(|| usage().to_string())?;
            if args.next().is_some() {
                return Err(usage().to_string());
            }
            set_version(&root, &version)
        }
        Some("check") => {
            let include_generated = match args.next().as_deref() {
                None => false,
                Some("--include-generated-runtime-manifests") => true,
                _ => return Err(usage().to_string()),
            };
            if args.next().is_some() {
                return Err(usage().to_string());
            }
            check_version_sync(&root, include_generated)
        }
        _ => Err(usage().to_string()),
    }
}

fn usage() -> &'static str {
    "usage: version-sync <set <version> | check [--include-generated-runtime-manifests]>"
}

fn repo_root() -> Result<PathBuf, String> {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    manifest_dir
        .parent()
        .and_then(Path::parent)
        .map(Path::to_path_buf)
        .ok_or_else(|| "failed to determine repository root".to_string())
}

fn set_version(root: &Path, version: &str) -> Result<(), String> {
    validate_version(version)?;

    write_file(root.join("VERSION"), &format!("{version}\n"))?;
    replace_unique_line(
        root.join("Cargo.toml"),
        |line, _| is_version_line(line),
        &format!("version = \"{version}\""),
    )?;
    replace_lockfile_version(root.join("Cargo.lock"), "kotlin-analyzer", version)?;
    replace_unique_line(
        root.join("server/Cargo.toml"),
        |line, _| is_version_line(line),
        &format!("version = \"{version}\""),
    )?;
    replace_lockfile_version(
        root.join("server/Cargo.lock"),
        "kotlin-analyzer-server",
        version,
    )?;
    replace_unique_line(
        root.join("extension.toml"),
        |line, next| {
            line.trim_start().starts_with("version = ") && next == Some("schema_version = 1")
        },
        &format!("version = \"{version}\""),
    )?;
    replace_unique_line(
        root.join("sidecar/build.gradle.kts"),
        |line, _| is_version_line(line),
        &format!("version = \"{version}\""),
    )?;
    replace_sidecar_startup_version(
        root.join("sidecar/src/main/kotlin/dev/kouros/sidecar/Main.kt"),
        version,
    )?;

    println!("updated VERSION and derived metadata to {version}");
    Ok(())
}

fn check_version_sync(root: &Path, include_generated: bool) -> Result<(), String> {
    let expected = read_trimmed(root.join("VERSION"))?;
    let mut errors = Vec::new();

    check_equals(
        &mut errors,
        "Cargo.toml",
        extract_unique_line_value(root.join("Cargo.toml"), |line, _| is_version_line(line))?,
        &expected,
    );
    check_equals(
        &mut errors,
        "Cargo.lock",
        extract_lockfile_version(root.join("Cargo.lock"), "kotlin-analyzer")?,
        &expected,
    );
    check_equals(
        &mut errors,
        "server/Cargo.toml",
        extract_unique_line_value(root.join("server/Cargo.toml"), |line, _| {
            is_version_line(line)
        })?,
        &expected,
    );
    check_equals(
        &mut errors,
        "server/Cargo.lock",
        extract_lockfile_version(root.join("server/Cargo.lock"), "kotlin-analyzer-server")?,
        &expected,
    );
    check_equals(
        &mut errors,
        "extension.toml",
        extract_unique_line_value(root.join("extension.toml"), |line, next| {
            line.trim_start().starts_with("version = ") && next == Some("schema_version = 1")
        })?,
        &expected,
    );
    check_equals(
        &mut errors,
        "sidecar/build.gradle.kts",
        extract_unique_line_value(root.join("sidecar/build.gradle.kts"), |line, _| {
            is_version_line(line)
        })?,
        &expected,
    );
    check_equals(
        &mut errors,
        "sidecar/src/main/kotlin/dev/kouros/sidecar/Main.kt",
        extract_sidecar_startup_version(
            root.join("sidecar/src/main/kotlin/dev/kouros/sidecar/Main.kt"),
        )?,
        &expected,
    );

    if include_generated {
        errors.extend(check_generated_runtime_manifests(root, &expected)?);
    }

    if errors.is_empty() {
        println!("all versioned metadata matches VERSION={expected}");
        Ok(())
    } else {
        eprintln!("version sync check failed:");
        for error in errors {
            eprintln!("- {error}");
        }
        Err("version sync check failed".to_string())
    }
}

fn validate_version(version: &str) -> Result<(), String> {
    let mut chars = version.chars().peekable();
    for segment_index in 0..3 {
        let mut saw_digit = false;
        while matches!(chars.peek(), Some(c) if c.is_ascii_digit()) {
            saw_digit = true;
            chars.next();
        }
        if !saw_digit {
            return Err(format!("invalid version: {version}"));
        }
        if segment_index < 2 && chars.next() != Some('.') {
            return Err(format!("invalid version: {version}"));
        }
    }
    if let Some(separator) = chars.next() {
        if separator != '-' && separator != '+' {
            return Err(format!("invalid version: {version}"));
        }
        let mut saw_suffix = false;
        for c in chars {
            if c.is_ascii_alphanumeric() || c == '.' || c == '-' {
                saw_suffix = true;
            } else {
                return Err(format!("invalid version: {version}"));
            }
        }
        if !saw_suffix {
            return Err(format!("invalid version: {version}"));
        }
    }
    Ok(())
}

fn is_version_line(line: &str) -> bool {
    line.trim_start().starts_with("version = ")
}

fn replace_unique_line<F>(path: PathBuf, predicate: F, replacement: &str) -> Result<(), String>
where
    F: Fn(&str, Option<&str>) -> bool,
{
    let mut lines: Vec<String> = read_lines(&path)?;
    let index = unique_line_index(&lines, &predicate, &path)?;
    lines[index] = replacement.to_string();
    write_lines(path, &lines)
}

fn extract_unique_line_value<F>(path: PathBuf, predicate: F) -> Result<String, String>
where
    F: Fn(&str, Option<&str>) -> bool,
{
    let lines = read_lines(&path)?;
    let index = unique_line_index(&lines, &predicate, &path)?;
    extract_quoted_value(&lines[index]).ok_or_else(|| {
        format!(
            "expected quoted version value in {}",
            display_rel(&repo_root().unwrap_or_default(), &path)
        )
    })
}

fn unique_line_index<F>(lines: &[String], predicate: &F, path: &Path) -> Result<usize, String>
where
    F: Fn(&str, Option<&str>) -> bool,
{
    let mut matches = Vec::new();
    for (i, line) in lines.iter().enumerate() {
        let next = lines.get(i + 1).map(String::as_str);
        if predicate(line, next) {
            matches.push(i);
        }
    }
    match matches.as_slice() {
        [index] => Ok(*index),
        _ => Err(format!(
            "expected exactly one version match in {}, found {}",
            display_rel(&repo_root().unwrap_or_default(), path),
            matches.len()
        )),
    }
}

fn replace_lockfile_version(
    path: PathBuf,
    package_name: &str,
    version: &str,
) -> Result<(), String> {
    let mut lines = read_lines(&path)?;
    let index = lockfile_version_index(&lines, package_name, &path)?;
    lines[index] = format!("version = \"{version}\"");
    write_lines(path, &lines)
}

fn extract_lockfile_version(path: PathBuf, package_name: &str) -> Result<String, String> {
    let lines = read_lines(&path)?;
    let index = lockfile_version_index(&lines, package_name, &path)?;
    extract_quoted_value(&lines[index]).ok_or_else(|| {
        format!(
            "expected quoted version value in {}",
            display_rel(&repo_root().unwrap_or_default(), &path)
        )
    })
}

fn lockfile_version_index(
    lines: &[String],
    package_name: &str,
    path: &Path,
) -> Result<usize, String> {
    let mut matches = Vec::new();
    for i in 0..lines.len().saturating_sub(2) {
        if lines[i] == "[[package]]" && lines[i + 1] == format!("name = \"{package_name}\"") {
            if lines[i + 2].trim_start().starts_with("version = ") {
                matches.push(i + 2);
            }
        }
    }
    match matches.as_slice() {
        [index] => Ok(*index),
        _ => Err(format!(
            "expected exactly one version match in {}, found {}",
            display_rel(&repo_root().unwrap_or_default(), path),
            matches.len()
        )),
    }
}

fn replace_sidecar_startup_version(path: PathBuf, version: &str) -> Result<(), String> {
    let text = fs::read_to_string(&path).map_err(|error| {
        format!(
            "failed to read {}: {error}",
            display_rel(&repo_root().unwrap_or_default(), &path)
        )
    })?;
    let marker = "kotlin-analyzer sidecar v";
    let suffix = " starting (Kotlin ";
    let start = text.find(marker).ok_or_else(|| {
        format!(
            "expected exactly one version match in {}, found 0",
            display_rel(&repo_root().unwrap_or_default(), &path)
        )
    })? + marker.len();
    let rest = &text[start..];
    let end = rest.find(suffix).ok_or_else(|| {
        format!(
            "expected exactly one version match in {}, found 0",
            display_rel(&repo_root().unwrap_or_default(), &path)
        )
    })? + start;
    if text[end + suffix.len()..].contains(marker) {
        return Err(format!(
            "expected exactly one version match in {}, found more than one",
            display_rel(&repo_root().unwrap_or_default(), &path)
        ));
    }
    let mut updated = String::with_capacity(text.len() - (end - start) + version.len());
    updated.push_str(&text[..start]);
    updated.push_str(version);
    updated.push_str(&text[end..]);
    write_file(path, &updated)
}

fn extract_sidecar_startup_version(path: PathBuf) -> Result<String, String> {
    let text = fs::read_to_string(&path).map_err(|error| {
        format!(
            "failed to read {}: {error}",
            display_rel(&repo_root().unwrap_or_default(), &path)
        )
    })?;
    let marker = "kotlin-analyzer sidecar v";
    let suffix = " starting (Kotlin ";
    let start = text.find(marker).ok_or_else(|| {
        format!(
            "expected exactly one version match in {}, found 0",
            display_rel(&repo_root().unwrap_or_default(), &path)
        )
    })? + marker.len();
    let rest = &text[start..];
    let end = rest.find(suffix).ok_or_else(|| {
        format!(
            "expected exactly one version match in {}, found 0",
            display_rel(&repo_root().unwrap_or_default(), &path)
        )
    })? + start;
    Ok(text[start..end].to_string())
}

fn check_generated_runtime_manifests(root: &Path, expected: &str) -> Result<Vec<String>, String> {
    let runtime_root = root.join("sidecar/build/runtime");
    if !runtime_root.exists() {
        return Ok(vec![
            "generated runtime manifests requested, but sidecar/build/runtime does not exist"
                .to_string(),
        ]);
    }

    let mut manifests = Vec::new();
    for entry in fs::read_dir(&runtime_root).map_err(|error| {
        format!(
            "failed to read {}: {error}",
            display_rel(root, &runtime_root)
        )
    })? {
        let entry =
            entry.map_err(|error| format!("failed to read runtime directory entry: {error}"))?;
        let manifest = entry.path().join("manifest.json");
        if manifest.is_file() {
            manifests.push(manifest);
        }
    }
    manifests.sort();

    if manifests.is_empty() {
        return Ok(vec![
            "generated runtime manifests requested, but no manifest.json files were found under sidecar/build/runtime".to_string(),
        ]);
    }

    let mut errors = Vec::new();
    for manifest_path in manifests {
        let text = fs::read_to_string(&manifest_path).map_err(|error| {
            format!(
                "failed to read {}: {error}",
                display_rel(root, &manifest_path)
            )
        })?;
        let value: Value = serde_json::from_str(&text).map_err(|error| {
            format!(
                "failed to parse {}: {error}",
                display_rel(root, &manifest_path)
            )
        })?;
        let actual = value
            .get("analyzerVersion")
            .and_then(Value::as_str)
            .ok_or_else(|| {
                format!(
                    "{} is missing analyzerVersion",
                    display_rel(root, &manifest_path)
                )
            })?;
        if actual != expected {
            errors.push(format!(
                "{} has analyzerVersion '{actual}', expected '{expected}'",
                display_rel(root, &manifest_path)
            ));
        }
    }

    Ok(errors)
}

fn check_equals(errors: &mut Vec<String>, path: &str, actual: String, expected: &str) {
    if actual != expected {
        errors.push(format!(
            "{path} has version '{actual}', expected '{expected}'"
        ));
    }
}

fn read_trimmed(path: PathBuf) -> Result<String, String> {
    fs::read_to_string(&path)
        .map_err(|error| {
            format!(
                "failed to read {}: {error}",
                display_rel(&repo_root().unwrap_or_default(), &path)
            )
        })
        .map(|text| text.trim().to_string())
}

fn read_lines(path: &Path) -> Result<Vec<String>, String> {
    let text = fs::read_to_string(path).map_err(|error| {
        format!(
            "failed to read {}: {error}",
            display_rel(&repo_root().unwrap_or_default(), path)
        )
    })?;
    Ok(text.lines().map(ToString::to_string).collect())
}

fn write_lines(path: PathBuf, lines: &[String]) -> Result<(), String> {
    let mut text = lines.join("\n");
    text.push('\n');
    write_file(path, &text)
}

fn write_file(path: PathBuf, content: &str) -> Result<(), String> {
    fs::write(&path, content).map_err(|error| {
        format!(
            "failed to write {}: {error}",
            display_rel(&repo_root().unwrap_or_default(), &path)
        )
    })
}

fn extract_quoted_value(line: &str) -> Option<String> {
    let start = line.find('"')? + 1;
    let end = line[start..].find('"')? + start;
    Some(line[start..end].to_string())
}

fn display_rel(root: &Path, path: &Path) -> String {
    path.strip_prefix(root)
        .map(|relative| relative.display().to_string())
        .unwrap_or_else(|_| path.display().to_string())
}
