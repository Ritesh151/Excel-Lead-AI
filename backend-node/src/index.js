/**
 * index.js
 * Express + WebSocket application entry point — v3 (ADB+Android+Kotlin).
 *
 * HTTP port:  3000  (Express REST API)
 * WebSocket:  3000  (same port, upgraded from HTTP)
 *
 * ADB flow API:
 *   POST /api/adb/start              → start ADB campaign
 *   POST /api/adb/stop               → stop campaign
 *   GET  /api/adb/status             → campaign status
 *   POST /api/adb/call               → single test call
 *
 * Android upload (proxy to ai-python):
 *   POST /api/calls/recording        → receive WAV from Android, forward to ai-python
 *
 * Voice flow & audio:
 *   /audio/:fileName                 → full-buffer WAV (no 206)
 *   /voice-flow                      → voice XML (Play + Record)
 *   /webhook/recording               → recording webhook
 *
 * WebSocket (ws://localhost:3000/):
 *   Emits real-time events to Android app + dashboard
 */

'use strict';

// config.js loads dotenv and validates all env vars — must be first import
const config = require('./config');

const http    = require('http');
const express = require('express');
const cors    = require('cors');
const morgan  = require('morgan');
const multer  = require('multer');
const path    = require('path');
const axios   = require('axios');
const fs      = require('fs');

const logger             = require('./utils/logger');
const { connect: connectMongo, disconnect: disconnectMongo } = require('./mongodb/connection');
const wsServer           = require('./socket/WebSocketServer');
const { authMiddleware } = require('./middleware/auth');
const { syncLeadFromCallResult } = require('./services/LeadSyncService');
const Recording = require('./mongodb/models/Recording');
const aiPython = require('./services/AiPythonClient');

const leadsRoutes        = require('./api/routes/leadsRoutes');
const callRoutes         = require('./api/routes/callRoutes');
const adbRoutes          = require('./api/routes/adbRoutes');
const debugRoutes        = require('./api/routes/debugRoutes');
const networkDiagRoutes  = require('./api/routes/networkDiagnosticsRoutes');
const voiceFlowRoutes    = require('./routes/voiceFlowRoutes');
const audioRoutes        = require('./routes/audioRoutes');
const testAudioRoutes    = require('./routes/testAudioRoutes');
const webhookRoutes      = require('./routes/webhookRoutes');
const testCallRoutes     = require('./routes/testCallRoutes');
const audioDebugRoutes   = require('./routes/exotelTestRoutes');
const startupDiagnostics = require('./startup/diagnostics');

// ─── App ──────────────────────────────────────────────────────────────────────

const app  = express();
const PORT = parseInt(process.env.PORT || '3000', 10);
const AI_ENGINE_URL = (process.env.AI_ENGINE_URL || 'http://localhost:8000').replace(/\/$/, '');

// Multer storage — recordings forwarded from Android
const _recordingsDir = path.resolve(process.env.RECORDINGS_DIR || '../recordings');
if (!fs.existsSync(_recordingsDir)) fs.mkdirSync(_recordingsDir, { recursive: true });

const androidUploadStorage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, _recordingsDir),
  filename: (_req, file, cb) => {
    const ts = Date.now();
    cb(null, `android_upload_${ts}_${file.originalname}`);
  },
});
const androidUpload = multer({ storage: androidUploadStorage, limits: { fileSize: 50 * 1024 * 1024 } });

// ─── Middleware ───────────────────────────────────────────────────────────────

app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true }));
app.use(cors());
app.use(morgan('combined', { stream: logger.morganStream }));
app.use(authMiddleware);

// Request diagnostics for key paths
app.use((req, res, next) => {
  const isKeyPath =
    req.path.startsWith('/audio')      ||
    req.path.startsWith('/voice-flow') ||
    req.path.startsWith('/webhook')    ||
    req.path.startsWith('/api/calls')  ||
    req.path.startsWith('/api/adb');
  if (isKeyPath) {
    logger.info('[Req]', { method: req.method, path: req.path, ip: req.ip, ua: req.headers['user-agent'] });
  }
  if (req.path.startsWith('/audio')) {
    req.headers['accept-encoding'] = 'identity';
  }
  next();
});

// Attach ws reference so routes can emit events
app.use((req, _res, next) => {
  req.ws = wsServer;
  next();
});

// ─── Health & Diagnostics ─────────────────────────────────────────────────────

