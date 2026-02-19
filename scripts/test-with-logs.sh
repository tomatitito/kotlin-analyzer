#!/bin/bash

# Run the kotlin-analyzer with debug logging to see what's happening

echo "Starting kotlin-analyzer with debug logging..."
echo "=========================================="

# Set environment variables for logging
export RUST_LOG=kotlin_analyzer=debug,tower_lsp=debug
export RUST_BACKTRACE=1

# Create a test file
cat > /tmp/test.kt << 'EOF'
class TestClass {
    fun hello(): String {
        return "Hello, World!"
    }
}

fun main() {
    val test = TestClass()
    println(test.hello())
}
EOF

# Start the server and interact with it
(
echo 'Content-Length: 205

{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":"file:///tmp","capabilities":{"textDocument":{"hover":{"dynamicRegistration":false},"completion":{"dynamicRegistration":false}}}}}'

sleep 2

echo 'Content-Length: 52

{"jsonrpc":"2.0","method":"initialized","params":{}}'

sleep 2

echo 'Content-Length: 172

{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///tmp/test.kt","languageId":"kotlin","version":1,"text":"class TestClass {}\n"}}}'

sleep 3

echo 'Content-Length: 143

{"jsonrpc":"2.0","id":2,"method":"textDocument/hover","params":{"textDocument":{"uri":"file:///tmp/test.kt"},"position":{"line":0,"character":6}}}'

sleep 5

) | ./server/target/debug/kotlin-analyzer 2>&1 | head -200
