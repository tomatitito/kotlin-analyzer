#!/bin/bash

export RUST_LOG=kotlin_analyzer=trace,kotlin_analyzer_server=trace

echo "Detailed trace test..."
echo "======================"

# Python script for better control
python3 - << 'EOF'
import subprocess
import json
import time
import sys

def send_request(proc, method, params, msg_id=None):
    msg = {"jsonrpc": "2.0", "method": method}
    if msg_id is not None:
        msg["id"] = msg_id
    if params is not None:
        msg["params"] = params

    content = json.dumps(msg)
    header = f"Content-Length: {len(content)}\r\n\r\n"
    full = header + content

    print(f"→ {method}", file=sys.stderr)
    proc.stdin.write(full.encode())
    proc.stdin.flush()

# Start server
proc = subprocess.Popen(
    ["./server/target/debug/kotlin-analyzer"],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE
)

time.sleep(0.5)

# Send initialize
send_request(proc, "initialize", {
    "processId": None,
    "rootUri": "file:///tmp/test-project",
    "capabilities": {}
}, msg_id=1)

time.sleep(2)

# Send initialized
send_request(proc, "initialized", {})

# Wait and collect logs
time.sleep(8)

proc.terminate()
time.sleep(0.5)

# Print stderr (logs)
stderr = proc.stderr.read().decode('utf-8')
for line in stderr.split('\n'):
    if any(word in line.lower() for word in ['bridge', 'sidecar', 'spawn', 'initialized', 'starting', 'ready', 'callback']):
        print(line)
EOF