app.get('/health', (_req, res) =>
  res.status(200).json({
    status:    'ok',
    service:   'ai-calling-backend',
    version:   '3.0.0',
    websocket: { clients: wsServer.clientCount, android: wsServer.androidConnected },
    timestamp: new Date().toISOString(),
  })
);

// ─── Debug endpoints for network validation ────────────────────────────────────

app.get('/api/debug/network', (_req, res) => {
  const os = require('os');
  const ifaces = os.networkInterfaces();
  const ips = [];
  const interfaces = {};

  for (const [name, addrs] of Object.entries(ifaces)) {
    interfaces[name] = addrs.map(addr => ({
      family: addr.family,
      address: addr.address,
      netmask: addr.netmask,
      internal: addr.internal,
    }));
    addrs.forEach(addr => {
      if (addr.family === 'IPv4' && !addr.internal) {
        ips.push(addr.address);
      }
    });
  }

  return res.json({
    success: true,
    hostname: os.hostname(),
    platform: os.platform(),
    interfaces,
    localIps: ips,
    primaryIp: ips[0] || '127.0.0.1',
    backend: {
      http: `http://${ips[0] || '127.0.0.1'}:${PORT}`,
      ws: `ws://${ips[0] || '127.0.0.1'}:${PORT}/`,
      binding: '0.0.0.0',
    },
    androidCanReach: `http://${ips[0] || '127.0.0.1'}:${PORT}`,
  });
});

app.get('/api/debug/socket', (_req, res) => {
  const clients = Array.from(wsServer._clients || []).map((ws, idx) => ({
    id: idx,
    state: ws.readyState === 1 ? 'OPEN' : ws.readyState === 0 ? 'CONNECTING' : 'CLOSED',
  }));
  
  return res.json({
    success: true,
    clientCount: wsServer.clientCount,
    androidConnected: wsServer.androidConnected,
    clients,
    wsUrl: `ws://0.0.0.0:${PORT}/`,
  });
});

app.get('/api/debug/backend', (_req, res) => {
  return res.json({
    success: true,
    express: 'listening',
    websocket: 'attached',
    mongodb: 'connected',
    aiPython: AI_ENGINE_URL,
    port: PORT,
    nodeEnv: process.env.NODE_ENV || 'development',
  });
});

app.get('/api/debug/tcp', (_req, res) => {
  const os = require('os');
  const ifaces = os.networkInterfaces();
  const ips = [];
  for (const addrs of Object.values(ifaces)) {
    addrs.forEach(addr => {
      if (addr.family === 'IPv4' && !addr.internal) ips.push(addr.address);
    });
  }
  const primaryIp = ips[0] || 'N/A';
  
  return res.json({
    success: true,
    message: 'Backend is reachable via TCP',
    clientIp: _req.ip,
    tcpReachable: true,
    serverBinding: '0.0.0.0',
    port: PORT,
    primaryIp,
    httpUrl: `http://${primaryIp}:${PORT}`,
    wsUrl: `ws://${primaryIp}:${PORT}/`,
  });
});

// ─── Android WAV upload proxy ──────────────────────────────────────────────────
//
// The Android Kotlin app (ApiClient.kt) posts to /api/calls/recording.
// We save the file locally AND forward it to ai-python /api/calls/recording
// for Whisper transcription, then return the intent to the Android app.

