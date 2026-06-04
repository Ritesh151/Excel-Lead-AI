/**
 * auth.js — optional Bearer token for internal API routes.
 * Set INTERNAL_API_TOKEN in .env to enable.
 */

'use strict';

const logger = require('../utils/logger');

const PUBLIC_PATHS = [
  '/health',
  '/webhook',
  '/voice-flow',
  '/audio',
  '/test-call',
  '/test-audio',
  '/test-exotel-playback',
];

function isPublicPath(path) {
  return PUBLIC_PATHS.some((p) => path === p || path.startsWith(`${p}/`));
}

function authMiddleware(req, res, next) {
  const token = (process.env.INTERNAL_API_TOKEN || '').trim();
  if (!token) return next();

  if (isPublicPath(req.path)) return next();

  const header = req.headers.authorization || '';
  const provided = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (provided === token) return next();

  logger.warn('[Auth] Rejected request', { path: req.path, ip: req.ip });
  return res.status(401).json({ success: false, message: 'Unauthorized' });
}

module.exports = { authMiddleware };
