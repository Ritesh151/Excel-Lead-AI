/**
 * voiceFlowRoutes.js
 * Voice XML endpoint and call-status webhook.
 *
 * Endpoints:
 *   GET  /voice-flow               — fetched when customer answers
 *   POST /voice-flow/call-status   — call lifecycle status events
 */

const express  = require('express');
const logger   = require('../utils/logger');
const CallLog  = require('../mongodb/models/CallLog');

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

/**
 * Generate a minimal voice XML response.
 */
function generateVoiceXML({ audioUrl, recordingWebhookUrl }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Play>${audioUrl}</Play>
  <Record action="${recordingWebhookUrl}" maxLength="120" playBeep="false"/>
  <Hangup/>
</Response>`;
}

// ─── GET /voice-flow ──────────────────────────────────────────────────────────

/**
 * Called when the customer answers — returns voice XML to play the greeting.
 */
router.get('/', async (req, res) => {
  const { campaignId, leadId } = req.query;
  const audioUrl      = getAudioUrl();
  const webhookUrl    = getRecordingWebhookUrl();

  logger.info('[VoiceFlow] Voice flow hit', {
    campaignId,
    leadId,
    audioUrl,
    webhookUrl,
    ip:        req.ip || req.connection?.remoteAddress,
    userAgent: req.headers['user-agent'] || 'unknown',
    timestamp: new Date().toISOString(),
  });

  if (leadId && leadId !== 'test') {
    CallLog.findByIdAndUpdate(leadId, {
      status:     'answered',
      answeredAt: new Date(),
    }).catch((err) =>
      logger.warn('[VoiceFlow] DB update skipped', { leadId, error: err.message })
    );
  }

  const xml = generateVoiceXML({ audioUrl, recordingWebhookUrl: webhookUrl });

  logger.info('[VoiceFlow] Serving XML', { xml });

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
 * Receives call lifecycle events.
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
