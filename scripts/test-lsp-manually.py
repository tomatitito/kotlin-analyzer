#!/usr/bin/env python3
"""
Manual LSP test script to verify hover/completion/diagnostics functionality.
Uses real test fixture files from the project's test directory.
"""

import json
import subprocess
import sys
import time
import os
import select
import fcntl
from typing import Any, Dict, Optional, List

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURE_DIR = os.path.join(PROJECT_ROOT, "tests", "fixtures", "kotlin")

class LSPClient:
    def __init__(self, server_path: str):
        self.process = subprocess.Popen(
            [server_path, "--log-level", "debug"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=False
        )
        self.request_id = 0

        # Make stderr non-blocking for reading logs
        fd = self.process.stderr.fileno()
        flags = fcntl.fcntl(fd, fcntl.F_GETFL)
        fcntl.fcntl(fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)

    def send_request(self, method: str, params: Dict[str, Any]) -> Dict[str, Any]:
        self.request_id += 1
        request_id = self.request_id
        request = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
            "params": params
        }

        message = json.dumps(request)
        content_length = len(message.encode('utf-8'))
        header = f"Content-Length: {content_length}\r\n\r\n"

        full_message = header + message
        print(f"\n→ Sending {method} (id={request_id})")

        self.process.stdin.write(full_message.encode('utf-8'))
        self.process.stdin.flush()

        # Read responses until we get our response (skip notifications)
        while True:
            msg = self._read_message()
            if msg is None:
                return {}
            # Notifications don't have an id
            if "id" in msg and msg["id"] == request_id:
                return msg
            else:
                # It's a notification or response to another request
                method_name = msg.get("method", "unknown")
                if "method" in msg:
                    print(f"  [notification: {method_name}]")

    def _read_message(self) -> Optional[Dict]:
        """Read a single JSON-RPC message from stdout."""
        headers = {}
        while True:
            line = self.process.stdout.readline().decode('utf-8').strip()
            if not line:
                break
            if ':' in line:
                key, value = line.split(':', 1)
                headers[key.strip()] = value.strip()

        content_length = int(headers.get('Content-Length', 0))
        if content_length > 0:
            response_bytes = self.process.stdout.read(content_length)
            return json.loads(response_bytes.decode('utf-8'))

        return None

    def send_notification(self, method: str, params: Dict[str, Any]):
        notification = {
            "jsonrpc": "2.0",
            "method": method,
            "params": params
        }

        message = json.dumps(notification)
        content_length = len(message.encode('utf-8'))
        header = f"Content-Length: {content_length}\r\n\r\n"

        full_message = header + message
        print(f"→ Sending notification {method}")

        self.process.stdin.write(full_message.encode('utf-8'))
        self.process.stdin.flush()

    def read_stderr(self) -> List[str]:
        """Read all available stderr output (non-blocking)."""
        lines = []
        while True:
            ready, _, _ = select.select([self.process.stderr], [], [], 0)
            if ready:
                line = self.process.stderr.readline()
                if line:
                    lines.append(line.decode('utf-8').rstrip())
                else:
                    break
            else:
                break
        return lines

    def close(self):
        if self.process:
            self.process.terminate()
            self.process.wait()

def print_stderr(client: LSPClient, label: str = ""):
    """Print recent stderr output from the server."""
    lines = client.read_stderr()
    if lines:
        if label:
            print(f"\n  [{label} - stderr]:")
        for line in lines[-20:]:
            print(f"    {line}")

