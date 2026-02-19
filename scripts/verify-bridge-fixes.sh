#!/bin/bash
# Verification script for bridge channel fixes
# This script checks that the fixes described in plans/active/fix-bridge-channel.md
# are actually present in the code

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

echo "Verifying bridge channel fixes..."
echo "================================"

# Counter for failures
FAILURES=0

# Bug 1: Check that request_tx is wrapped in Mutex
echo -n "Bug 1 - Checking request_tx is wrapped in Mutex... "
if grep -q "request_tx: Mutex<mpsc::Sender<Request>>" server/src/bridge.rs; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗ request_tx not wrapped in Mutex${NC}"
    ((FAILURES++))
fi

# Bug 1: Check that request_tx is updated in start()
echo -n "Bug 1 - Checking request_tx is updated in start()... "
if grep -A2 "let mut current_tx = self.request_tx.lock" server/src/bridge.rs | grep -q "\*current_tx = tx"; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗ request_tx not updated in start()${NC}"
    ((FAILURES++))
fi

# Bug 2: Check that child field exists
echo -n "Bug 2 - Checking child process field exists... "
if grep -q "child: Mutex<Option<tokio::process::Child>>" server/src/bridge.rs; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗ child field not found${NC}"
    ((FAILURES++))
fi

# Bug 2: Check that child is stored after spawn
echo -n "Bug 2 - Checking child process is stored... "
if grep -A2 "let mut child_slot = self.child.lock" server/src/bridge.rs | grep -q "\*child_slot = Some(child)"; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗ child not stored after spawn${NC}"
    ((FAILURES++))
fi

# Bug 3: Check for tokio::spawn in initialized handler
echo -n "Bug 3 - Checking initialized handler uses tokio::spawn... "
if grep -A15 "async fn initialized" server/src/server.rs | grep -q "tokio::spawn"; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗ initialized handler not using tokio::spawn${NC}"
    ((FAILURES++))
fi

# Bug 4: Check for canonicalize in find_sidecar_jar
echo -n "Bug 4 - Checking canonicalize for symlinks... "
if grep -A10 "fn find_sidecar_jar" server/src/server.rs | grep -q "canonicalize"; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗ canonicalize not found in find_sidecar_jar${NC}"
    ((FAILURES++))
fi

# Bug 5: Check that --add-opens comes before -jar
echo -n "Bug 5 - Checking JVM flag order (--add-opens before -jar)... "
# Extract the command building section and check order
COMMAND_SECTION=$(sed -n '/Command::new.*java_path/,/\.spawn()/p' server/src/bridge.rs)
ADD_OPENS_LINE=$(echo "$COMMAND_SECTION" | grep -n "\.arg(\"--add-opens\")" | head -1 | cut -d: -f1)
JAR_LINE=$(echo "$COMMAND_SECTION" | grep -n "\.arg(\"-jar\")" | cut -d: -f1)

if [ -n "$ADD_OPENS_LINE" ] && [ -n "$JAR_LINE" ] && [ "$ADD_OPENS_LINE" -lt "$JAR_LINE" ]; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${RED}✗ --add-opens not before -jar${NC}"
    ((FAILURES++))
fi

echo "================================"

if [ $FAILURES -eq 0 ]; then
    echo -e "${GREEN}All bridge fixes verified!${NC}"
    exit 0
else
    echo -e "${RED}$FAILURES issues found. The fixes may not be fully implemented.${NC}"
    exit 1
fi
