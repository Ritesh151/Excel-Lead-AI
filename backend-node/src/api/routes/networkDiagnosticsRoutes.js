/**
 * networkDiagnosticsRoutes.js — Network connectivity diagnostics endpoints
 *
 * Endpoints:
 *   GET /api/debug/network      — detailed network info (IP, ports, LAN status)
 *   GET /api/debug/socket       — WebSocket connectivity status
 *   GET /api/debug/backend      — Backend service status (AI-python, MongoDB, etc)
 */

'use strict';

const express = require('express');
const os = require('os');
const axios = require('axios');
const logger = require('../../utils/logger');

const router = express.Router();

/**
 * GET /api/debug/network
 * Returns network configuration and LAN reachability info.
 */
router.get('/network', (_req, res) => {
  try {
    const ifaces = os.networkInterfaces();
    const networkInfo = {};
    const ips = [];

    for (const [name, addresses] of Object.entries(ifaces)) {
      networkInfo[name] = [];
      for (const addr of addresses) {
        networkInfo[name].push({
          family: addr.family,
          address: addr.address,
          netmask: addr.netmask,
          internal: addr.internal,
        });
        if (addr.family === 'IPv4' && !addr.internal) {
          ips.push(addr.address);
        }
      }
    }

    res.json({
      success: true,
      message: 'Network configuration',
      data: {
        // Current machine network info
        hostname: os.hostname(),
        platform: os.platform(),
        localIps: ips,
        networkInterfaces: networkInfo,

        // Backend server binding
        backend: {
          http: `http://0.0.0.0:${process.env.PORT || 3000}`,
          ws: `ws://0.0.0.0:${process.env.PORT || 3000}/`,
          boundIps: ips,
          note: 'Server bound to 0.0.0.0 — accessible from LAN via any local IP',
        },

        // How to reach from Android
        androidReachable: {
          http: ips.length > 0 ? `http://${ips[0]}:${process.env.PORT || 3000}` : 'N/A (no local IP)',
          ws: ips.length > 0 ? `ws://${ips[0]}:${process.env.PORT || 3000}/` : 'N/A (no local IP)',
        },

        // AI-python backend
        aiPythonBackend: {
          url: process.env.AI_ENGINE_URL || 'http://localhost:8000',
        },

        // MongoDB
        mongodb: {
          uri: process.env.MONGO_URI ? process.env.MONGO_URI.replace(/\/\/.*:.*@/, '//***:***@') : 'Not configured',
        },

        timestamp: new Date().toISOString(),
      },
    });
  } catch (error) {
    res.status(500).json({
      success: false,
      error: error.message,
      message: 'Network diagnostics failed',
    });
  }
});

/**
 * GET /api/debug/socket
 * Returns WebSocket server status.
 */
router.get('/socket', (req, res) => {
  const ws = req.ws; // Attached in main index.js
  
  res.json({
    success: true,
    message: 'WebSocket server status',
    data: {
      connected: ws?.clientCount > 0,
      clientCount: ws?.clientCount || 0,
      androidConnected: ws?.androidConnected || false,
      wsUrl: `ws://0.0.0.0:${process.env.PORT || 3000}/`,
      clients: Array.from(ws?.clients || []).map(client => ({
        id: client.id || 'unknown',
        type: client.type || 'unknown',
        connected: true,
      })),
      timestamp: new Date().toISOString(),
    },
  });
});

/**
 * GET /api/debug/backend
 * Checks connectivity to all backend services.
 */
router.get('/backend', async (_req, res) => {
  const results = {};

  // 1. MongoDB (via mongoose)
  try {
    const mongoose = require('mongoose');
    results.mongodb = {
      connected: mongoose.connection.readyState === 1,
      state: ['disconnected', 'connected', 'connecting', 'disconnecting'][mongoose.connection.readyState],
    };
  } catch (e) {
    results.mongodb = { connected: false, error: e.message };
  }

  // 2. AI-python
  const aiUrl = process.env.AI_ENGINE_URL || 'http://localhost:8000';
  try {
    const response = await axios.get(`${aiUrl}/health`, { timeout: 5000 });
    results.aiPython = {
      url: aiUrl,
      reachable: response.status === 200,
      status: response.data?.status || 'unknown',
    };
  } catch (e) {
    results.aiPython = {
      url: aiUrl,
      reachable: false,
      error: e.message,
      note: 'AI-python service is not responding. Make sure ai-python is running on port 8000.',
    };
  }

  // 3. Local ports
  results.ports = {
    port3000: `${process.env.PORT || 3000} (backend-node — Express + WebSocket)`,
    port8000: '8000 (ai-python — Flask)',
    port27017: '27017 (MongoDB — if running locally)',
  };

  // 4. Network accessibility
  results.networkAccess = {
    httpServer: `http://0.0.0.0:${process.env.PORT || 3000} (all interfaces)`,
    wsServer: `ws://0.0.0.0:${process.env.PORT || 3000}/ (all interfaces)`,
    androidAccess: `Use: http://${os.networkInterfaces()[Object.keys(os.networkInterfaces())[0]]?.[0]?.address || 'BACKEND_IP'}:${process.env.PORT || 3000}`,
  };

  res.json({
    success: true,
    message: 'Backend service status',
    data: results,
    timestamp: new Date().toISOString(),
  });
});

/**
 * GET /api/debug/validate-android-connection
 * Validates that Android can successfully connect.
 */
router.get('/validate-android-connection', async (req, res) => {
  const clientIp = req.ip || req.connection.remoteAddress || 'unknown';
  const isLANClient = clientIp.startsWith('10.') || 
                      clientIp.startsWith('192.168.') || 
                      clientIp.startsWith('172.');

  res.json({
    success: true,
    message: 'Android connection validation',
    data: {
      clientIp,
      isLANClient,
      serverBinds: '0.0.0.0 (all interfaces)',
      serverPorts: {
        http: process.env.PORT || 3000,
        ws: process.env.PORT || 3000,
      },
      status: isLANClient ? 'CONNECTED ✓' : 'WARNING: Client does not appear to be on LAN',
      recommendation: isLANClient 
        ? 'Android should be able to reach backend'
        : 'Android should connect from same LAN as backend',
      timestamp: new Date().toISOString(),
    },
  });
});

module.exports = router;
