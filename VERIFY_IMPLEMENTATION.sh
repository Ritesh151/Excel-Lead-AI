#!/bin/bash
# VERIFY_IMPLEMENTATION.sh — Verify all networking components are in place

set -e

echo "════════════════════════════════════════════════════════════════"
echo "🔍 Verifying LAN Networking Implementation"
echo "════════════════════════════════════════════════════════════════"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

pass_count=0
fail_count=0

# Helper function
check_file() {
    local file=$1
    local name=$2
    
    if [ -f "$file" ]; then
        echo -e "${GREEN}✅${NC} $name"
        ((pass_count++))
    else
        echo -e "${RED}❌${NC} $name NOT FOUND: $file"
        ((fail_count++))
    fi
}

check_dir() {
    local dir=$1
    local name=$2
    
    if [ -d "$dir" ]; then
        echo -e "${GREEN}✅${NC} $name"
        ((pass_count++))
    else
        echo -e "${RED}❌${NC} $name NOT FOUND: $dir"
        ((fail_count++))
    fi
}

check_content() {
    local file=$1
    local pattern=$2
    local name=$3
    
    if grep -q "$pattern" "$file" 2>/dev/null; then
        echo -e "${GREEN}✅${NC} $name"
        ((pass_count++))
    else
        echo -e "${RED}❌${NC} $name NOT FOUND in $file"
        ((fail_count++))
    fi
}

echo "📦 CHECKING ANDROID NETWORKING FILES"
echo ""

# Networking stack files
check_file "frontend-kotlin/src/main/java/com/optimatrix/gsmcall/networking/NetworkingInitializer.kt" "NetworkingInitializer.kt"
check_file "frontend-kotlin/src/main/java/com/optimatrix/gsmcall/networking/SocketManagerProduction.kt" "SocketManagerProduction.kt"
check_file "frontend-kotlin/src/main/java/com/optimatrix/gsmcall/networking/NetworkDiagnosticsValidator.kt" "NetworkDiagnosticsValidator.kt"
check_file "frontend-kotlin/src/main/java/com/optimatrix/gsmcall/networking/ApiClientProduction.kt" "ApiClientProduction.kt"
check_file "frontend-kotlin/src/main/java/com/optimatrix/gsmcall/networking/NetworkConfigManager.kt" "NetworkConfigManager.kt"

echo ""
echo "🔧 CHECKING ANDROID CONFIGURATION"
echo ""

check_file "frontend-kotlin/res/xml/network_security_config.xml" "network_security_config.xml"
check_file "frontend-kotlin/local.properties" "local.properties"

echo ""
echo "📝 CHECKING MAINVIEWMODEL INTEGRATION"
echo ""

check_content "frontend-kotlin/src/main/java/com/optimatrix/gsmcall/ui/MainViewModel.kt" "NetworkingInitializer" "MainViewModel imports NetworkingInitializer"
check_content "frontend-kotlin/src/main/java/com/optimatrix/gsmcall/ui/MainViewModel.kt" "initializeNetworking" "MainViewModel has initializeNetworking()"
check_content "frontend-kotlin/src/main/java/com/optimatrix/gsmcall/ui/MainViewModel.kt" "handleWebSocketStateChange" "MainViewModel handles WebSocket state changes"
check_content "frontend-kotlin/src/main/java/com/optimatrix/gsmcall/ui/MainViewModel.kt" "networking.runDiagnostics" "MainViewModel runs diagnostics on campaign start"
check_content "frontend-kotlin/src/main/java/com/optimatrix/gsmcall/ui/MainViewModel.kt" "networking.shutdown" "MainViewModel shuts down networking on destroy"

echo ""
echo "🚀 CHECKING BACKEND CONFIGURATION"
echo ""

check_content "backend-node/src/index.js" "0\.0\.0\.0" "Backend binds to 0.0.0.0"
check_content "backend-node/src/index.js" "listen.*3000" "Backend listens on port 3000"
check_content "backend-node/src/index.js" "/health" "Backend has /health endpoint"
check_content "backend-node/src/index.js" "/api/debug" "Backend has debug endpoints"

check_content "backend-node/src/socket/WebSocketServer.js" "pingInterval.*25" "WebSocket has 25s heartbeat"

echo ""
echo "📚 CHECKING DOCUMENTATION"
echo ""

check_file "MAINVIEWMODEL_INTEGRATION_COMPLETE.md" "MAINVIEWMODEL_INTEGRATION_COMPLETE.md"
check_file "IMPLEMENTATION_STATUS.md" "IMPLEMENTATION_STATUS.md"
check_file "READY_TO_TEST.md" "READY_TO_TEST.md"
check_file "COMPLETION_SUMMARY.md" "COMPLETION_SUMMARY.md"
check_file "TEST_ENDPOINTS.sh" "TEST_ENDPOINTS.sh"

echo ""
echo "════════════════════════════════════════════════════════════════"
echo ""

if [ $fail_count -eq 0 ]; then
    echo -e "${GREEN}✅ ALL CHECKS PASSED ($pass_count/$pass_count)${NC}"
    echo ""
    echo "Implementation is complete and ready for testing."
    echo ""
    echo "Next steps:"
    echo "  1. Start backend: cd backend-node && npm start"
    echo "  2. Build Android: cd frontend-kotlin && ./gradlew installDebug"
    echo "  3. Run tests: Follow READY_TO_TEST.md"
    echo ""
    exit 0
else
    echo -e "${RED}❌ CHECKS FAILED${NC}"
    echo "Passed: $pass_count"
    echo "Failed: $fail_count"
    echo ""
    echo "Please verify all files are in place and try again."
    exit 1
fi