app.post('/api/calls/recording', androidUpload.single('file'), async (req, res) => {
  const remoteNumber = req.body.remoteNumber || 'unknown';
  const callType     = req.body.callType     || 'outgoing';
  const timestamp    = req.body.timestamp    || String(Date.now());

  logger.info('[AndroidProxy] Received recording', {
    filename: req.file?.filename,
    size:     req.file?.size,
    number:   remoteNumber,
    callType,
  });

  if (!req.file) {
    return res.status(400).json({ success: false, message: 'No file uploaded', intent: 'UNKNOWN' });
  }

  // Emit WebSocket event
  wsServer.emitRecordingSaved(remoteNumber, req.file.path, Math.round(req.file.size / 32000));
  wsServer.emitRecordingStarted(remoteNumber);

  // Forward to ai-python for transcription
  try {
    const FormData = require('form-data');
    const form = new FormData();
    form.append('file', fs.createReadStream(req.file.path), req.file.filename);
    form.append('callType', callType);
    form.append('remoteNumber', remoteNumber);
    form.append('timestamp', timestamp);

    const response = await axios.post(
      `${AI_ENGINE_URL}/api/calls/recording`,
      form,
      {
        headers: form.getHeaders(),
        timeout: parseInt(process.env.TRANSCRIPTION_TIMEOUT_MS || '120000', 10),
      }
    );

    const { intent, transcription, db_id } = response.data;
    logger.info('[AndroidProxy] Transcription result', { remoteNumber, intent, transcription });

    // Emit WebSocket events
    wsServer.emitTranscriptionDone(remoteNumber, transcription || '', intent || 'UNKNOWN', response.data.confidence || 0);
    wsServer.emitIntentDetected(remoteNumber, intent || 'UNKNOWN', response.data.confidence || 0, null);
    wsServer.emitCallCompleted(remoteNumber, intent || 'UNKNOWN', transcription || '', db_id || null);

    await syncLeadFromCallResult({
      phone: remoteNumber,
      intent: intent || 'UNKNOWN',
      transcription: transcription || '',
      recordingPath: req.file.path,
      success: true,
    }).catch(() => {});

    await Recording.create({
      phone: remoteNumber,
      filePath: req.file.path,
      fileSize: req.file.size,
      source: 'android',
      transcription: transcription || null,
      intent: intent || null,
      callRecordId: db_id || null,
    }).catch(() => {});

    return res.status(200).json({
      intent:        intent        || 'UNKNOWN',
      transcription: transcription || null,
      success:       true,
    });

  } catch (err) {
    logger.error('[AndroidProxy] ai-python transcription failed', { error: err.message, remoteNumber });

    // Still return a valid response to the Android app so it doesn't hang
    return res.status(200).json({
      intent:        'UNKNOWN',
      transcription: null,
      success:       false,
      message:       `Transcription error: ${err.message}`,
    });
  }
});

// ─── WebSocket status endpoint ────────────────────────────────────────────────

app.get('/api/ws/status', (_req, res) => {
  res.json({
    clients:         wsServer.clientCount,
    androidConnected: wsServer.androidConnected,
  });
});

// ─── Campaign WebSocket emitter route ─────────────────────────────────────────
// ai-python notifies backend-node of campaign events via HTTP (simpler than WS from Python)

app.post('/api/events/emit', express.json(), (req, res) => {
  const { event, payload } = req.body || {};
  if (!event) return res.status(400).json({ success: false, message: 'event required' });
  wsServer.emit(event, payload || {});
  res.json({ success: true, event, clients: wsServer.clientCount });
});

// ─── Existing routes ──────────────────────────────────────────────────────────

app.use('/api/leads',  leadsRoutes);
app.use('/api/call',   callRoutes);
app.use('/api/adb',    adbRoutes);
app.use('/api/debug',  debugRoutes);
app.use('/api/debug',  networkDiagRoutes);  // NEW: Network diagnostics
app.use('/audio',      audioRoutes);
app.use('/voice-flow', voiceFlowRoutes);
app.use('/webhook',    webhookRoutes);
app.use('/',           testCallRoutes);
app.use('/',           audioDebugRoutes);
app.use('/',           testAudioRoutes);


// ─── 404 ─────────────────────────────────────────────────────────────────────

app.use((_req, res) =>
  res.status(404).json({ success: false, message: 'Route not found' })
);

// ─── Error handler ────────────────────────────────────────────────────────────

// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  logger.error('Unhandled error', { error: err.message, stack: err.stack });
  res.status(500).json({ success: false, message: 'Internal server error' });
});

// ─── Bootstrap ────────────────────────────────────────────────────────────────

