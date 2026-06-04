/**
 * voiceFlowRoutes.js
 * Voice XML endpoint — Exotel calls this URL to control the call flow.
 *
 * PHASE 1 (current): Minimal XML — only <Play> + <Hangup>.
 *   Objective: Confirm the customer hears the greeting WAV.
 *   Recording / webhook logic is preserved but temporarily inactive.
 *   Restore it once audio playback is confirmed working.
 *
 * PHASE 2 (restore after confirmation):
 *   Uncomment the <Record> block inside generateVoiceXML_full().
 */

const express = require('express');
const logger = require('../utils/logger');
const CallLog = require('../mongodb/models/CallLog');

const router = express.Router();

// ─── Helpers ─────────────────────────────────────────────────────────────────

function getBaseUrl() {
  return (process.env.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
}

function getAudioUrl() {
  const baseUrl = getBaseUrl();
  const audioFileName = process.env.AUDIO_FILE_NAME || 'greeting_telephony.wav';
  // Do NOT encodeURIComponent on a clean filename — Exotel parsers can choke on %XX
  return `${baseUrl}/audio/${audioFileName}`;
}

/**
 * PHASE 1 — Minimal XML: just Play + Hangup.
 * Removes all callbacks and <Record> so that nothing can interfere
 * with Exotel downloading and playing the audio.
 */
function generateMinimalXML(audioUrl) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Play>${audioUrl}</Play>
  <Hangup/>
</Response>`;
}

/**
 * PHASE 2 — Full XML with recording.
 * Uncomment in voiceFlowRoutes.js GET '/' once audio playback is confirmed.
 */
// function generateFullXML(audioUrl, recordingWebhookUrl) {
//   return `<?xml version="1.0" encoding="UTF-8"?>
// <Response>
//   <Play>${audioUrl}</Play>
//   <Record
//     action="${recordingWebhookUrl}"
//     method="POST"
//     maxLength="60"
//     timeout="10"
//     finishOnKey="#"
//     playBeep="true"/>
//   <Hangup/>
// </Response>`;
// }

// ─── Routes ──────────────────────────────────────────────────────────────────

/**
 * GET /voice-flow
 * Exotel fetches this when a call is connected.
 * Returns minimal XML to play greeting WAV.
 */
router.get('/', async (req, res) => {
  const { campaignId, leadId } = req.query;
  const audioUrl = getAudioUrl();

  // Log Exotel's request so we can see user-agent, IP, etc.
  logger.info('[VoiceFlow] Exotel voice-flow requested', {
    campaignId,
    leadId,
    audioUrl,
    ip: req.ip || req.connection?.remoteAddress,
    'user-agent': req.headers['user-agent'] || 'unknown',
    headers: req.headers,
  });

  // Best-effort DB update — never let a DB error block XML delivery
  if (leadId) {
    CallLog.findByIdAndUpdate(leadId, {
      status: 'answered',
      answeredAt: new Date(),
    }).catch((err) =>
      logger.warn('[VoiceFlow] DB update skipped', { leadId, error: err.message })
    );
  }

  const xml = generateMinimalXML(audioUrl);

  logger.info('[VoiceFlow] Serving XML', { xml });

  res.set({
    'Content-Type': 'application/xml; charset=utf-8',
    'Cache-Control': 'no-cache, no-store, must-revalidate',
    Pragma: 'no-cache',
    Expires: '0',
  });

  return res.status(200).send(xml);
});

/**
 * POST /voice-flow/call-status
 * Exotel call status callback.
 */
router.post('/call-status', async (req, res) => {
  try {
    const { CallSid, Status, Duration, StartTime, EndTime } = req.body;

    logger.info('[VoiceFlow] Call status callback', {
      CallSid,
      Status,
      Duration,
    });

    if (CallSid) {
      const update = { status: (Status || '').toLowerCase() };
      if (Duration) update.duration = parseInt(Duration, 10);
      if (StartTime) update.startTime = new Date(StartTime);
      if (EndTime)   update.endTime   = new Date(EndTime);

      await CallLog.findOneAndUpdate({ callSid: CallSid }, update).catch((err) =>
        logger.warn('[VoiceFlow] DB update for call-status failed', { CallSid, error: err.message })
      );
    }

    return res.status(200).json({ success: true });
  } catch (err) {
    logger.error('[VoiceFlow] call-status handler error', { error: err.message });
    return res.status(500).json({ success: false });
  }
});

module.exports = router;
