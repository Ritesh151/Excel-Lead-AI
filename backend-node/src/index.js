/**
 * index.js
 * Express application entry point.
 *
 * Audio serving strategy (Exotel-compatible):
 *   - /audio/:fileName  →  audioRoutes.js  →  full-buffer 200 response
 *   - No express.static for audio. No res.sendFile. No Accept-Ranges.
 *   - No compression middleware anywhere.
 */

require('dotenv').config();

const express = require('express');
const cors = require('cors');
const morgan = require('morgan');

const logger = require('./utils/logger');
const { connect: connectMongo, disconnect: disconnectMongo } = require('./mongodb/connection');
const leadsRoutes       = require('./api/routes/leadsRoutes');
const callRoutes        = require('./api/routes/callRoutes');
const voiceFlowRoutes   = require('./routes/voiceFlowRoutes');
const audioRoutes       = require('./routes/audioRoutes');
const testAudioRoutes   = require('./routes/testAudioRoutes');
const webhookRoutes     = require('./routes/webhookRoutes');
const exotelTestRoutes  = require('./routes/exotelTestRoutes');
const startupDiagnostics = require('./startup/diagnostics');

// ─── App Setup ────────────────────────────────────────────────────────────────

const app = express();
const PORT = process.env.PORT || 3000;

// ─── Middleware ───────────────────────────────────────────────────────────────

app.use(express.json());
app.use(express.urlencoded({ extended: false }));
app.use(cors());

// HTTP request logging via Morgan → Winston
app.use(
  morgan('combined', {
    stream: { write: (msg) => logger.info(msg.trim()) },
  })
);

// ─── Exotel Request Diagnostics Middleware ────────────────────────────────────
// Logs every request to /audio and /voice-flow so we can detect Exotel's
// user-agent, IP, whether it sends Range headers, etc.

app.use((req, res, next) => {
  const isExotelPath =
    req.path.startsWith('/audio') ||
    req.path.startsWith('/voice-flow') ||
    req.path.startsWith('/test-exotel') ||
    req.path.startsWith('/debug');

  if (isExotelPath) {
    logger.info('[ExotelDiag] Incoming request', {
      method:      req.method,
      path:        req.path,
      ip:          req.ip || req.connection?.remoteAddress,
      userAgent:   req.headers['user-agent'] || 'UNKNOWN',
      range:       req.headers['range']      || 'NONE',
      accept:      req.headers['accept']     || 'not set',
      host:        req.headers['host'],
      referer:     req.headers['referer']    || 'none',
      timestamp:   new Date().toISOString(),
    });
  }

  // Remove compression negotiation headers on audio paths so no middleware
  // or proxy accidentally gzip/brotli-encodes the WAV bytes.
  if (req.path.startsWith('/audio')) {
    req.headers['accept-encoding'] = 'identity';
  }

  next();
});

// ─── Health Check ─────────────────────────────────────────────────────────────

app.get('/health', (_req, res) => {
  res.status(200).json({
    status:    'ok',
    service:   'ai-calling-backend',
    timestamp: new Date().toISOString(),
  });
});

// ─── Routes ───────────────────────────────────────────────────────────────────

// Core API
app.use('/api/leads',  leadsRoutes);
app.use('/api/call',   callRoutes);

// Audio — MUST use dedicated buffer-serving controller (not express.static)
app.use('/audio',      audioRoutes);

// Exotel voice flow (XML) + call-status webhook
app.use('/voice-flow', voiceFlowRoutes);

// Exotel recording + status webhooks
app.use('/webhook',    webhookRoutes);

// Diagnostics / test endpoints (keep mounted in production — useful for ops)
app.use('/',           exotelTestRoutes);  // /test-exotel-playback, /debug/audio-report
app.use('/',           testAudioRoutes);   // /test-audio

// ─── 404 Handler ──────────────────────────────────────────────────────────────

app.use((_req, res) => {
  res.status(404).json({ success: false, message: 'Route not found' });
});

// ─── Global Error Handler ─────────────────────────────────────────────────────

// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  logger.error('Unhandled error', { error: err.message, stack: err.stack });
  res.status(500).json({ success: false, message: 'Internal server error' });
});

// ─── Bootstrap ────────────────────────────────────────────────────────────────

async function bootstrap() {
  let server;

  try {
    await connectMongo();

    server = app.listen(PORT, () => {
      logger.info(`Backend server running on http://localhost:${PORT}`);
      logger.info(`Environment: ${process.env.NODE_ENV || 'development'}`);
      logger.info(`Public base URL: ${process.env.BASE_URL || 'not configured'}`);
      logger.info('Audio serving: full-buffer 200 (no Accept-Ranges, no 206)');
    });

    await startupDiagnostics.validateEnvironment();

    // ─── Graceful Shutdown ─────────────────────────────────────────────────
    const shutdown = async (signal) => {
      logger.info(`${signal} received — shutting down gracefully`);
      server.close(async () => {
        await disconnectMongo();
        logger.info('Server closed');
        process.exit(0);
      });
    };

    process.on('SIGTERM', () => shutdown('SIGTERM'));
    process.on('SIGINT',  () => shutdown('SIGINT'));
  } catch (error) {
    logger.error('Bootstrap failed', { error: error.message });

    if (server) {
      server.close(() => process.exit(1));
    } else {
      process.exit(1);
    }
  }
}

bootstrap();
