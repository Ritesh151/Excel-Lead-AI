/**
 * audioRoutes.js
 * Exotel-compatible WAV file serving.
 *
 * CRITICAL: res.sendFile() and express.static() both honor Range requests
 * and emit 206 Partial Content, which breaks Exotel audio playback.
 * This controller reads the FULL file into a buffer and sends it in ONE
 * complete HTTP 200 response with explicit Content-Length.
 *
 * Rules enforced:
 *   - NO Accept-Ranges header (disables range negotiation)
 *   - NO Transfer-Encoding: chunked (explicit Content-Length prevents it)
 *   - NO compression (raw bytes only)
 *   - NO express.static / res.sendFile
 *   - ALWAYS HTTP 200 full delivery
 *   - WAV RIFF header validated before serving
 */

const express = require('express');
const path = require('path');
const fs = require('fs');
const logger = require('../utils/logger');

const router = express.Router();

const audioDir = path.resolve(
  process.env.AUDIO_DIR || path.join(__dirname, '../../audio')
);

// Only WAV files are allowed
const ALLOWED_EXT = new Set(['.wav']);

/**
 * Validate a WAV buffer has a valid RIFF/WAVE header and is telephony-compatible.
 * Returns { valid, errors, metadata }
 */
function validateWavBuffer(buffer) {
  const errors = [];
  const metadata = {};

  if (buffer.length < 44) {
    return { valid: false, errors: ['Buffer too small to be a WAV file'], metadata };
  }

  const riff = buffer.toString('ascii', 0, 4);
  const wave = buffer.toString('ascii', 8, 12);

  if (riff !== 'RIFF') {
    errors.push(`Invalid RIFF header: got "${riff}"`);
  }
  if (wave !== 'WAVE') {
    errors.push(`Invalid WAVE chunk: got "${wave}"`);
  }

  if (errors.length > 0) {
    return { valid: false, errors, metadata };
  }

  // Parse fmt chunk
  let offset = 12;
  let foundFmt = false;

  while (offset + 8 <= buffer.length) {
    const chunkId = buffer.toString('ascii', offset, offset + 4);
    const chunkSize = buffer.readUInt32LE(offset + 4);

    if (chunkId === 'fmt ') {
      metadata.audioFormat  = buffer.readUInt16LE(offset + 8);
      metadata.channels     = buffer.readUInt16LE(offset + 10);
      metadata.sampleRate   = buffer.readUInt32LE(offset + 12);
      metadata.bitsPerSample = buffer.readUInt16LE(offset + 22);
      foundFmt = true;
      break;
    }

    offset += 8 + chunkSize;
    // Safety: prevent infinite loop on malformed chunk sizes
    if (chunkSize === 0) break;
  }

  if (!foundFmt) {
    errors.push('WAV fmt chunk not found');
    return { valid: false, errors, metadata };
  }

  if (metadata.audioFormat !== 1) {
    errors.push(`Audio format must be PCM (1), got ${metadata.audioFormat}`);
  }
  if (metadata.channels !== 1) {
    errors.push(`Must be mono (1 channel), got ${metadata.channels}`);
  }
  if (metadata.sampleRate !== 8000) {
    errors.push(`Sample rate must be 8000 Hz, got ${metadata.sampleRate}`);
  }
  if (metadata.bitsPerSample !== 16) {
    errors.push(`Bits per sample must be 16, got ${metadata.bitsPerSample}`);
  }

  return { valid: errors.length === 0, errors, metadata };
}

/**
 * Log every incoming audio request with Exotel-relevant details.
 */
function logAudioRequest(req, fileName) {
  logger.info('[AudioServe] Incoming audio request', {
    fileName,
    method: req.method,
    url: req.originalUrl,
    ip: req.ip || req.connection?.remoteAddress,
    'user-agent': req.headers['user-agent'] || 'unknown',
    range: req.headers['range'] || 'NONE (good)',
    accept: req.headers['accept'] || 'not set',
    host: req.headers['host'],
    referer: req.headers['referer'] || 'none',
    timestamp: new Date().toISOString(),
  });
}

/**
 * GET /audio/:fileName
 * Serves the WAV file as a complete HTTP 200 response.
 * Range requests are IGNORED — full buffer is always returned.
 */
router.get('/:fileName', (req, res) => {
  const { fileName } = req.params;

  logAudioRequest(req, fileName);

  // ── Security: path traversal guard ─────────────────────────────────────────
  if (!fileName || fileName.includes('..') || fileName.includes('/') || fileName.includes('\\')) {
    logger.warn('[AudioServe] Path traversal attempt blocked', { fileName });
    return res.status(400).json({ success: false, message: 'Invalid file name' });
  }

  // ── Extension whitelist ─────────────────────────────────────────────────────
  const ext = path.extname(fileName).toLowerCase();
  if (!ALLOWED_EXT.has(ext)) {
    logger.warn('[AudioServe] Disallowed file extension', { fileName, ext });
    return res.status(400).json({ success: false, message: 'Only WAV files are supported' });
  }

  const filePath = path.join(audioDir, fileName);

  // ── File existence check ────────────────────────────────────────────────────
  if (!fs.existsSync(filePath)) {
    logger.error('[AudioServe] File not found', { filePath });
    return res.status(404).json({ success: false, message: `Audio file not found: ${fileName}` });
  }

  // ── Read FULL file into memory buffer ───────────────────────────────────────
  let buffer;
  try {
    buffer = fs.readFileSync(filePath);
  } catch (readErr) {
    logger.error('[AudioServe] Failed to read audio file', { filePath, error: readErr.message });
    return res.status(500).json({ success: false, message: 'Failed to read audio file' });
  }

  // ── Validate WAV header ─────────────────────────────────────────────────────
  const validation = validateWavBuffer(buffer);
  if (!validation.valid) {
    logger.error('[AudioServe] WAV validation failed — refusing to serve', {
      fileName,
      errors: validation.errors,
    });
    return res.status(500).json({
      success: false,
      message: 'WAV file failed telephony validation',
      errors: validation.errors,
    });
  }

  logger.info('[AudioServe] WAV validated — serving full buffer', {
    fileName,
    size: buffer.length,
    metadata: validation.metadata,
  });

  // ── Send FULL buffer, HTTP 200, no ranges ───────────────────────────────────
  //
  // Key points:
  //   1. Remove Accept-Ranges so Exotel's fetcher never tries a range request.
  //   2. Set explicit Content-Length so Node.js does NOT chunk-encode.
  //   3. Set Cache-Control to public so Exotel caches on its CDN layer.
  //   4. Do NOT call res.end() with a stream — always .send(buffer).
  //
  res.removeHeader('Accept-Ranges');   // eliminate 206 negotiation
  res.removeHeader('Transfer-Encoding'); // belt-and-suspenders

  res.set({
    'Content-Type':   'audio/wav',
    'Content-Length': buffer.length,          // explicit length → no chunking
    'Cache-Control':  'public, max-age=3600', // Exotel may cache; that's fine
    'X-Content-Type-Options': 'nosniff',
    'X-Exotel-Compat': 'full-buffer-200',     // debug marker visible in logs
  });

  // Ignore any incoming Range header — serve full file regardless
  if (req.headers['range']) {
    logger.warn('[AudioServe] Range request IGNORED — forcing full 200 delivery', {
      fileName,
      rangeHeader: req.headers['range'],
    });
  }

  return res.status(200).send(buffer);
});

module.exports = router;
