Run `tk query 'select(.parent == "ka-ixtv")'` to get a list of issues that belong to this epic. Pick the issue with the highest priority that does not have any dependencies (the `deps` field is an empty array) and that this is ready for implementation. Set this issue to `in progress`. Use red/green TDD. After you have successfully implemented the issue and all tests pass, mark it as closed, pick the next open issue without dependencies and implement it. Use the `trace-lsp` skill to check.

Complete when:
- There are no more issues left that are marked as `ready`.
- There are no warnings when building the `kotlin-analyzer`.
- The server successfully starts up and runs without errors on a kotlin project.
- All tests pass.
- Output: <promise>COMPLETE</promise>
