#!/bin/bash

# ════════════════════════════════════════════════════════════════════
# EXOTEL SYSTEM VALIDATION SCRIPT
# Verifies all components are ready before starting services
# ════════════════════════════════════════════════════════════════════

set -e

echo "🔍 EXOTEL AI CALLING - SYSTEM VALIDATION"
echo "========================================"
echo ""

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

FAILED=0
WARNINGS=0

# ─── Functions ───────────────────────────────────────────────────────────────
check_file() {
    local file=$1
    local description=$2
    
    if [ -f "$file" ]; then
        echo -e "${GREEN}✓${NC} $description"
        return 0
    else
        echo -e "${RED}✗${NC} $description - File not found: $file"
        FAILED=$((FAILED + 1))
        return 1
    fi
}

check_dir() {
    local dir=$1
    local description=$2
    
    if [ -d "$dir" ]; then
        echo -e "${GREEN}✓${NC} $description"
        return 0
    else
        echo -e "${RED}✗${NC} $description - Directory not found: $dir"
        FAILED=$((FAILED + 1))
        return 1
    fi
}

check_env_var() {
    local file=$1
    local var=$2
    local description=$3
    
    if grep -q "^${var}=" "$file"; then
        local value=$(grep "^${var}=" "$file" | cut -d'=' -f2)
        if [ -z "$value" ] || [ "$value" = "your_sid_here" ] || [ "$value" = "your_*_here" ]; then
            echo -e "${YELLOW}⚠${NC} $description - Not configured (placeholder or empty)"
            WARNINGS=$((WARNINGS + 1))
            return 1
        else
            echo -e "${GREEN}✓${NC} $description"
            return 0
        fi
    else
        echo -e "${RED}✗${NC} $description - Variable not set in $file"
        FAILED=$((FAILED + 1))
        return 1
    fi
}

check_command() {
    local cmd=$1
    local description=$2
    
    if command -v "$cmd" &> /dev/null; then
        local version=$("$cmd" --version 2>&1 | head -n1)
        echo -e "${GREEN}✓${NC} $description - $version"
        return 0
    else
        echo -e "${RED}✗${NC} $description - Not installed"
        FAILED=$((FAILED + 1))
        return 1
    fi
}

# ─── Header ──────────────────────────────────────────────────────────────────
echo -e "${BLUE}📋 PREREQUISITES${NC}"
echo "─────────────────────────────────────────"

check_command "node" "Node.js"
check_command "python3" "Python"
check_command "mongosh" "MongoDB Client"
check_command "ngrok" "ngrok"

echo ""
echo -e "${BLUE}📁 PROJECT STRUCTURE${NC}"
echo "─────────────────────────────────────────"

check_dir "backend-node" "Backend Node.js directory"
check_dir "ai-python" "AI Python directory"
check_dir "leads" "Leads directory"
check_dir "audio" "Audio directory"
check_dir "recordings" "Recordings directory"
check_dir "logs" "Logs directory"

echo ""
echo -e "${BLUE}📦 CONFIGURATION FILES${NC}"
echo "─────────────────────────────────────────"

check_file "backend-node/.env" "Backend environment file"
check_file "ai-python/.env" "Python environment file"
check_file "leads/leads.xlsx" "Excel leads file"
check_file "audio/greeting.wav" "Greeting audio file"

echo ""
echo -e "${BLUE}⚙️  BACKEND CONFIGURATION${NC}"
echo "─────────────────────────────────────────"

if [ -f "backend-node/.env" ]; then
    check_env_var "backend-node/.env" "EXOTEL_SID" "Exotel Account SID"
    check_env_var "backend-node/.env" "EXOTEL_API_KEY" "Exotel API Key"
    check_env_var "backend-node/.env" "EXOTEL_API_TOKEN" "Exotel API Token"
    check_env_var "backend-node/.env" "EXOTEL_CALLER_ID" "Exotel Caller ID"
    check_env_var "backend-node/.env" "MONGODB_URI" "MongoDB Connection URI"
    check_env_var "backend-node/.env" "AI_ENGINE_URL" "Python AI Engine URL"
fi

