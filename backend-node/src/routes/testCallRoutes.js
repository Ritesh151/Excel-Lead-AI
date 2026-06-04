/**
 * testCallRoutes.js
 * Test and diagnostic endpoints for Exotel outbound calling.
 *
 * Endpoints:
 *   POST /test-call              — place a single test call via Exotel
 *   GET  /test-call/account      — validate Exotel credentials + KYC status
 *   GET  /test-call/voice-xml    — preview the XML Exotel will receive
 */

const express        = require('express');
const logger         = require('../utils/logger');
const ExotelService  = require('../services/ExotelService');
const { toExotelFormat, toE164 } = require('../services/ExotelService');
const CallLog        = require('../mongodb/models/CallLog');

const router = express.Router();

// ─── POST /test-call ──────────────────────────────────────────────────────────

/**
 * Place a single test call to a given phone number.
 *
 * Request body:
 *   { "phone": "9427047705" }       — any Indian format
 *   { "phone": "+919427047705" }
 *   { "phone": "09427047705" }
 *
 * Returns the full Exotel API response + diagnostics.
 */
router.post('/test-call', async (req, res) => {
  const { phone } = req.body || {};

  if (!phone) {
    return res.status(400).json({
      success: false,
      message: 'Request body must contain "phone" field',
      example: { phone: '9427047705' },
    });
  }

  const phoneDial = toExotelFormat(phone);
  const phoneE164 = toE164(phone);

  logger.info('[TestCall] Test call requested', {
    phoneRaw:  phone,
    phoneDial,
    phoneE164,
    baseUrl:   process.env.BASE_URL,
    callerId:  process.env.EXOTEL_CALLER_ID,
    sid:       process.env.EXOTEL_SID,
  });

  if (!phoneDial) {
    return res.status(400).json({
      success: false,
      message: `Cannot normalise "${phone}" to Exotel format (0XXXXXXXXXX). Provide a valid 10-digit Indian number.`,
      phoneRaw: phone,
    });
  }

  // Create a test CallLog entry
  let callLogId = 'test';
  try {
    const callLog = await CallLog.create({
      campaignId:   'test_campaign',
      phoneNumber:  phoneE164 || phone,
      customerName: 'Test Call',
      status:       'initiated',
      startTime:    new Date(),
    });
    callLogId = callLog._id.toString();
  } catch (err) {
    logger.warn('[TestCall] Could not create CallLog (non-fatal)', { error: err.message });
  }

  try {
    const result = await ExotelService.initiateCall({
      phoneNumber: phone,
      campaignId:  'test_campaign',
      leadId:      callLogId,
    });

    // Update CallLog with callSid
    if (callLogId !== 'test') {
      CallLog.findByIdAndUpdate(callLogId, {
        callSid:        result.callSid,
        status:         'ringing',
        exotelResponse: result,
      }).catch(() => {});
    }

    logger.info('[TestCall] ✓ Test call initiated successfully', result);

    return res.status(200).json({
      success:     true,
      message:     'Exotel call initiated — customer phone should ring shortly',
      callSid:     result.callSid,
      exotelStatus: result.status,
      phoneRaw:    phone,
      phoneDial,
      phoneE164,
      callLogId,
      voiceFlowUrl: `${ExotelService.publicBaseUrl}/voice-flow?campaignId=test_campaign&leadId=${callLogId}`,
      audioUrl:     `${ExotelService.publicBaseUrl}/audio/${process.env.AUDIO_FILE_NAME || 'greeting_telephony.wav'}`,
      exotelRaw:   result,
    });

  } catch (err) {
    logger.error('[TestCall] ✗ Test call failed', {
      code:       err.code,
      httpStatus: err.httpStatus,
      message:    err.message,
      exotelBody: err.exotelBody,
      phone,
      phoneDial,
    });

    // Update CallLog with error
    if (callLogId !== 'test') {
      CallLog.findByIdAndUpdate(callLogId, {
        status:       'failed',
        errorMessage: err.message,
      }).catch(() => {});
    }

    const httpStatus = err.httpStatus === 403 ? 403
      : err.httpStatus === 401 ? 401
      : err.httpStatus === 400 ? 400
      : 500;

    return res.status(httpStatus).json({
      success:    false,
      code:       err.code         || 'EXOTEL_ERROR',
      message:    err.message,
      httpStatus: err.httpStatus,
      exotelBody: err.exotelBody,
      phone,
      phoneDial,
      phoneE164,
      // Actionable guidance per error type
      action:
        err.code === 'EXOTEL_KYC_REQUIRED'
          ? 'Complete KYC at https://my.exotel.com → Settings → KYC'
          : err.code === 'EXOTEL_TRIAL_RESTRICTION'
          ? 'Whitelist this number at https://my.exotel.com → Numbers → Test Numbers, or upgrade account'
          : err.code === 'EXOTEL_AUTH_FAILED'
          ? 'Verify EXOTEL_API_KEY and EXOTEL_API_TOKEN in backend-node/.env'
          : 'Check logs for full detail',
    });
  }
});

// ─── GET /test-call/account ───────────────────────────────────────────────────

/**
 * Validate Exotel credentials and account KYC status without placing a call.
 * Run this first to confirm the account is ready for outbound calls.
 */
router.get('/test-call/account', async (req, res) => {
  logger.info('[TestCall] Account validation requested');

  try {
    const result = await ExotelService.validateAccount();

    return res.status(result.valid ? 200 : (result.httpStatus || 400)).json({
      sid:        process.env.EXOTEL_SID,
      callerId:   process.env.EXOTEL_CALLER_ID,
      apiBaseUrl: 'https://api.exotel.com',
      cluster:    'Singapore',
      ...result,
      action: result.valid
        ? 'Account is ready — you can place calls'
        : result.httpStatus === 403 && !result.kyc
        ? 'Complete KYC at https://my.exotel.com → Settings → KYC'
        : result.httpStatus === 401
        ? 'Fix EXOTEL_API_KEY / EXOTEL_API_TOKEN in .env'
        : result.message,
    });
  } catch (err) {
    logger.error('[TestCall] Account validation error', { error: err.message });
    return res.status(500).json({ success: false, message: err.message });
  }
});

// ─── GET /test-call/voice-xml ─────────────────────────────────────────────────

/**
 * Preview exactly what XML Exotel will receive when it fetches /voice-flow.
 * Useful to verify audio URL and webhook URL before a live call.
 */
router.get('/test-call/voice-xml', (req, res) => {
  const baseUrl     = (process.env.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
  const audioFile   = process.env.AUDIO_FILE_NAME || 'greeting_telephony.wav';
  const audioUrl    = `${baseUrl}/audio/${audioFile}`;
  const webhookUrl  = `${baseUrl}/webhook/recording`;

  const xml = ExotelService.generateVoiceXML({ audioUrl, recordingWebhookUrl: webhookUrl });

  logger.info('[TestCall] Voice XML preview requested', { audioUrl, webhookUrl });

  res.set({ 'Content-Type': 'text/xml; charset=utf-8' });
  return res.status(200).send(xml);
});

module.exports = router;
