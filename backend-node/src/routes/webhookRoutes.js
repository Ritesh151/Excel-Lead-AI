/**
 * webhookRoutes.js
 * Exotel webhook handlers for recording and status callbacks
 * Responsibilities:
 *   - Receive recording webhooks
 *   - Validate webhook payloads
 *   - Trigger recording processing
 *   - Handle retries and errors
 */

const express = require('express');
const logger = require('../utils/logger');
const RecordingService = require('../services/RecordingService');

const router = express.Router();

/**
 * POST /webhook/recording
 * Handle Exotel recording webhook
 * Exotel sends this when a call recording is ready
 */
router.post('/recording', async (req, res) => {
  try {
    const { CallSid, RecordingSid, RecordingUrl, RecordingDuration } = req.body;

    logger.info('Recording webhook received', {
      CallSid,
      RecordingSid,
      RecordingDuration,
    });

    // Validate webhook payload
    if (!CallSid || !RecordingSid || !RecordingUrl) {
      logger.warn('Invalid recording webhook payload', { body: req.body });
      return res.status(400).json({
        success: false,
        message: 'Missing required fields: CallSid, RecordingSid, RecordingUrl',
      });
    }

    // Process recording asynchronously (non-blocking response)
    RecordingService.processRecordingWebhook({
      CallSid,
      RecordingSid,
      RecordingUrl,
      RecordingDuration: parseInt(RecordingDuration || '0', 10),
    }).catch((error) => {
      logger.error('Async recording processing failed', {
        CallSid,
        error: error.message,
      });
    });

    // Return immediate acknowledgment to Exotel
    res.status(200).json({
      success: true,
      message: 'Webhook received and queued for processing',
      callSid: CallSid,
    });
  } catch (error) {
    logger.error('Recording webhook handler error', {
      error: error.message,
      stack: error.stack,
    });
    res.status(500).json({
      success: false,
      error: error.message,
    });
  }
});

/**
 * POST /webhook/call-status
 * Handle Exotel call status updates
 */
router.post('/call-status', async (req, res) => {
  try {
    const { CallSid, Status, Duration, EndTime } = req.body;

    logger.info('Call status webhook received', {
      CallSid,
      Status,
      Duration,
    });

    // Status can be: ringing, answered, completed, failed, busy, no-answer
    if (!CallSid) {
      return res.status(400).json({
        success: false,
        message: 'Missing CallSid',
      });
    }

    // Webhook processing could be added here if needed
    // For now, just acknowledge

    res.status(200).json({
      success: true,
      message: 'Status webhook acknowledged',
      callSid: CallSid,
    });
  } catch (error) {
    logger.error('Call status webhook handler error', {
      error: error.message,
    });
    res.status(500).json({
      success: false,
      error: error.message,
    });
  }
});

/**
 * POST /webhook/verify
 * Test endpoint to verify webhooks are reachable
 */
router.post('/verify', async (req, res) => {
  logger.info('Webhook verification request received');
  res.status(200).json({
    success: true,
    message: 'Webhook endpoint is reachable',
    timestamp: new Date().toISOString(),
  });
});

module.exports = router;