echo ""
echo -e "${BLUE}🐍 PYTHON CONFIGURATION${NC}"
echo "─────────────────────────────────────────"

if [ -f "ai-python/.env" ]; then
    check_env_var "ai-python/.env" "MONGO_URI" "MongoDB Connection URI"
    check_env_var "ai-python/.env" "WHISPER_MODEL_SIZE" "Whisper Model Size"
    check_env_var "ai-python/.env" "WHISPER_DEVICE" "Whisper Device (cpu/cuda)"
fi

echo ""
echo -e "${BLUE}📦 NODE DEPENDENCIES${NC}"
echo "─────────────────────────────────────────"

if [ -d "backend-node/node_modules" ]; then
    echo -e "${GREEN}✓${NC} Node dependencies installed"
else
    echo -e "${YELLOW}⚠${NC} Node dependencies not installed"
    echo "   Run: cd backend-node && npm install"
    WARNINGS=$((WARNINGS + 1))
fi

echo ""
echo -e "${BLUE}🐍 PYTHON DEPENDENCIES${NC}"
echo "─────────────────────────────────────────"

if [ -d "ai-python/venv" ]; then
    echo -e "${GREEN}✓${NC} Python virtual environment exists"
else
    echo -e "${YELLOW}⚠${NC} Python virtual environment not created"
    echo "   Run: cd ai-python && python -m venv venv && source venv/bin/activate"
    WARNINGS=$((WARNINGS + 1))
fi

echo ""
echo -e "${BLUE}🗄️  DATABASE CONNECTION${NC}"
echo "─────────────────────────────────────────"

if mongosh --eval "db.adminCommand('ping')" &> /dev/null; then
    echo -e "${GREEN}✓${NC} MongoDB is running"
else
    echo -e "${YELLOW}⚠${NC} MongoDB is not responding"
    echo "   Start with: mongod --dbpath /var/lib/mongodb"
    WARNINGS=$((WARNINGS + 1))
fi

echo ""
echo -e "${BLUE}🔗 NGROK TUNNEL${NC}"
echo "─────────────────────────────────────────"

if pgrep -x "ngrok" > /dev/null; then
    echo -e "${GREEN}✓${NC} ngrok is running"
    NGROK_URL=$(curl -s http://localhost:4040/api/tunnels | grep -o '"public_url":"[^"]*' | cut -d'"' -f4 | head -1)
    if [ -n "$NGROK_URL" ]; then
        echo "   Tunnel URL: $NGROK_URL"
        echo "   ${YELLOW}Update BASE_URL in backend-node/.env with: $NGROK_URL${NC}"
    fi
else
    echo -e "${YELLOW}⚠${NC} ngrok is not running"
    echo "   Start with: ngrok http 3000"
    WARNINGS=$((WARNINGS + 1))
fi

echo ""
echo "════════════════════════════════════════════════════════════════"

if [ $FAILED -eq 0 ]; then
    if [ $WARNINGS -eq 0 ]; then
        echo -e "${GREEN}✅ ALL CHECKS PASSED!${NC}"
        echo "System is ready to launch."
    else
        echo -e "${YELLOW}⚠️  CHECKS PASSED WITH WARNINGS${NC}"
        echo "($WARNINGS warnings found - see above)"
    fi
else
    echo -e "${RED}❌ VALIDATION FAILED${NC}"
    echo "($FAILED errors found - see above)"
    exit 1
fi

echo ""
echo "🚀 NEXT STEPS:"
echo ""
echo "1. If MongoDB is not running:"
echo "   mongod --dbpath /var/lib/mongodb"
echo ""
echo "2. If ngrok is not running:"
echo "   ngrok http 3000"
echo ""
echo "3. Start Backend (in new terminal):"
echo "   cd backend-node && npm start"
echo ""
echo "4. Start Python (in new terminal):"
echo "   cd ai-python && source venv/bin/activate && python main.py server"
echo ""
echo "5. Start Campaign (in new terminal):"
echo "   curl -X POST http://localhost:3000/api/call/start \\"
echo "     -H 'Content-Type: application/json' \\"
echo "     -d '{\"campaignName\":\"Production\"}'"
echo ""
