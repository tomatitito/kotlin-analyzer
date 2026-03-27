#!/usr/bin/env python3
"""
Fallback diagnostics verifier for TOM-24.

Runs direct LSP checks against fixture files when my-zed visual checks are unavailable.
Requires a prebuilt kotlin-analyzer binary.
"""

from __future__ import annotations

import argparse
import json
import select
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests" / "fixtures" / "kotlin"
EVIDENCE_DIR = ROOT / "plans" / "active" / "evidence"
EXPECTED = {
    "without_extra_flags": {
        "compiler-flags/ContextParameters.kt": {"diagnostic_count": 4, "errors": 4, "warnings": 0},
        "compiler-flags/ContextReceivers.kt": {"diagnostic_count": 6, "errors": 5, "warnings": 1},
        "compiler-flags/MultiDollarInterpolation.kt": {"diagnostic_count": 1, "errors": 1, "warnings": 0},
    },
    "with_required_flags": {
        "compiler-flags/ContextParameters.kt": {"diagnostic_count": 3, "errors": 3, "warnings": 0},
        "compiler-flags/ContextReceivers.kt": {"diagnostic_count": 5, "errors": 5, "warnings": 0},
        "compiler-flags/MultiDollarInterpolation.kt": {"diagnostic_count": 0, "errors": 0, "warnings": 0},
    },
}


