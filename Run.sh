#!/bin/bash
# =============================================================================
# AI Calling System - Production Startup Script
# Binds all services to 0.0.0.0 for Android LAN access
# =============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Get local IP
LOCAL_IP=$(hostname -I | awk '{print $1}')
if [ -z "$LOCAL_IP" ]; then
    LOCAL_IP=$(ip route get 1 | awk '{print $7; exit}')
fi

echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  AI CALLING SYSTEM STARTUP${NC}"
echo -e "${BLUE}  LAN IP: ${GREEN}$LOCAL_IP${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${YELLOW}Step 1: Checking firewall...${NC}"
if command -v ufw &> /dev/null; then
    sudo ufw status | grep -q "active" && echo -e "${GREEN}  UFW active${NC}" || echo -e "${YELLOW}  UFW inactive${NC}"
    sudo ufw allow 3000/tcp 2>/dev/null || true
    sudo ufw allow 8000/tcp 2>/dev/null || true
    echo -e "${GREEN}  Ports 3000, 8000 opened${NC}"
fi
if command -v iptables &> /dev/null; then
    sudo iptables -C INPUT -p tcp --dport 3000 -j ACCEPT 2>/dev/null || {
        sudo iptables -A INPUT -p tcp --dport 3000 -j ACCEPT 2>/dev/null || true
        sudo iptables -A INPUT -p tcp --dport 8000 -j ACCEPT 2>/dev/null || true
    }
    echo -e "${GREEN}  iptables rules verified${NC}"
fi

echo ""
echo -e "${YELLOW}Step 2: Starting backend-node (port 3000, 0.0.0.0)...${NC}"
cd backend-node
PORT=3000 HOST=0.0.0.0 npm run dev &
BACKEND_PID=$!
echo -e "${GREEN}  Backend PID: $BACKEND_PID${NC}"

echo ""
echo -e "${YELLOW}Step 3: Starting ai-python (port 8000, 0.0.0.0)...${NC}"
cd ../ai-python
python main.py server --host 0.0.0.0 --port 8000 &
AI_PID=$!
echo -e "${GREEN}  AI Engine PID: $AI_PID${NC}"

echo ""
echo -e "${YELLOW}Step 4: Waiting for services to start...${NC}"
sleep 3

echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  SERVICE STATUS${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"

# Check backend
if curl -s -o /dev/null -w "%{http_code}" http://localhost:3000/health 2>/dev/null | grep -q "200"; then
    echo -e "${GREEN}  ✓ Backend: http://0.0.0.0:3000/health -> 200 OK${NC}"
else
    echo -e "${RED}  ✗ Backend: NOT RESPONDING${NC}"
fi

# Check ai-python
if curl -s -o /dev/null -w "%{http_code}" http://localhost:8000/health 2>/dev/null | grep -q "200"; then
    echo -e "${GREEN}  ✓ AI Engine: http://0.0.0.0:8000/health -> 200 OK${NC}"
else
    echo -e "${RED}  ✗ AI Engine: NOT RESPONDING${NC}"
fi

echo ""
echo -e "${GREEN}═══ ANDROID MUST USE THESE ADDRESSES ═══${NC}"
echo -e "${GREEN}  HTTP:  http://$LOCAL_IP:3000${NC}"
echo -e "${GREEN}  WS:    ws://$LOCAL_IP:3000/${NC}"
echo -e "${GREEN}  AI:    http://$LOCAL_IP:8000${NC}"
echo ""

# Verify ports are listening on all interfaces
echo -e "${YELLOW}Listening on 0.0.0.0:${NC}"
ss -tlnp | grep -E '(3000|8000)' || netstat -tlnp 2>/dev/null | grep -E '(3000|8000)' || echo -e "${RED}  Warning: cannot verify listening ports${NC}"

echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  Services running. Press Ctrl+C to stop all.${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"

# Trap for cleanup
trap "echo ''; echo 'Shutting down...'; kill $BACKEND_PID $AI_PID 2>/dev/null; exit" SIGINT SIGTERM

# Wait for both processes
wait
