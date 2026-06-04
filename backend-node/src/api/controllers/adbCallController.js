/**
 * adbCallController.js
 * HTTP handlers for ADB+Android+Kotlin GSM call flow.
 *
 * Routes:
 *   POST /api/adb/start   — Start ADB campaign
 *   POST /api/adb/stop    — Stop ADB campaign
 *   GET  /api/adb/status  — Campaign status
 *   POST /api/adb/call    — Single test call via ADB
 */

const AdbCampaignService = require('../../services/AdbCampaignService');
const logger = require('../../utils/logger');

/**
 * POST /api/adb/start
 * Start ADB campaign. Reads Excel → calls each lead via ai-python ADB engine.
 */
async function startAdbCampaign(req, res) {
  try {
    const { campaignName } = req.body || {};

    logger.info('[AdbController] Start campaign request', { campaignName });

    const result = await AdbCampaignService.startCampaign(campaignName || 'ADB Campaign');

    logger.info('[AdbController] Campaign started', result);

    return res.status(200).json({
      success: true,
      message: 'ADB campaign started',
      mode:    'adb+android+kotlin',
      data:    result,
    });
  } catch (error) {
    logger.error('[AdbController] Start failed', { error: error.message });
    return res.status(500).json({
      success: false,
      message: `Campaign start failed: ${error.message}`,
    });
  }
}

/**
 * POST /api/adb/stop
 * Stop the running ADB campaign.
 */
function stopAdbCampaign(req, res) {
  try {
    const result = AdbCampaignService.stopCampaign();
    return res.status(200).json({ success: true, data: result });
  } catch (error) {
    return res.status(500).json({ success: false, message: error.message });
  }
}

/**
 * GET /api/adb/status
 * Get campaign status.
 */
function getAdbStatus(req, res) {
  try {
    const status = AdbCampaignService.getCampaignStatus();
    return res.status(200).json({ success: true, data: status });
  } catch (error) {
    return res.status(500).json({ success: false, message: error.message });
  }
}

/**
 * POST /api/adb/call
 * Execute a single ADB test call.
 *
 * Body: { "phone": "+919427047705", "name": "Test User" }
 */
async function executeSingleCall(req, res) {
  const { phone, name } = req.body || {};

  if (!phone) {
    return res.status(400).json({
      success: false,
      message: 'phone is required',
      example: { phone: '+919427047705', name: 'Test User' },
    });
  }

  logger.info('[AdbController] Single call request', { phone, name });

  try {
    const aiPython = require('../../services/AiPythonClient');
    const { syncLeadFromCallResult } = require('../../services/LeadSyncService');
    const wsServer = require('../../socket/WebSocketServer');

    wsServer.emitCallStarted(phone, name || 'Test User', 'single_call');
    const result = await aiPython.executeCall(name || 'Test User', phone);

    await syncLeadFromCallResult({
      phone,
      intent: result.intent,
      transcription: result.transcription,
      recordingPath: result.recording_file,
      success: result.success,
      errorMessage: result.error,
    });
    logger.info('[AdbController] Call result', {
      phone, success: result.success, intent: result.intent,
    });

    return res.status(200).json({
      success:       result.success,
      phone,
      name:          name || 'Test User',
      intent:        result.intent,
      transcription: result.transcription,
      recording:     result.recording_file,
      error:         result.error || null,
      mode:          'adb+android',
    });
  } catch (error) {
    logger.error('[AdbController] Single call failed', { phone, error: error.message });
    return res.status(500).json({
      success: false,
      phone,
      message: error.message,
    });
  }
}

module.exports = { startAdbCampaign, stopAdbCampaign, getAdbStatus, executeSingleCall };
