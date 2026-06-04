/**
 * index.js
 * Express application entry point.
 * Responsibilities:
 *   - Load environment variables
 *   - Connect to MongoDB
 *   - Register middleware
 *   - Mount API routes
 *   - Start HTTP server
 *   - Handle graceful shutdown
 */

require('dotenv').config();

const express = require('express');
const cors = require('cors');
const morgan = require('morgan');
const path = require('path');

const logger = require('./utils/logger');
const { connect: connectMongo, disconnect: disconnectMongo } = require('./mongodb/connection');
const leadsRoutes = require('./api/routes/leadsRoutes');
const callRoutes = require('./api/routes/callRoutes');
const voiceFlowRoutes = require('./routes/voiceFlowRoutes');
const audioRoutes = require('./routes/audioRoutes');
const testAudioRoutes = require('./routes/testAudioRoutes');
const webhookRoutes = require('./routes/webhookRoutes');
const startupDiagnostics = require('./startup/diagnostics');

// ─── App Setup ────────────────────────────────────────────────────────────────

const app = express();
const PORT = process.env.PORT || 3000;

// ─── Middleware ───────────────────────────────────────────────────────────────

// Parse JSON and URL-encoded bodies
app.use(express.json());
app.use(express.urlencoded({ extended: false }));

// CORS — allow all origins for MVP (tighten later)
app.use(cors());

// HTTP request logging via Morgan, piped through Winston
app.use(
  morgan('combined', {
    stream: {
      write: (message) => logger.info(message.trim()),
    },
  })
);

// ─── Health Check ─────────────────────────────────────────────────────────────

app.get('/health', (_req, res) => {
  res.status(200).json({
    status: 'ok',
    service: 'ai-calling-backend',
    timestamp: new Date().toISOString(),
  });
});

// ─── API Routes ───────────────────────────────────────────────────────────────

app.use('/api/leads', leadsRoutes);
app.use('/api/call', callRoutes);
app.use('/audio', audioRoutes);
app.use('/', testAudioRoutes);
app.use('/voice-flow', voiceFlowRoutes);
app.use('/webhook', webhookRoutes);

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
    });

    await startupDiagnostics.validateEnvironment();

    // ─── Graceful Shutdown ──────────────────────────────────────────────────
    const shutdown = async (signal) => {
      logger.info(`${signal} received — shutting down gracefully`);
      server.close(async () => {
        await disconnectMongo();
        logger.info('Server closed');
        process.exit(0);
      });
    };

    process.on('SIGTERM', () => shutdown('SIGTERM'));
    process.on('SIGINT', () => shutdown('SIGINT'));
  } catch (error) {
    logger.error('Bootstrap failed', { error: error.message });

    if (server) {
      server.close(() => {
        process.exit(1);
      });
    } else {
      process.exit(1);
    }
  }
}

bootstrap();