def main():
    print("=== Kotlin Analyzer LSP Manual Test ===")
    print(f"Project root: {PROJECT_ROOT}")
    print(f"Fixture dir: {FIXTURE_DIR}\n")

    # Use the fixture directory as project root
    fixture_root = os.path.join(PROJECT_ROOT, "tests", "fixtures", "kotlin")

    # Read the test file
    test_file = os.path.join(fixture_root, "correct", "ClassHierarchy.kt")
    with open(test_file) as f:
        test_code = f.read()

    test_uri = f"file://{test_file}"
    root_uri = f"file://{fixture_root}"

    print(f"Test file: {test_file}")
    print(f"Test URI: {test_uri}")
    print(f"Root URI: {root_uri}\n")

    # Start the server
    server_path = os.path.join(PROJECT_ROOT, "server", "target", "debug", "kotlin-analyzer")
    client = LSPClient(server_path)

    results = {}

    try:
        # 1. Initialize
        print("=" * 60)
        print("1. INITIALIZE")
        print("=" * 60)
        init_response = client.send_request("initialize", {
            "processId": os.getpid(),
            "rootUri": root_uri,
            "capabilities": {
                "textDocument": {
                    "hover": {"dynamicRegistration": False},
                    "completion": {"dynamicRegistration": False},
                    "definition": {"dynamicRegistration": False},
                    "rename": {"dynamicRegistration": False},
                },
                "window": {
                    "workDoneProgress": True
                }
            }
        })

        if "result" in init_response:
            caps = init_response["result"].get("capabilities", {})
            print(f"  Hover: {caps.get('hoverProvider', False)}")
            print(f"  Completion: {bool(caps.get('completionProvider'))}")
            print(f"  Definition: {caps.get('definitionProvider', False)}")
            print(f"  Rename: {bool(caps.get('renameProvider'))}")

        # 2. Initialized
        print("\n" + "=" * 60)
        print("2. INITIALIZED (triggers sidecar startup)")
        print("=" * 60)
        client.send_notification("initialized", {})

        # Wait for sidecar to start (it runs in background)
        print("  Waiting for sidecar to start...")
        for i in range(20):
            time.sleep(1)
            print_stderr(client, f"sidecar startup {i+1}s")
            # Check if the sidecar has started by looking for "Ready" in stderr

        # 3. Open document
        print("\n" + "=" * 60)
        print("3. OPEN DOCUMENT")
        print("=" * 60)
        client.send_notification("textDocument/didOpen", {
            "textDocument": {
                "uri": test_uri,
                "languageId": "kotlin",
                "version": 1,
                "text": test_code
            }
        })

        # Wait for analysis
        time.sleep(3)
        print_stderr(client, "after didOpen")

        # 4. Hover over "Circle" class name (line 23, col 6)
        print("\n" + "=" * 60)
        print("4. HOVER over 'Circle' class (line 23, col 6)")
        print("=" * 60)
        hover_response = client.send_request("textDocument/hover", {
            "textDocument": {"uri": test_uri},
            "position": {"line": 22, "character": 6}  # 0-indexed: line 23
        })

        if "result" in hover_response and hover_response["result"]:
            result = hover_response["result"]
            if isinstance(result, dict) and "contents" in result:
                print(f"  ✅ HOVER WORKS!")
                contents = result["contents"]
                if isinstance(contents, dict):
                    print(f"  Content: {contents.get('value', contents)}")
                else:
                    print(f"  Content: {contents}")
                results["hover"] = "PASS"
            else:
                print(f"  ✅ HOVER WORKS! Result: {json.dumps(result, indent=4)}")
                results["hover"] = "PASS"
        else:
            print(f"  ❌ HOVER returned null/empty")
            print(f"  Full response: {json.dumps(hover_response, indent=4)}")
            results["hover"] = "FAIL"

        print_stderr(client, "after hover")

        # 5. Completion after "shape." (line 55)
        print("\n" + "=" * 60)
        print("5. COMPLETION after 'shape.' (line 55, col 23)")
        print("=" * 60)
        completion_response = client.send_request("textDocument/completion", {
            "textDocument": {"uri": test_uri},
            "position": {"line": 54, "character": 23}  # After "shape."
        })

        if "result" in completion_response:
            result = completion_response["result"]
            items = []
            if isinstance(result, dict):
                items = result.get("items", [])
            elif isinstance(result, list):
                items = result

            if items:
                print(f"  ✅ COMPLETION WORKS! Found {len(items)} items")
                for item in items[:5]:
                    label = item.get("label", "?")
                    kind = item.get("kind", "?")
                    detail = item.get("detail", "")
                    print(f"    - {label} (kind={kind}) {detail}")
                results["completion"] = "PASS"
            else:
                print(f"  ❌ COMPLETION returned empty")
                results["completion"] = "FAIL"
        else:
            print(f"  ❌ COMPLETION failed")
            results["completion"] = "FAIL"

        print_stderr(client, "after completion")

        # 6. Go to definition of "Shape" reference (line 23, col 36 - extends Shape)
        print("\n" + "=" * 60)
        print("6. GO TO DEFINITION of 'Shape' (line 23, col 36)")
        print("=" * 60)

        # class Circle(val radius: Double) : Shape("Circle"), Resizable {
        #                                    ^36
        definition_response = client.send_request("textDocument/definition", {
            "textDocument": {"uri": test_uri},
            "position": {"line": 22, "character": 36}  # "Shape" reference
        })

        if "result" in definition_response and definition_response["result"]:
            result = definition_response["result"]
            if isinstance(result, list) and len(result) > 0:
                print(f"  ✅ DEFINITION WORKS! Found {len(result)} location(s)")
                for loc in result:
                    print(f"    - {loc}")
                results["definition"] = "PASS"
            else:
                print(f"  Result: {json.dumps(result, indent=4)}")
                results["definition"] = "PASS" if result else "FAIL"
        else:
            print(f"  ❌ DEFINITION returned null/empty")
            results["definition"] = "FAIL"

        print_stderr(client, "after definition")

        # 7. Diagnostics test - open a file with errors
        print("\n" + "=" * 60)
        print("7. DIAGNOSTICS test - open TypeMismatch.kt")
        print("=" * 60)

        error_file = os.path.join(fixture_root, "errors", "TypeMismatch.kt")
        with open(error_file) as f:
            error_code = f.read()

        error_uri = f"file://{error_file}"
        client.send_notification("textDocument/didOpen", {
            "textDocument": {
                "uri": error_uri,
                "languageId": "kotlin",
                "version": 1,
                "text": error_code
            }
        })

        # Wait for diagnostics
        time.sleep(5)
        print_stderr(client, "after error file open")

        # The diagnostics come as notifications - we'll check stderr for them
        results["diagnostics"] = "CHECK_STDERR"

        # Print summary
        print("\n" + "=" * 60)
        print("SUMMARY")
        print("=" * 60)
        for feature, status in results.items():
            icon = "✅" if status == "PASS" else "❌" if status == "FAIL" else "⚠️"
            print(f"  {icon} {feature}: {status}")

        # Final stderr dump
        print("\n" + "=" * 60)
        print("FINAL STDERR (last 30 lines)")
        print("=" * 60)
        time.sleep(1)
        lines = client.read_stderr()
        for line in lines[-30:]:
            print(f"  {line}")

    finally:
        client.close()

    print("\n" + "=" * 60)
    print("Test complete!")

if __name__ == "__main__":
    # First build the server
    print("Building kotlin-analyzer...")
    result = subprocess.run(
        ["cargo", "build"],
        cwd=os.path.join(PROJECT_ROOT, "server"),
        capture_output=True
    )
    if result.returncode != 0:
        print("Failed to build kotlin-analyzer")
        print(result.stderr.decode('utf-8'))
        sys.exit(1)
    print("Build successful.\n")

    main()
