The `kotlin-analyzer` has some bugs. Not all lsp functionality works correctly. Pick the next open issue from `plans/active/fixture-parity.md` and work on it. Pay special attention to popovers on hover. I want them to look almost identical in `my-zed` with the kotlin-analyzer, similar to the ones in `images`, or even nicer. I want you to:

1. Open the project using `ZED_LSP_TRACE=kotlin-analyzer my-zed`.
2. Open the project in IntelliJ (use a tmux session to open it from the command line).
3. Use `peekaboo` to test language server functionality like hover, rename, code actions, etc. with both, `my-zed` and IntelliJ.
4. Make sure that there is feature parity for `my-zed` with the kotlin-analyzer and IntelliJ.
5. If you find a problem, append it to the plan in `plans/active/fixture-parity.md`.
6. Work on that plan and fix the issue. Use red/green TDD.
7. Commit the fix.

IMPORTANT:
- Always run the `restart language server` via the command pallete, when opening `my-zed` in a kotlin project to make the sidecar start.
- You can use the logs from the `my-zed` binary to diagnose issues. To see language server logs and JSON-RPC messages from the kotlin-analyzer, run it with `ZED_LSP_TRACE=kotlin-analyzer my-zed`.
- Always use a subagents to run tests. The subagents should report back with a simple short message if all tests passed or, if not, which tests failed.

Complete when:
- You have gone through all of the test-fixtures.
- There is feature paritiy between th kotlin-analyzer in my-zed and IntelliJ.
- There are no warnings when building the `kotlin-analyzer`.
- The server successfully starts up and runs without errors on a kotlin project.
- All tests pass.
- You can observe the correct beahviour for all features either by using `peekaboo` when running `zed` or `my-zed` (via tmux) to take screenshots, or by observig the logs when running `ZED_LSP_TRACE=kotlin-analyzer my-zed`.
- The `kotlin-analyzer` still works in zed.
- Output: <promise>COMPLETE</promise>
