#!/bin/bash
# ════════════════════════════════════════════════════════════════════════════
#  VERIFY_NETWORKING.sh — Test LAN networking setup end-to-end
# ════════════════════════════════════════════════════════════════════════════

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  LAN NETWORKING VERIFICATION${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════${NC}"
echo ""

# Check 1: Backend running
echo -e "${BLUE}[1/7] Checking backend server...${NC}"
if curl -s http://localhost:3000/health > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Backend responding at http://localhost:3000${NC}"
else
    echo -e "${RED}✗ Backend not responding. Run: npm start${NC}"
    exit 1
fi
echo ""

# Check 2: Backend binding
echo -e "${BLUE}[2/7] Checking backend binding (0.0.0.0)...${NC}"
if netstat -tlnp 2>/dev/null | grep -q "0.0.0.0:3000\|0\.0\.0\.0 3000" || \
   ss -tlnp 2>/dev/null | grep -q "0.0.0.0:3000\|0\.0\.0\.0 3000"; then
    echo -e "${GREEN}✓ Backend bound to 0.0.0.0:3000 (all interfaces)${NC}"
else
    echo -e "${YELLOW}⚠ Could not verify binding (may need root for netstat/ss)${NC}"
fi
echo ""

# Check 3: Health endpoint
echo -e "${BLUE}[3/7] Testing health endpoint...${NC}"
HEALTH=$(curl -s http://localhost:3000/health)
if echo "$HEALTH" | grep -q '"status":"ok"'; then
    echo -e "${GREEN}✓ Health endpoint working${NC}"
    echo "  Response: $(echo $HEALTH | jq -c .)"
else
    echo -e "${RED}✗ Health endpoint failed${NC}"
    exit 1
fi
echo ""

# Check 4: Network diagnostics
echo -e "${BLUE}[4/7] Testing network diagnostics endpoint...${NC}"
NET_DIAG=$(curl -s http://localhost:3000/api/debug/network)
if echo "$NET_DIAG" | grep -q '"success":true'; then
    LOCAL_IPS=$(echo "$NET_DIAG" | jq -r '.data.localIps[0]')
    echo -e "${GREEN}✓ Network diagnostics endpoint working${NC}"
    echo "  Local IP: $LOCAL_IPS:3000"
    echo "  Android should use: http://$LOCAL_IPS:3000"
else
    echo -e "${RED}✗ Network diagnostics endpoint failed${NC}"
    exit 1
fi
echo ""

# Check 5: WebSocket status
echo -e "${BLUE}[5/7] Testing WebSocket endpoint...${NC}"
WS_STATUS=$(curl -s http://localhost:3000/api/debug/socket)
if echo "$WS_STATUS" | grep -q '"success":true'; then
    WS_CLIENTS=$(echo "$WS_STATUS" | jq -r '.data.clientCount')
    echo -e "${GREEN}✓ WebSocket endpoint working${NC}"
    echo "  Connected clients: $WS_CLIENTS"
    echo "  (Will show 1 after Android app connects)"
else
    echo -e "${RED}✗ WebSocket endpoint failed${NC}"
    exit 1
fi
echo ""

# Check 6: Backend services
echo -e "${BLUE}[6/7] Testing backend services status...${NC}"
BACKEND=$(curl -s http://localhost:3000/api/debug/backend)
if echo "$BACKEND" | grep -q '"success":true'; then
    MONGO=$(echo "$BACKEND" | jq -r '.data.mongodb.connected')
    AI_PYTHON=$(echo "$BACKEND" | jq -r '.data.aiPython.reachable')
    echo -e "${GREEN}✓ Backend services endpoint working${NC}"
    echo "  MongoDB: $MONGO"
    echo "  AI-python: $AI_PYTHON"
else
    echo -e "${RED}✗ Backend services endpoint failed${NC}"
    exit 1
fi
echo ""

# Check 7: Android configuration
echo -e "${BLUE}[7/7] Checking Android configuration...${NC}"
ANDROID_CONFIG="frontend-kotlin/local.properties"
if [ -f "$ANDROID_CONFIG" ]; then
    BACKEND_HOST=$(grep "BACKEND_HOST=" "$ANDROID_CONFIG" | cut -d= -f2)
    BACKEND_PORT=$(grep "BACKEND_PORT=" "$ANDROID_CONFIG" | cut -d= -f2)
    echo -e "${GREEN}✓ Android configuration found${NC}"
    echo "  BACKEND_HOST: $BACKEND_HOST"
    echo "  BACKEND_PORT: $BACKEND_PORT"
    echo "  Should match: $LOCAL_IPS:3000"
    if [ "$BACKEND_HOST" != "$LOCAL_IPS" ]; then
        echo -e "${YELLOW}⚠ Note: Android IP ($BACKEND_HOST) differs from detected IP ($LOCAL_IPS)${NC}"
        echo "  If they're on different networks, update local.properties"
    fi
else
    echo -e "${YELLOW}⚠ Android local.properties not found${NC}"
    echo "  Create it in: frontend-kotlin/local.properties"
fi
echo ""

# Summary
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}✓ ALL CHECKS PASSED${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════${NC}"
echo ""
echo "Next steps:"
echo "  1. Install Android app: ./gradlew installDebug"
echo "  2. Open app on device"
echo "  3. Click 'Start Automation' to test campaign"
echo "  4. Monitor WebSocket connection in real-time:"
echo "     curl -s http://localhost:3000/api/debug/socket | jq '.data.clientCount'"
echo ""
