/**
 * voiceFlowRoutes.js
 * Voice XML endpoint for Exotel to fetch call flow
 * Responsibilities:
 *   - Generate dynamic voice XML
 *   - Serve greeting audio URL
 *   - Handle webhook callbacks
 *   - Manage call context
 */

const express = require('express');
const logger = require('../utils/logger');
const { validatePublicUrl } = require('../utils/urlValidator');
const ExotelService = require('../services/ExotelService');
const CallLog = require('../mongodb/models/CallLog');

const router = express.Router();

/**
 * GET /voice-flow
 * Generate Exotel voice XML for call
 * Parameters:
 *   - campaignId: Campaign identifier
 *   - leadId: Lead/CallLog ID
 */
router.get('/', async (req, res) => {
  try {
    const { campaignId, leadId } = req.query;

    if (!campaignId || !leadId) {
      logger.warn('Missing voice flow parameters', { campaignId, leadId });
      return res.status(400).json({
        success: false,
        message: 'Missing campaignId or leadId',
      });
    }

    logger.info('Voice flow requested', { campaignId, leadId });

    // Update call log status
    await CallLog.findByIdAndUpdate(leadId, {
      status: 'answered',
      answeredAt: new Date(),
    });

    // Generate voice XML
    const baseUrl = process.env.BASE_URL || 'http://localhost:3000';
    const urlValidation = await validatePublicUrl(baseUrl).catch((error) => ({ valid: false, errors: [error.message] }));

    if (!urlValidation.valid) {
      logger.error('Voice flow cannot use invalid public URL', { baseUrl, urlValidation });
      const errorXml = `<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Speak>We are unable to fetch the audio prompt because the server URL is not publicly accessible. Please try again later.</Speak>
  <Hangup/>
</Response>`;
      res.set('Content-Type', 'application/xml; charset=utf-8');
      return res.status(500).send(errorXml);
    }

    const audioFileName = process.env.AUDIO_FILE_NAME || 'greeting_telephony.wav';
    const greetingAudioUrl = `${baseUrl.replace(/\/$/, '')}/audio/${encodeURIComponent(audioFileName)}`;
    const recordingWebhookUrl = `${baseUrl.replace(/\/$/, '')}/webhook/recording`;

    const voiceXml = ExotelService.generateVoiceXML({
      audioUrl: greetingAudioUrl,
      recordingWebhookUrl,
    });

    logger.info('Voice XML generated', {
      campaignId,
      leadId,
      greetingUrl: greetingAudioUrl,
    });

    // Return XML response
    res.set({
      'Content-Type': 'application/xml; charset=utf-8',
      'Cache-Control': 'no-cache, no-store, must-revalidate',
      Pragma: 'no-cache',
      Expires: '0',
    });
    res.send(voiceXml);
  } catch (error) {
    logger.error('Voice flow generation failed', {
      error: error.message,
      stack: error.stack,
    });

    // Return error XML to Exotel
    const errorXml = `<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Speak>We are experiencing technical difficulties. Please try again later.</Speak>
  <Hangup/>
</Response>`;

    res.type('application/xml');
    res.status(500).send(errorXml);
  }
});

/**
 * POST /call-status
 * Handle Exotel call status updates
 */
router.post('/call-status', async (req, res) => {
  try {
    const {
      CallSid,
      Status,
      From,
      To,
      Duration,
      StartTime,
      EndTime,
      CustomData,
    } = req.body;

    logger.info('Call status webhook received', {
      CallSid,
      Status,
      Duration,
    });

    // Update call log
    if (CallSid) {
      const updateData = {
        status: Status?.toLowerCase() || 'unknown',
      };

      if (Duration) updateData.duration = parseInt(Duration, 10);
      if (StartTime) updateData.startTime = new Date(StartTime);
      if (EndTime) updateData.endTime = new Date(EndTime);

      await CallLog.findOneAndUpdate(
        { callSid: CallSid },
        updateData
      );

      logger.info('Call log updated', { CallSid, Status });
    }

    res.status(200).json({ success: true, message: 'Status acknowledged' });
  } catch (error) {
    logger.error('Call status webhook processing failed', {
      error: error.message,
    });
    res.status(500).json({ success: false, error: error.message });
  }
});

module.exports = router;
