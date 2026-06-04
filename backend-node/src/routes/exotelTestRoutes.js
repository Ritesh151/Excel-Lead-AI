/**
 * audioDebugRoutes.js
 * Audio playback test and diagnostics endpoints.
 *
 * Routes:
 *   GET /test-audio-playback   — generate minimal Play XML pointing at the WAV
 *   GET /debug/audio-report    — JSON diagnostics: file, headers, WAV metadata
 */

const express = require('express');
const path = require('path');
const fs = require('fs');
const logger = require('../utils/logger');

const router = express.Router();

// ─── Helpers ─────────────────────────────────────────────────────────────────

function getBaseUrl() {
  return (process.env.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
}

function getAudioFileName() {
  return process.env.AUDIO_FILE_NAME || 'greeting_telephony.wav';
}

function getAudioDir() {
  return path.resolve(
    process.env.AUDIO_DIR || path.join(__dirname, '../../audio')
  );
}

function getAudioFilePath() {
  return path.join(getAudioDir(), getAudioFileName());
}

function getAudioUrl() {
  return `${getBaseUrl()}/audio/${getAudioFileName()}`;
}

/**
 * Parse WAV header from a buffer.
 * Returns metadata object or throws on invalid file.
 */
function parseWavMeta(buffer) {
  if (buffer.length < 44) throw new Error('Buffer too small');

  const riff = buffer.toString('ascii', 0, 4);
  const wave = buffer.toString('ascii', 8, 12);

  if (riff !== 'RIFF' || wave !== 'WAVE') {
    throw new Error(`Invalid RIFF/WAVE header: "${riff}"/"${wave}"`);
  }

  let offset = 12;
  while (offset + 8 <= buffer.length) {
    const chunkId   = buffer.toString('ascii', offset, offset + 4);
    const chunkSize = buffer.readUInt32LE(offset + 4);

    if (chunkId === 'fmt ') {
      return {
        audioFormat:   buffer.readUInt16LE(offset + 8),
        channels:      buffer.readUInt16LE(offset + 10),
        sampleRate:    buffer.readUInt32LE(offset + 12),
        byteRate:      buffer.readUInt32LE(offset + 16),
        blockAlign:    buffer.readUInt16LE(offset + 20),
        bitsPerSample: buffer.readUInt16LE(offset + 22),
      };
    }

    offset += 8 + chunkSize;
    if (chunkSize === 0) break;
  }

  throw new Error('WAV fmt chunk not found');
}

// ─── Routes ──────────────────────────────────────────────────────────────────

/**
 * GET /test-audio-playback
 * Returns minimal Play XML pointing at the WAV file for quick playback testing.
 */
router.get('/test-audio-playback', (req, res) => {
  const audioUrl = getAudioUrl();

  logger.info('[AudioPlayback] Request received', {
    ip:          req.ip || req.connection?.remoteAddress,
    'user-agent': req.headers['user-agent'] || 'unknown',
    audioUrl,
    timestamp:   new Date().toISOString(),
  });

  const filePath  = getAudioFilePath();
  const fileExists = fs.existsSync(filePath);
  let fileSize = null;

  if (fileExists) {
    try { fileSize = fs.statSync(filePath).size; } catch (_) { /* ignore */ }
  }

  logger.info('[AudioPlayback] Audio file status', { filePath, fileExists, fileSize });

  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Play>${audioUrl}</Play>
  <Hangup/>
</Response>`;

  logger.info('[AudioPlayback] Serving XML', { xml });

  res.set({
    'Content-Type': 'application/xml; charset=utf-8',
    'Cache-Control': 'no-cache, no-store, must-revalidate',
  });

  return res.status(200).send(xml);
});

/**
 * GET /debug/audio-report
 * Returns a JSON diagnostics report about the telephony WAV file and server config.
 */
router.get('/debug/audio-report', (req, res) => {
  const fileName = getAudioFileName();
  const filePath = getAudioFilePath();
  const audioUrl = getAudioUrl();
  const baseUrl  = getBaseUrl();

  logger.info('[AudioReport] Diagnostics requested', { ip: req.ip, 'user-agent': req.headers['user-agent'] });

  const report = {
    timestamp:            new Date().toISOString(),
    base_url:             baseUrl,
    audio_url:            audioUrl,
    audio_file_name:      fileName,
    audio_file_path:      filePath,
    file_exists:          false,
    file_size_bytes:      null,
    content_type:         'audio/wav',
    content_length:       null,
    range_disabled:       true,
    accept_ranges_header: 'REMOVED',
    transfer_encoding:    'none (explicit Content-Length set)',
    response_code:        200,
    partial_content_206:  false,
    wav_valid:            false,
    wav_errors:           [],
    wav_metadata:         null,
    telephony_compatible: false,
    audio_safe:           false,
    serving_method:       'fs.readFileSync buffer — NOT res.sendFile / express.static',
    notes:                [],
  };

  if (!fs.existsSync(filePath)) {
    report.notes.push(`CRITICAL: Audio file not found at ${filePath}`);
    return res.status(200).json(report);
  }

  report.file_exists = true;

  let buffer;
  try {
    buffer = fs.readFileSync(filePath);
    report.file_size_bytes = buffer.length;
    report.content_length  = buffer.length;
  } catch (err) {
    report.notes.push(`ERROR: Could not read file: ${err.message}`);
    return res.status(200).json(report);
  }

  try {
    const meta = parseWavMeta(buffer);
    report.wav_metadata = meta;
    report.wav_valid = true;

    const errors = [];
    if (meta.audioFormat !== 1)   errors.push(`audioFormat must be 1 (PCM), got ${meta.audioFormat}`);
    if (meta.channels !== 1)      errors.push(`channels must be 1 (mono), got ${meta.channels}`);
    if (meta.sampleRate !== 8000) errors.push(`sampleRate must be 8000, got ${meta.sampleRate}`);
    if (meta.bitsPerSample !== 16) errors.push(`bitsPerSample must be 16, got ${meta.bitsPerSample}`);

    report.wav_errors = errors;
    report.telephony_compatible = errors.length === 0;
  } catch (err) {
    report.wav_valid = false;
    report.wav_errors = [err.message];
  }

  report.audio_safe = report.file_exists && report.wav_valid && report.telephony_compatible;

  if (report.audio_safe) {
    report.notes.push('All checks passed. Audio file is telephony-compatible.');
  } else {
    if (!report.wav_valid)           report.notes.push('WAV header is invalid.');
    if (!report.telephony_compatible) report.notes.push('WAV is not telephony-compatible (needs 8kHz/mono/PCM-16).');
  }

  return res.status(200).json(report);
});

module.exports = router;
