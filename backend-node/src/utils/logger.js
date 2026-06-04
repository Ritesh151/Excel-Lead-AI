/**
 * logger.js
 * Centralized Winston logger with structured JSON output,
 * rotating file sinks, and coloured console in development.
 *
 * Log files:
 *   logs/error.log    — ERROR level only
 *   logs/combined.log — all levels
 *   logs/requests.log — HTTP request log (written via morgan stream)
 *   logs/db.log       — database operation log (write via logger.db())
 *   logs/ws.log       — WebSocket event log   (write via logger.ws())
 *   logs/api.log      — outbound API call log  (write via logger.api())
 */

'use strict';

require('dotenv').config();

const fs   = require('fs');
const path = require('path');
const { createLogger, format, transports } = require('winston');

// ── Ensure log directory exists ───────────────────────────────────────────────
const LOG_DIR = path.resolve(process.env.LOG_DIR || path.join(__dirname, '../../../logs'));
if (!fs.existsSync(LOG_DIR)) {
  fs.mkdirSync(LOG_DIR, { recursive: true });
}

// ── Shared formats ────────────────────────────────────────────────────────────
const jsonFormat = format.combine(
  format.timestamp({ format: 'YYYY-MM-DD HH:mm:ss.SSS' }),
  format.errors({ stack: true }),
  format.splat(),
  format.json(),
);

const consoleFormat = format.combine(
  format.colorize({ all: true }),
  format.timestamp({ format: 'HH:mm:ss.SSS' }),
  format.printf(({ timestamp, level, message, ...meta }) => {
    const extra = Object.keys(meta).length
      ? ' ' + JSON.stringify(meta, null, 0)
      : '';
    return `[${timestamp}] ${level}: ${message}${extra}`;
  }),
);

// ── File transport factory ────────────────────────────────────────────────────
function fileTransport(filename, level) {
  const opts = {
    filename: path.join(LOG_DIR, filename),
    maxsize: 10 * 1024 * 1024,  // 10 MB
    maxFiles: 5,
    tailable: true,
    format: jsonFormat,
  };
  if (level) opts.level = level;
  return new transports.File(opts);
}

// ── Main logger ───────────────────────────────────────────────────────────────
const logger = createLogger({
  level: process.env.LOG_LEVEL || (process.env.NODE_ENV === 'production' ? 'info' : 'debug'),
  transports: [
    fileTransport('error.log', 'error'),
    fileTransport('combined.log'),
  ],
  exitOnError: false,
});

if (process.env.NODE_ENV !== 'production') {
  logger.add(new transports.Console({ format: consoleFormat }));
}

// ── Specialised child loggers ─────────────────────────────────────────────────
const requestLogger = createLogger({
  level: 'info',
  transports: [fileTransport('requests.log')],
  exitOnError: false,
});

const dbLogger = createLogger({
  level: 'debug',
  transports: [fileTransport('db.log')],
  exitOnError: false,
});

const wsLogger = createLogger({
  level: 'debug',
  transports: [fileTransport('ws.log')],
  exitOnError: false,
});

const apiLogger = createLogger({
  level: 'debug',
  transports: [fileTransport('api.log')],
  exitOnError: false,
});

// ── Convenience methods ───────────────────────────────────────────────────────

/** Morgan-compatible stream for HTTP request logging */
logger.morganStream = {
  write(message) {
    const trimmed = message.trim();
    requestLogger.info(trimmed);
    if (process.env.NODE_ENV !== 'production') {
      // Also print to console in dev so HTTP requests are visible
      process.stdout.write(`[HTTP] ${trimmed}\n`);
    }
  },
};

/** Database operation log */
logger.db = (message, meta) => {
  dbLogger.info(message, meta || {});
};

/** WebSocket event log */
logger.ws = (message, meta) => {
  wsLogger.debug(message, meta || {});
};

/** Outbound API call log */
logger.api = (message, meta) => {
  apiLogger.info(message, meta || {});
};

module.exports = logger;
