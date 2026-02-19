#!/bin/bash

export RUST_LOG=kotlin_analyzer=debug,kotlin_analyzer_server=debug

echo "Simple test to see all logs..."
echo "================================"

# Start server and capture both stdout and stderr
(
echo 'Content-Length: 214

{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":"file:///tmp","capabilities":{"textDocument":{"hover":{"dynamicRegistration":false},"completion":{"dynamicRegistration":false}}}}}'

sleep 2

echo 'Content-Length: 52

{"jsonrpc":"2.0","method":"initialized","params":{}}'

sleep 8

) | ./server/target/debug/kotlin-analyzer 2>&1 | grep -v "Checking" | head -50
