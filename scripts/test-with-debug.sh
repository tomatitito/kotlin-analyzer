#!/bin/bash

# Run a simple test with debug logging to see what's happening

export RUST_LOG=kotlin_analyzer=debug,kotlin_analyzer_server=debug
export RUST_BACKTRACE=1

echo "Testing with debug logging..."
echo "=============================="

# Build first
echo "Building server..."
(cd server && cargo build 2>/dev/null)

echo ""
echo "Starting test..."

# Use a simple Python script to interact with the server
python3 - << 'EOF'
import subprocess
import json
import time
import sys

def send_message(process, method, params=None, msg_id=None):
    msg = {"jsonrpc": "2.0"}
    if msg_id is not None:
        msg["id"] = msg_id
    msg["method"] = method
    if params is not None:
        msg["params"] = params

    content = json.dumps(msg)
    header = f"Content-Length: {len(content)}\r\n\r\n"
    full = header + content

    print(f"→ Sending: {method}", file=sys.stderr)
    process.stdin.write(full.encode())
    process.stdin.flush()

# Start the server
print("Starting server...", file=sys.stderr)
proc = subprocess.Popen(
    ["./server/target/debug/kotlin-analyzer"],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=False
)

time.sleep(0.5)

# Send initialize
print("\n1. Sending initialize request...", file=sys.stderr)
send_message(proc, "initialize", {
    "processId": None,
    "rootUri": "file:///tmp",
    "capabilities": {}
}, msg_id=1)

time.sleep(2)

# Send initialized
print("\n2. Sending initialized notification...", file=sys.stderr)
send_message(proc, "initialized", {})

time.sleep(3)

# Try to send hover
print("\n3. Sending hover request...", file=sys.stderr)
send_message(proc, "textDocument/hover", {
    "textDocument": {"uri": "file:///tmp/test.kt"},
    "position": {"line": 0, "character": 0}
}, msg_id=2)

time.sleep(2)

# Check stderr for logs
print("\n=== STDERR LOGS ===", file=sys.stderr)
proc.terminate()
time.sleep(0.5)

stderr = proc.stderr.read().decode('utf-8')
# Print the important log lines
for line in stderr.split('\n'):
    if 'sidecar' in line.lower() or 'ready' in line.lower() or 'state' in line.lower() or 'Request' in line:
        print(line, file=sys.stderr)

EOF
