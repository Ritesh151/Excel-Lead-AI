#!/bin/bash
# =============================================================================
# FIREWALL AND NETWORK FIX FOR ANDROID CONNECTIVITY
# Run this on the PC/laptop running backend-node and ai-python
# =============================================================================

set -e

LOCAL_IP=$(hostname -I | awk '{print $1}')
echo "═══════════════════════════════════════════════════════════════"
echo " NETWORK DIAGNOSTICS AND FIREWALL FIX"
echo " Local IP: $LOCAL_IP"
echo "═══════════════════════════════════════════════════════════════"

# ─── 1. VERIFY PORT LISTENING ───────────────────────────────────────
echo ""
echo "[1] VERIFY BACKEND IS LISTENING ON ALL INTERFACES"
echo "───────────────────────────────────────────────────────────────"
echo "Running: ss -tlnp | grep -E '(3000|8000)'"
ss -tlnp | grep -E '(3000|8000)' || echo "  -> NOT LISTENING on 3000/8000"
echo ""
echo "Running: netstat -tlnp 2>/dev/null | grep -E '(3000|8000)'"
netstat -tlnp 2>/dev/null | grep -E '(3000|8000)' || echo "  -> (netstat not available)"
echo ""
echo "Running: lsof -i :3000 -i :8000"
lsof -i :3000 -i :8000 2>/dev/null || echo "  -> No process found on 3000/8000"

# ─── 2. VERIFY BINDING ADDRESS ──────────────────────────────────────
echo ""
echo "[2] VERIFY BINDING TO 0.0.0.0"
echo "───────────────────────────────────────────────────────────────"
ss -tlnp 2>/dev/null | awk '$4 ~ /0.0.0.0:3000/ {print "  ✓ Backend bound to 0.0.0.0:3000"}'
ss -tlnp 2>/dev/null | awk '$4 ~ /0.0.0.0:8000/ {print "  ✓ AI Engine bound to 0.0.0.0:8000"}'
ss -tlnp 2>/dev/null | awk '$4 ~ /127.0.0.1:3000/ {print "  ✗ Backend bound to 127.0.0.1:3000 ONLY — Android cannot reach!"}'
ss -tlnp 2>/dev/null | awk '$4 ~ /127.0.0.1:8000/ {print "  ✗ AI Engine bound to 127.0.0.1:8000 ONLY — Android cannot reach!"}'

# ─── 3. UFW FIREWALL ────────────────────────────────────────────────
echo ""
echo "[3] UFW FIREWALL CONFIGURATION"
echo "───────────────────────────────────────────────────────────────"
if command -v ufw &> /dev/null; then
    echo "UFW status:"
    sudo ufw status verbose
    echo ""
    echo "Opening ports 3000 and 8000..."
    sudo ufw allow 3000/tcp comment "AI Calling Backend"
    sudo ufw allow 8000/tcp comment "AI Calling AI Engine"
    echo "  ✓ Ports 3000/tcp, 8000/tcp allowed"
else
    echo "  UFW not installed. Skipping."
fi

# ─── 4. IPTABLES FIREWALL ───────────────────────────────────────────
echo ""
echo "[4] IPTABLES FIREWALL CONFIGURATION"
echo "───────────────────────────────────────────────────────────────"
if command -v iptables &> /dev/null; then
    echo "Current iptables rules for ports 3000, 8000:"
    sudo iptables -L INPUT -n -v 2>/dev/null | grep -E '(3000|8000|dpt:)' || echo "  -> No specific rules found"
    echo ""
    echo "Adding iptables rules if missing..."
    sudo iptables -C INPUT -p tcp --dport 3000 -j ACCEPT 2>/dev/null && \
        echo "  ✓ Rule for port 3000 exists" || \
        { sudo iptables -A INPUT -p tcp --dport 3000 -j ACCEPT && echo "  ✓ Added rule for port 3000"; }
    sudo iptables -C INPUT -p tcp --dport 8000 -j ACCEPT 2>/dev/null && \
        echo "  ✓ Rule for port 8000 exists" || \
        { sudo iptables -A INPUT -p tcp --dport 8000 -j ACCEPT && echo "  ✓ Added rule for port 8000"; }
