// Embed the git commit hash that built this binary so that runtime
// observability (the completion-metrics startup line, .sensors/log-analysis)
// can pin a measurement to the exact source revision that produced it.
//
// We expose two env vars to the compiled crate:
//   GIT_COMMIT_HASH  — short (12-char) abbreviated SHA, or "unknown" if git
//                      isn't usable (e.g. building from a tarball).
//   GIT_DIRTY        — "true" if `git status --porcelain` reports any
//                      tracked modifications at build time, else "false".
//
// Rebuild triggers: HEAD ref + index, so a new commit or staged change
// busts the build cache and re-stamps the binary. Untracked files don't
// trigger a rebuild but DO contribute to the dirty flag if they match
// `git status` output (since a fresh build picks up the current state
// regardless).

use std::path::PathBuf;
use std::process::Command;

fn main() {
    let (commit, dirty) = git_info().unwrap_or_else(|| ("unknown".into(), false));
    println!("cargo:rustc-env=GIT_COMMIT_HASH={}", commit);
    println!("cargo:rustc-env=GIT_DIRTY={}", dirty);

    // Re-run if HEAD moves or the index changes. Best-effort: if these
    // files don't exist we just re-stamp on every build, which is fine.
    if let Some(repo_root) = repo_root() {
        let head = repo_root.join(".git").join("HEAD");
        let index = repo_root.join(".git").join("index");
        if head.exists() {
            println!("cargo:rerun-if-changed={}", head.display());
        }
        if index.exists() {
            println!("cargo:rerun-if-changed={}", index.display());
        }
    }
}

fn git_info() -> Option<(String, bool)> {
    let commit = run_git(&["rev-parse", "--short=12", "HEAD"])?;
    let porcelain = run_git(&["status", "--porcelain"])?;
    let dirty = !porcelain.trim().is_empty();
    Some((commit, dirty))
}

fn run_git(args: &[&str]) -> Option<String> {
    let output = Command::new("git").args(args).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let s = String::from_utf8(output.stdout).ok()?;
    Some(s.trim().to_string())
}

fn repo_root() -> Option<PathBuf> {
    let s = run_git(&["rev-parse", "--show-toplevel"])?;
    Some(PathBuf::from(s))
}
