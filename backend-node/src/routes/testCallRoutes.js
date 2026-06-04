/**
 * testCallRoutes.js
 * Diagnostic endpoints for outbound calling (ADB/Android mode).
 *
 * Endpoints:
 *   GET  /test-call/voice-xml    — preview the voice XML that will be served
 */

const express = require('express');
const logger  = require('../utils/logger');

const router = express.Router();

// ─── GET /test-call/voice-xml ─────────────────────────────────────────────────

/**
 * Preview the XML returned by /voice-flow.
 */
router.get('/test-call/voice-xml', (req, res) => {
  const baseUrl    = (process.env.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
  const audioFile  = process.env.AUDIO_FILE_NAME || 'greeting_telephony.wav';
  const audioUrl   = `${baseUrl}/audio/${audioFile}`;
  const webhookUrl = `${baseUrl}/webhook/recording`;

  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Play>${audioUrl}</Play>
  <Record action="${webhookUrl}" maxLength="120" playBeep="false"/>
  <Hangup/>
</Response>`;

  logger.info('[TestCall] Voice XML preview requested', { audioUrl, webhookUrl });

  res.set({ 'Content-Type': 'text/xml; charset=utf-8' });
  return res.status(200).send(xml);
});

module.exports = router;
