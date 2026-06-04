/**
 * voiceFlowRoutes.js
 * Exotel voice XML endpoint and call-status webhook.
 *
 * CRITICAL: Exotel requires Content-Type: text/xml (NOT application/xml).
 * If wrong Content-Type is returned, Exotel ignores the XML and plays nothing.
 *
 * Endpoints:
 *   GET  /voice-flow               — Exotel fetches this when customer answers
 *   POST /voice-flow/call-status   — Exotel posts call lifecycle events
 */

const express  = require('express');
const logger   = require('../utils/logger');
const CallLog  = require('../mongodb/models/CallLog');
const ExotelService = require('../services/ExotelService');

const router = express.Router();

// ─── Helpers ──────────────────────────────────────────────────────────────────

function getBaseUrl() {
  return (process.env.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
}

function getAudioUrl() {
  const fileName = process.env.AUDIO_FILE_NAME || 'greeting_telephony.wav';
  return `${getBaseUrl()}/audio/${fileName}`;
}

function getRecordingWebhookUrl() {
  return `${getBaseUrl()}/webhook/recording`;
}

// ─── GET /voice-flow ──────────────────────────────────────────────────────────

/**
 * Exotel calls this URL when the customer answers.
 * Must return valid ExoML (Exotel XML) with Content-Type: text/xml.
 */
router.get('/', async (req, res) => {
  const { campaignId, leadId } = req.query;
  const audioUrl      = getAudioUrl();
  const webhookUrl    = getRecordingWebhookUrl();

  // ── Full request diagnostics ────────────────────────────────────────────────
  logger.info('[VoiceFlow] ✓ VOICE FLOW HIT — Exotel fetched XML', {
    campaignId,
    leadId,
    audioUrl,
    webhookUrl,
    ip:          req.ip || req.connection?.remoteAddress,
    userAgent:   req.headers['user-agent'] || 'unknown',
    host:        req.headers['host'],
    allHeaders:  req.headers,
    timestamp:   new Date().toISOString(),
  });

  // ── Update CallLog status ───────────────────────────────────────────────────
  if (leadId && leadId !== 'test') {
    CallLog.findByIdAndUpdate(leadId, {
      status:     'answered',
      answeredAt: new Date(),
    }).catch((err) =>
      logger.warn('[VoiceFlow] DB update skipped', { leadId, error: err.message })
    );
  }

  // ── Generate ExoML ──────────────────────────────────────────────────────────
  const xml = ExotelService.generateVoiceXML({ audioUrl, recordingWebhookUrl: webhookUrl });

  logger.info('[VoiceFlow] Serving XML', { xml });

  // CRITICAL: Content-Type MUST be text/xml — Exotel rejects application/xml
  res.set({
    'Content-Type':  'text/xml; charset=utf-8',
    'Cache-Control': 'no-cache, no-store, must-revalidate',
    Pragma:          'no-cache',
    Expires:         '0',
  });

  return res.status(200).send(xml);
});

// ─── POST /voice-flow/call-status ─────────────────────────────────────────────

/**
 * Exotel posts call lifecycle events here.
 * Status values: ringing | in-progress | completed | failed | busy | no-answer
 */
router.post('/call-status', async (req, res) => {
  const { CallSid, Status, Duration, StartTime, EndTime, To, From } = req.body;

  logger.info('[VoiceFlow] Call status webhook', {
    CallSid, Status, Duration, To, From,
    body: req.body,
  });

  if (CallSid) {
    const update = {};
    if (Status)    update.status    = Status.toLowerCase();
    if (Duration)  update.duration  = parseInt(Duration, 10);
    if (StartTime) update.startTime = new Date(StartTime);
    if (EndTime)   update.endTime   = new Date(EndTime);

    CallLog.findOneAndUpdate({ callSid: CallSid }, update).catch((err) =>
      logger.warn('[VoiceFlow] call-status DB update failed', { CallSid, error: err.message })
    );
  }

  return res.status(200).json({ success: true });
});

module.exports = router;
