# GitHub CLI (`gh`)

Use `gh` for all interactions with the remote GitHub repository. Do not use raw `git push`, `curl` to the GitHub API, or the web UI for operations that `gh` supports.

## Common operations

```bash
gh pr create --title "..." --body "..."    # open a pull request
gh pr list                                 # list open PRs
gh pr view 42                              # view PR details
gh pr merge 42                             # merge a PR

gh release create v0.1.0 *.tar.gz          # create a release with assets
gh release list                            # list releases
gh release view v0.1.0                     # view release details

gh issue create --title "..." --body "..."  # file an issue
gh issue list                              # list open issues
gh issue view 17                           # view issue details

gh run list                                # list CI workflow runs
gh run view <run-id>                       # view a specific run
gh run watch <run-id>                      # live-tail a running workflow

gh repo view                               # show repo metadata
gh api repos/{owner}/{repo}/...            # arbitrary GitHub API calls
```

## When to use

- Creating and managing pull requests, issues, and releases
- Checking CI status and workflow run logs
- Uploading release artifacts
- Any GitHub API interaction (prefer `gh api` over raw `curl`)