class LspClient:
    def __init__(self, server_bin: Path) -> None:
        self.proc = subprocess.Popen(  # noqa: S603
            [str(server_bin), "--log-level", "debug"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=False,
        )
        self.request_id = 0

    def _send(self, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
        assert self.proc.stdin is not None
        self.proc.stdin.write(header + body)
        self.proc.stdin.flush()

    def notify(self, method: str, params: dict[str, Any]) -> None:
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def request(self, method: str, params: dict[str, Any], timeout_s: float = 30.0) -> dict[str, Any]:
        self.request_id += 1
        request_id = self.request_id
        self._send(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "method": method,
                "params": params,
            }
        )

        deadline = time.time() + timeout_s
        while time.time() < deadline:
            msg = self.read_message(timeout_s=0.5)
            if msg and msg.get("id") == request_id:
                return msg
        raise TimeoutError(f"request timed out: {method}")

    def read_message(self, timeout_s: float = 0.2) -> dict[str, Any] | None:
        assert self.proc.stdout is not None
        if not select.select([self.proc.stdout], [], [], timeout_s)[0]:
            return None

        headers: dict[str, str] = {}
        while True:
            line = self.proc.stdout.readline()
            if not line:
                return None
            line_str = line.decode("utf-8").strip()
            if line_str == "":
                break
            if ":" in line_str:
                key, val = line_str.split(":", 1)
                headers[key.strip().lower()] = val.strip()

        content_length = int(headers.get("content-length", "0"))
        if content_length <= 0:
            return None

        payload = self.proc.stdout.read(content_length)
        return json.loads(payload.decode("utf-8"))

    def collect_messages(self, seconds: float) -> list[dict[str, Any]]:
        out: list[dict[str, Any]] = []
        deadline = time.time() + seconds
        while time.time() < deadline:
            msg = self.read_message(timeout_s=0.25)
            if msg:
                out.append(msg)
        return out

    def collect_diagnostics_for_uri(self, uri: str, timeout_s: float = 12.0) -> list[dict[str, Any]]:
        """
        Wait for publishDiagnostics notifications for one URI.

        Returns the last diagnostics list seen for that URI (LSP semantics are replace-by-URI).
        """
        deadline = time.time() + timeout_s
        saw_uri_publish = False
        last_for_uri: list[dict[str, Any]] = []
        quiet_since = time.time()

        while time.time() < deadline:
            msg = self.read_message(timeout_s=0.25)
            if not msg:
                # After at least one uri-specific publish, consider stream stable after
                # a short quiet window so we can move to the next file.
                if saw_uri_publish and (time.time() - quiet_since) >= 0.75:
                    break
                continue

            quiet_since = time.time()
            if msg.get("method") != "textDocument/publishDiagnostics":
                continue

            params = msg.get("params", {})
            if params.get("uri") != uri:
                continue

            saw_uri_publish = True
            last_for_uri = params.get("diagnostics", [])

        return last_for_uri

    def close(self) -> None:
        try:
            self.proc.terminate()
            self.proc.wait(timeout=3)
        except Exception:
            self.proc.kill()


def did_open_params(path: Path) -> dict[str, Any]:
    return {
        "textDocument": {
            "uri": f"file://{path}",
            "languageId": "kotlin",
            "version": 1,
            "text": path.read_text(encoding="utf-8"),
        }
    }


def summarize(rel_path: str, diagnostics: list[dict[str, Any]]) -> dict[str, Any]:
    errors = sum(1 for d in diagnostics if d.get("severity") == 1)
    warnings = sum(1 for d in diagnostics if d.get("severity") == 2)
    messages = [d.get("message", "") for d in diagnostics[:8]]
    return {
        "file": rel_path,
        "diagnostic_count": len(diagnostics),
        "errors": errors,
        "warnings": warnings,
        "messages": messages,
    }


def compare_expected(session_name: str, results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    expected_by_file = EXPECTED.get(session_name, {})
    mismatches: list[dict[str, Any]] = []
    for row in results:
        target = expected_by_file.get(row["file"])
        if target is None:
            continue
        for key, expected in target.items():
            actual = row[key]
            if actual != expected:
                mismatches.append(
                    {"file": row["file"], "field": key, "expected": expected, "actual": actual}
                )
    return mismatches


def run_session(server_bin: Path, compiler_flags: list[str], rel_paths: list[str]) -> dict[str, Any]:
    client = LspClient(server_bin)
    try:
        init = client.request(
            "initialize",
            {
                "processId": None,
                "rootUri": f"file://{FIXTURES}",
                "capabilities": {},
                "initializationOptions": {"compilerFlags": compiler_flags},
            },
        )
        client.notify("initialized", {})
        time.sleep(5)

        results: list[dict[str, Any]] = []
        for rel in rel_paths:
            path = FIXTURES / rel
            uri = f"file://{path}"
            client.notify("textDocument/didOpen", did_open_params(path))
            diagnostics = client.collect_diagnostics_for_uri(uri, timeout_s=12.0)
            results.append(summarize(rel, diagnostics))
            client.notify("textDocument/didClose", {"textDocument": {"uri": uri}})
            client.collect_messages(seconds=1)

        return {
            "compilerFlags": compiler_flags,
            "initialize_ok": "result" in init,
            "results": results,
        }
    finally:
        client.close()


def choose_default_server() -> Path:
    candidates = [
        ROOT / "server" / "target" / "debug" / "kotlin-analyzer",
        Path.cwd() / "server" / "target" / "debug" / "kotlin-analyzer",
        Path.home() / ".local" / "bin" / "kotlin-analyzer",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return ROOT / "server" / "target" / "debug" / "kotlin-analyzer"


def main() -> int:
    parser = argparse.ArgumentParser(description="Fallback TOM-24 diagnostics verifier")
    parser.add_argument(
        "--server",
        type=Path,
        default=choose_default_server(),
        help="Path to prebuilt kotlin-analyzer binary",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Output JSON path (default: plans/active/evidence/tom-24-fallback-lsp-<timestamp>.json)",
    )
    args = parser.parse_args()

    if not args.server.exists():
        print(f"server binary not found: {args.server}", file=sys.stderr)
        return 1

    timestamp = time.strftime("%Y-%m-%d-%H%M%S")
    output = args.output or (EVIDENCE_DIR / f"tom-24-fallback-lsp-{timestamp}.json")
    output.parent.mkdir(parents=True, exist_ok=True)

    files = [
        "errors/TypeMismatch.kt",
        "warnings/UnusedVariable.kt",
        "compiler-flags/ContextParameters.kt",
        "compiler-flags/ContextReceivers.kt",
        "compiler-flags/MultiDollarInterpolation.kt",
    ]
    report = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "server": str(args.server),
        "workspace": str(ROOT),
        "sessions": {
            "without_extra_flags": run_session(args.server, [], files),
            "with_required_flags": run_session(
                args.server,
                [
                    "-Xcontext-parameters",
                    "-Xcontext-receivers",
                    "-Xmulti-dollar-interpolation",
                ],
                files,
            ),
        },
        "expected_mismatches": {},
    }

    for session_name, session in report["sessions"].items():
        report["expected_mismatches"][session_name] = compare_expected(
            session_name,
            session["results"],
        )

    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"wrote {output}")
    mismatch_found = any(report["expected_mismatches"].values())
    for session_name, session in report["sessions"].items():
        print(f"\n== {session_name} ==")
        for row in session["results"]:
            print(
                f"{row['file']}: count={row['diagnostic_count']} errors={row['errors']} warnings={row['warnings']}"
            )
            if row["messages"]:
                print(f"  first: {row['messages'][0]}")
    if mismatch_found:
        print(
            "\n[EXPECTED_MISMATCH] Context fixture diagnostics diverge from this ticket's pinned expectations.",
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
