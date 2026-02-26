In addition to what's in the active plans, the `kotlin-analyzer` has some bugs. Not all lsp functionality works correctly. There are still active plans in the `plans/active` directory. Also, when opening a kotlin project, I get an error saying that the server shut down. Fix these errors, use the logs from the `my-zed` binary to diagnose issues. After fixing an issue, move it to the `plans/completed` directory.

Complete when:
- The server successfully starts up and runs without errors on a kotlin project.
- All active plans have been completed.
- All tests still pass.
- There are no warnings when building the `kotlin-analyzer`.
- The `kotlin-analyzer` still works in zed.
- There are no warnings or errors from `kotlin-analyzer` when opening a kotlin file in zed.
- Output: <promise>COMPLETE</promise>
