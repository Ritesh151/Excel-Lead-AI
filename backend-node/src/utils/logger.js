/**
 * logger.js
 * Centralised Winston logger.
 * Writes to console (dev) and rotating log files (all envs).
 */

const { createLogger, format, transports } = require('winston');
const path = require('path');

const LOG_DIR = path.join(__dirname, '../../../logs');

const logger = createLogger({
  level: process.env.NODE_ENV === 'production' ? 'info' : 'debug',

  format: format.combine(
    format.timestamp({ format: 'YYYY-MM-DD HH:mm:ss' }),
    format.errors({ stack: true }),
    format.splat(),
    format.json()
  ),

  transports: [
    // Always write errors to their own file
    new transports.File({
      filename: path.join(LOG_DIR, 'error.log'),
      level: 'error',
    }),
    // Combined log for all levels
    new transports.File({
      filename: path.join(LOG_DIR, 'combined.log'),
    }),
  ],
});

// In non-production environments, also log to console with colour
if (process.env.NODE_ENV !== 'production') {
  logger.add(
    new transports.Console({
      format: format.combine(
        format.colorize(),
        format.printf(({ timestamp, level, message, ...meta }) => {
          const extra = Object.keys(meta).length ? ` ${JSON.stringify(meta)}` : '';
          return `[${timestamp}] ${level}: ${message}${extra}`;
        })
      ),
    })
  );
}

module.exports = logger;
