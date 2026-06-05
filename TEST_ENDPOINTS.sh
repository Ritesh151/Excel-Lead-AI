#!/bin/bash
# TEST_ENDPOINTS.sh — Quick verification of backend and networking

set -e

BACKEND_HOST="${1:-localhost}"
BACKEND_PORT="${2:-3000}"

echo "════════════════════════════════════════════════════════════════"
echo "🔍 Testing Backend Endpoints"
echo "════════════════════════════════════════════════════════════════"
echo ""
echo "Backend URL: http://${BACKEND_HOST}:${BACKEND_PORT}"
echo ""

# Test 1: Health endpoint
echo "1️⃣  Testing: GET /health"
if curl -s "http://${BACKEND_HOST}:${BACKEND_PORT}/health" | jq . 2>/dev/null; then
    echo "   ✅ SUCCESS"
else
    echo "   ❌ FAILED — Backend not responding"
    exit 1
fi
echo ""

# Test 2: Network debug
echo "2️⃣  Testing: GET /api/debug/network"
if curl -s "http://${BACKEND_HOST}:${BACKEND_PORT}/api/debug/network" | jq . 2>/dev/null; then
    echo "   ✅ SUCCESS"
else
    echo "   ❌ FAILED"
    exit 1
fi
echo ""

# Test 3: Socket debug
echo "3️⃣  Testing: GET /api/debug/socket"
if curl -s "http://${BACKEND_HOST}:${BACKEND_PORT}/api/debug/socket" | jq . 2>/dev/null; then
    echo "   ✅ SUCCESS"
else
    echo "   ❌ FAILED"
    exit 1
fi
echo ""

# Test 4: Backend debug
echo "4️⃣  Testing: GET /api/debug/backend"
if curl -s "http://${BACKEND_HOST}:${BACKEND_PORT}/api/debug/backend" | jq . 2>/dev/null; then
    echo "   ✅ SUCCESS"
else
    echo "   ❌ FAILED"
    exit 1
fi
echo ""

# Test 5: TCP debug
echo "5️⃣  Testing: GET /api/debug/tcp"
if curl -s "http://${BACKEND_HOST}:${BACKEND_PORT}/api/debug/tcp" | jq . 2>/dev/null; then
    echo "   ✅ SUCCESS"
else
    echo "   ❌ FAILED"
    exit 1
fi
echo ""

echo "════════════════════════════════════════════════════════════════"
echo "✅ All endpoints responding correctly!"
echo "════════════════════════════════════════════════════════════════"
echo ""
echo "🌍 Backend is LAN accessible at:"
echo "   • HTTP: http://${BACKEND_HOST}:${BACKEND_PORT}"
echo "   • WS:   ws://${BACKEND_HOST}:${BACKEND_PORT}/"
echo ""
echo "📱 Configure Android app to use: ${BACKEND_HOST}:${BACKEND_PORT}"
echo ""