else
    echo "  iptables not available. Skipping."
fi

# ─── 5. FIREWALLD ──────────────────────────────────────────────────
echo ""
echo "[5] FIREWALLD CONFIGURATION (if active)"
echo "───────────────────────────────────────────────────────────────"
if command -v firewall-cmd &> /dev/null; then
    sudo firewall-cmd --add-port=3000/tcp --permanent 2>/dev/null || true
    sudo firewall-cmd --add-port=8000/tcp --permanent 2>/dev/null || true
    sudo firewall-cmd --reload 2>/dev/null || true
    echo "  ✓ firewalld ports added"
else
    echo "  firewalld not active. Skipping."
fi

# ─── 6. VERIFY CONNECTIVITY FROM LOCAL ─────────────────────────────
echo ""
echo "[6] LOCAL CONNECTIVITY TEST"
echo "───────────────────────────────────────────────────────────────"
echo "Testing: curl -s http://localhost:3000/health"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:3000/health 2>/dev/null || echo "FAILED")
echo "  Response: $HTTP_CODE"

echo "Testing: curl -s http://127.0.0.1:3000/health"
HTTP_CODE2=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3000/health 2>/dev/null || echo "FAILED")
echo "  Response: $HTTP_CODE2"

echo "Testing: curl -s http://$LOCAL_IP:3000/health"
HTTP_CODE3=$(curl -s -o /dev/null -w "%{http_code}" http://$LOCAL_IP:3000/health 2>/dev/null || echo "FAILED")
echo "  Response: $HTTP_CODE3"

# ─── 7. CHECK ROUTER ISOLATION ─────────────────────────────────────
echo ""
echo "[7] ROUTER/AP ISOLATION CHECK"
echo "───────────────────────────────────────────────────────────────"
echo "Checking if local IP is in private range..."
if [[ $LOCAL_IP == 10.* ]] || [[ $LOCAL_IP == 172.1[6-9].* ]] || [[ $LOCAL_IP == 172.2[0-9].* ]] || [[ $LOCAL_IP == 172.3[0-1].* ]] || [[ $LOCAL_IP == 192.168.* ]]; then
    echo "  ✓ IP $LOCAL_IP is a private LAN address"
else
    echo "  ⚠ IP $LOCAL_IP is not a standard private IP"
fi

echo ""
echo "Checking for common AP isolation indicators..."
ping -c 1 -W 1 $LOCAL_IP > /dev/null 2>&1 && \
    echo "  ✓ Can ping self" || \
    echo "  ⚠ Cannot ping self (unexpected)"

echo ""
echo "Checking if another device on LAN can reach this machine..."
echo "  -> Have another device ping: ping $LOCAL_IP"
echo "  -> Or from Android browser: http://$LOCAL_IP:3000/health"

# ─── 8. SUMMARY ─────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo " SUMMARY"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo " Backend URL: http://$LOCAL_IP:3000"
echo " WebSocket URL: ws://$LOCAL_IP:3000/"
echo " AI Engine URL: http://$LOCAL_IP:8000"
echo ""
echo " Android local.properties MUST have:"
echo "   BACKEND_HOST=$LOCAL_IP"
echo "   BACKEND_PORT=3000"
echo ""
echo " Verify from Android with:"
echo "   curl http://$LOCAL_IP:3000/health"
echo "   curl http://$LOCAL_IP:8000/health"
echo ""
echo " If Android cannot connect:"
echo "   1. Check both devices on same WiFi"
echo "   2. Disable AP Isolation / Client Isolation in router"
echo "   3. Disable Guest Network isolation"
echo "   4. Turn off VPN on Android"
echo "   5. Turn off Mobile Data (forces WiFi)"
echo "   6. Check Windows Defender / Linux firewall"
echo "   7. Disable hotspot if using one"
echo "═══════════════════════════════════════════════════════════════"
