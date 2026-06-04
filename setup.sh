#!/bin/bash

# ════════════════════════════════════════════════════════════════════
# EXOTEL VOICE CALLING AUTOMATION - QUICK START SCRIPT
# Production-Ready Deployment
# ════════════════════════════════════════════════════════════════════

set -e

echo "🚀 AI CALLING - EXOTEL DEPLOYMENT SCRIPT"
echo "=========================================="
echo ""

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check prerequisites
echo "📋 Checking prerequisites..."

if ! command -v node &> /dev/null; then
    echo -e "${RED}✗ Node.js not found${NC}"
    echo "  Install: https://nodejs.org"
    exit 1
fi
echo -e "${GREEN}✓ Node.js $(node --version)${NC}"

if ! command -v python3 &> /dev/null; then
    echo -e "${RED}✗ Python not found${NC}"
    echo "  Install: https://www.python.org"
    exit 1
fi
echo -e "${GREEN}✓ Python $(python3 --version)${NC}"

if ! command -v mongosh &> /dev/null; then
    echo -e "${YELLOW}⚠ MongoDB client not found${NC}"
    echo "  Ensure MongoDB is running: mongod --dbpath /var/lib/mongodb"
fi

# Setup Backend
echo ""
echo "🔧 Setting up Backend (Node.js)..."

if [ ! -d "backend-node/node_modules" ]; then
    cd backend-node
    npm install
    cd ..
    echo -e "${GREEN}✓ Backend dependencies installed${NC}"
else
    echo -e "${GREEN}✓ Backend already set up${NC}"
fi

# Create .env if doesn't exist
if [ ! -f "backend-node/.env" ]; then
    echo -e "${YELLOW}⚠ Creating backend-node/.env${NC}"
    cp backend-node/.env.example backend-node/.env
    echo -e "${YELLOW}  IMPORTANT: Edit backend-node/.env with your Exotel credentials${NC}"
    echo "  - EXOTEL_SID"
    echo "  - EXOTEL_API_KEY"
    echo "  - EXOTEL_API_TOKEN"
    echo "  - EXOTEL_CALLER_ID"
    echo "  - BASE_URL (update with ngrok URL after starting)"
fi

# Setup Python
echo ""
echo "🐍 Setting up Python..."

if [ ! -d "ai-python/venv" ]; then
    cd ai-python
    python3 -m venv venv
    source venv/bin/activate || . venv/Scripts/activate
    pip install --upgrade pip
    pip install -r requirements.txt
    deactivate
    cd ..
    echo -e "${GREEN}✓ Python environment created${NC}"
else
    echo -e "${GREEN}✓ Python environment already set up${NC}"
fi

# Create Python .env if doesn't exist
if [ ! -f "ai-python/.env" ]; then
    echo -e "${YELLOW}⚠ Creating ai-python/.env${NC}"
    cp ai-python/.env.example ai-python/.env
fi

# Check audio files
echo ""
echo "🎵 Checking audio files..."

if [ ! -f "audio/greeting.wav" ]; then
    echo -e "${RED}✗ audio/greeting.wav not found${NC}"
    echo "  Please create or place greeting.wav in audio/ folder"
else
    echo -e "${GREEN}✓ greeting.wav found${NC}"
fi

# Check leads file
echo ""
echo "📊 Checking leads file..."

if [ ! -f "leads/leads.xlsx" ]; then
    echo -e "${YELLOW}⚠ leads/leads.xlsx not found${NC}"
    echo "  Create Excel file with columns: Name, Mobile Number"
else
    echo -e "${GREEN}✓ leads.xlsx found${NC}"
fi

# Create directories
mkdir -p logs recordings

echo ""
echo "════════════════════════════════════════════════════════════════"
echo -e "${GREEN}✓ SETUP COMPLETE!${NC}"
echo "════════════════════════════════════════════════════════════════"
echo ""

echo "📝 NEXT STEPS:"
echo ""
echo "1. ⚙️  Configure Exotel Credentials"
echo "   Edit: backend-node/.env"
echo "   Add your Exotel SID, API Key, Token, and Caller ID"
echo ""
echo "2. 🌐 Start ngrok (in new terminal):"
echo "   ngrok http 3000"
echo ""
echo "3. 🔄 Update BASE_URL in backend-node/.env"
echo "   Use the ngrok URL (https://xxxxx.ngrok.io)"
echo ""
echo "4. 🗄️  Start MongoDB (in new terminal):"
echo "   mongod --dbpath /var/lib/mongodb"
echo ""
echo "5. 🚀 Start Backend (in new terminal):"
echo "   cd backend-node && npm start"
echo ""
echo "6. 🧠 Start Python (in new terminal):"
echo "   cd ai-python && source venv/bin/activate && python main.py server"
echo ""
echo "7. 📞 Start Campaign (in new terminal):"
echo "   curl -X POST http://localhost:3000/api/call/start \\"
echo "     -H 'Content-Type: application/json' \\"
echo "     -d '{\"campaignName\":\"Production\"}'"
echo ""
echo "8. 📊 Monitor Status:"
echo "   curl http://localhost:3000/api/call/status"
echo ""
echo "════════════════════════════════════════════════════════════════"
echo ""
echo "📖 Full documentation: See STARTUP.md"
echo ""