async function bootstrap() {
  // Create HTTP server so both Express and WebSocket share the same port
  const httpServer = http.createServer(app);

  // Attach WebSocket server
  wsServer.attach(httpServer);

  try {
    await connectMongo();

    // CRITICAL: Bind to 0.0.0.0 so Android LAN clients can reach backend
    const HOST = '0.0.0.0';
    httpServer.listen(PORT, HOST, () => {
      const os = require('os');
      const ifaces = os.networkInterfaces();
      const ips = [];
      
      for (const name of Object.keys(ifaces)) {
        for (const iface of ifaces[name]) {
          if (iface.family === 'IPv4' && !iface.internal) {
            ips.push(iface.address);
          }
        }
      }
      
      const primaryIp = ips[0] || 'localhost';

      // EXTENSIVE STARTUP LOGGING FOR DIAGNOSTICS
      console.log('\n' + '═'.repeat(70));
      console.log('🚀 AI CALLING BACKEND — PRODUCTION NETWORK CONFIGURATION');
      console.log('═'.repeat(70));
      console.log(`⏰ Started at: ${new Date().toISOString()}`);
      console.log(`📍 Hostname: ${os.hostname()}`);
      console.log(`🖥️  Platform: ${os.platform()} ${os.arch()}`);
      console.log('\n📡 NETWORK BINDING:');
      console.log(`   ✓ Binding address: ${HOST} (all interfaces)`);
      console.log(`   ✓ Port: ${PORT}`);
      console.log(`   ✓ Protocol: HTTP/1.1 with WebSocket upgrade`);
      
      console.log('\n🌍 EXTERNAL ACCESS (Android LAN):');
      ips.forEach((ip, idx) => {
        console.log(`   ✓ HTTP  [${idx}]: http://${ip}:${PORT}`);
        console.log(`   ✓ WS    [${idx}]: ws://${ip}:${PORT}/`);
      });
      
      console.log('\n💻 LOCAL ACCESS (PC only):');
      console.log(`   ✓ HTTP: http://localhost:${PORT}`);
      console.log(`   ✓ WS:   ws://localhost:${PORT}/`);
      
      console.log('\n🛠️  SERVICES:');
      console.log(`   ✓ Express: ready`);
      console.log(`   ✓ WebSocket: attached`);
      console.log(`   ✓ MongoDB: connected`);
      console.log(`   ✓ AI-Python: ${AI_ENGINE_URL}`);
      
      console.log('\n📊 DEBUG ENDPOINTS:');
      console.log(`   ✓ GET /health                  — Overall health`);
      console.log(`   ✓ GET /api/debug/network      — Network interfaces`);
      console.log(`   ✓ GET /api/debug/socket       — WebSocket status`);
      console.log(`   ✓ GET /api/debug/backend      — Backend services`);
      console.log(`   ✓ GET /api/debug/tcp          — TCP reachability`);
      
      console.log('\n⚠️  IMPORTANT FOR ANDROID:');
      console.log(`   📲 Use IP: ${primaryIp}`);
      console.log(`   📲 HTTP:   http://${primaryIp}:${PORT}`);
      console.log(`   📲 WS:     ws://${primaryIp}:${PORT}/`);
      
      console.log('\n' + '═'.repeat(70));
      logger.info(`═══════════════════════════════════════════════`);
      logger.info(`  AI Calling Backend v3 — ADB+Android+Kotlin`);
      logger.info(`  🌍 PUBLIC (Android LAN): http://${primaryIp}:${PORT}`);
      logger.info(`  🌍 PUBLIC (Android LAN): ws://${primaryIp}:${PORT}/`);
      logger.info(`  💻 LOCAL (PC only):      http://localhost:${PORT}`);
      logger.info(`  💻 LOCAL (PC only):      ws://localhost:${PORT}/`);
      logger.info(`  Env   : ${process.env.NODE_ENV || 'development'}`);
      logger.info(`  AI URL: ${AI_ENGINE_URL}`);
      logger.info(`═══════════════════════════════════════════════`);
    });

    // Startup diagnostics (non-blocking)
    startupDiagnostics.validateEnvironment().catch((e) =>
      logger.warn('[Startup] Diagnostics warning:', e.message)
    );

    _validateDependencies().catch(() => {});

    const shutdown = async (signal) => {
      logger.info(`${signal} — shutting down`);
      wsServer.close();
      httpServer.close(async () => {
        await disconnectMongo();
        process.exit(0);
      });
    };
    process.on('SIGTERM', () => shutdown('SIGTERM'));
    process.on('SIGINT',  () => shutdown('SIGINT'));

  } catch (error) {
    logger.error('Bootstrap failed', { error: error.message });
    httpServer.close(() => process.exit(1));
  }
}

async function _validateDependencies() {
  try {
    await aiPython.health();
    logger.info('[Startup] ✓ ai-python reachable');
  } catch (err) {
    logger.warn('[Startup] ⚠ ai-python not reachable — ADB calls will fail until started:', err.message);
  }
  logger.info(`[Startup] WebSocket ready — path / (${wsServer.clientCount} clients)`);
}

bootstrap();
