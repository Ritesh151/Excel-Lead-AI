/**
 * ExotelService.js
 * Exotel Cloud Telephony integration — pure Exotel outbound calling.
 *
 * ARCHITECTURE (v2):
 *   This is the ONLY component that places outbound calls.
 *   ADB / Android / SIM calling is fully disabled.
 *
 * Responsibilities:
 *   - Initiate outbound calls via Exotel API
 *   - Generate Exotel-compatible voice XML
 *   - Fetch call status
 *   - Download call recordings
 *
 * Exotel call flow:
 *   1. initiateCall()  → Exotel API → Exotel calls customer
 *   2. Customer answers → Exotel fetches GET /voice-flow
 *   3. /voice-flow returns XML: <Play>greeting_telephony.wav</Play>
 *   4. Exotel plays greeting to customer
 *   5. Exotel records response, sends POST /webhook/recording
 *   6. backend-node downloads recording, sends to ai-python /api/call/transcribe
 */

const axios = require('axios');
const logger = require('../utils/logger');

class ExotelService {
  constructor() {
    this.sid       = process.env.EXOTEL_SID       || '';
    this.apiKey    = process.env.EXOTEL_API_KEY    || '';
    this.apiToken  = process.env.EXOTEL_API_TOKEN  || '';
    this.callerId  = process.env.EXOTEL_CALLER_ID  || '';
    this.baseUrl   = (process.env.EXOTEL_BASE_URL  || 'https://api.exotel.com/v1').replace(/\/$/, '');
    this.maxRetries  = parseInt(process.env.EXOTEL_MAX_RETRIES  || '3',  10);
    this.retryDelay  = parseInt(process.env.EXOTEL_RETRY_DELAY  || '2000', 10);

    this._validateConfig();
    this._http = this._createHttpClient();
  }

  // ─── Config ──────────────────────────────────────────────────────────────────

  _validateConfig() {
    const required = { sid: this.sid, apiKey: this.apiKey, apiToken: this.apiToken, callerId: this.callerId };
    const missing = Object.entries(required).filter(([, v]) => !v).map(([k]) => k);
    if (missing.length > 0) {
      const msg = `Missing Exotel config: ${missing.join(', ')}`;
      logger.error(msg);
      throw new Error(msg);
    }
    logger.info('ExotelService configured', { sid: this.sid, callerId: this.callerId });
  }

  _createHttpClient() {
    const auth = Buffer.from(`${this.apiKey}:${this.apiToken}`).toString('base64');
    return axios.create({
      baseURL: this.baseUrl,
      headers: {
        Authorization:  `Basic ${auth}`,
        'Content-Type': 'application/x-www-form-urlencoded',
        Accept:         'application/json',
      },
      timeout: 30000,
    });
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────────

  /**
   * Resolve the current public base URL.
   * Always reads from process.env.BASE_URL (startup diagnostics may have
   * updated it with the live ngrok URL after the constructor ran).
   */
  get publicBaseUrl() {
    return (process.env.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
  }

  // ─── Outbound Call ────────────────────────────────────────────────────────────

  /**
   * Initiate an outbound call via Exotel API.
   *
   * @param {Object}  options
   * @param {string}  options.phoneNumber  - Customer number in E.164 or 10-digit
   * @param {string}  options.campaignId   - Campaign ID for tracking
   * @param {string}  options.leadId       - Lead / CallLog MongoDB _id
   * @returns {Promise<{success, callSid, status, phoneNumber}>}
   */
  async initiateCall({ phoneNumber, campaignId, leadId }) {
    if (!phoneNumber) throw new Error('phoneNumber is required');

    const voiceFlowUrl = `${this.publicBaseUrl}/voice-flow?campaignId=${encodeURIComponent(campaignId)}&leadId=${encodeURIComponent(leadId)}`;
    const statusCallbackUrl = `${this.publicBaseUrl}/webhook/call-status`;

    const payload = {
      From:                this.callerId,
      To:                  phoneNumber,
      CallerId:            this.callerId,
      Url:                 voiceFlowUrl,       // Exotel fetches this when customer answers
      TimeLimit:           '300',              // 5 minutes max
      Timeout:             '30',              // ring for 30 seconds
      StatusCallbackUrl:   statusCallbackUrl,
      StatusCallbackMethod: 'POST',
      CustomData:          JSON.stringify({ campaignId, leadId }),
    };

    logger.info('[ExotelService] Initiating outbound call', {
      phoneNumber,
      campaignId,
      leadId,
      voiceFlowUrl,
    });

    const response = await this._http.post(
      `/Accounts/${this.sid}/Calls/connect.json`,
      new URLSearchParams(payload).toString()
    );

    const call = response.data?.Call || {};

    logger.info('[ExotelService] Call initiated', {
      callSid: call.Sid,
      status:  call.Status,
      phoneNumber,
    });

    return {
      success: true,
      callSid: call.Sid,
      status:  call.Status,
      phoneNumber,
    };
  }

  /**
   * Initiate call with automatic retry on transient failures.
   */
  async initiateCallWithRetry(options, attempt = 1) {
    try {
      return await this.initiateCall(options);
    } catch (error) {
      logger.warn('[ExotelService] Call attempt failed', {
        attempt,
        maxRetries: this.maxRetries,
        error: error.message,
        responseData: error.response?.data,
      });

      if (attempt < this.maxRetries) {
        await new Promise((resolve) => setTimeout(resolve, this.retryDelay));
        return this.initiateCallWithRetry(options, attempt + 1);
      }

      throw error;
    }
  }

  // ─── Call Status ─────────────────────────────────────────────────────────────

  async getCallStatus(callSid) {
    if (!callSid) throw new Error('callSid is required');

    const response = await this._http.get(
      `/Accounts/${this.sid}/Calls/${callSid}.json`
    );

    const call = response.data?.Call || {};
    return {
      success:    true,
      callSid,
      status:     call.Status,
      duration:   call.Duration,
      startTime:  call.StartTime,
      endTime:    call.EndTime,
      recordings: call.Recordings || [],
    };
  }

  // ─── Recording Download ───────────────────────────────────────────────────────

  async downloadRecording(recordingSid) {
    if (!recordingSid) throw new Error('recordingSid is required');

    logger.info('[ExotelService] Downloading recording', { recordingSid });

    const response = await this._http.get(
      `/Accounts/${this.sid}/Recordings/${recordingSid}.wav`,
      { responseType: 'arraybuffer' }
    );

    logger.info('[ExotelService] Recording downloaded', { recordingSid });
    return response.data;
  }

  // ─── Voice XML ────────────────────────────────────────────────────────────────

  /**
   * Generate Exotel-compatible voice XML.
   *
   * PHASE 1 (current): Play greeting + Record + Hangup.
   * The <Record> tag tells Exotel to record the customer's response and
   * POST the recording details to recordingStatusCallback.
   *
   * @param {Object} options
   * @param {string} options.audioUrl              - Public HTTPS URL to greeting WAV
   * @param {string} options.recordingWebhookUrl   - Webhook URL for recording callback
   * @returns {string} XML string
   */
  generateVoiceXML({ audioUrl, recordingWebhookUrl }) {
    if (!audioUrl) throw new Error('audioUrl is required for generateVoiceXML');

    // If no webhook URL is provided fall back to publicBaseUrl
    const webhookUrl = recordingWebhookUrl || `${this.publicBaseUrl}/webhook/recording`;

    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Play>${audioUrl}</Play>
  <Record
    maxLength="30"
    timeout="5"
    recordingStatusCallback="${webhookUrl}"
    recordingStatusCallbackMethod="POST"/>
  <Hangup/>
</Response>`;

    logger.info('[ExotelService] Voice XML generated', { audioUrl, webhookUrl });
    return xml;
  }

  // ─── Webhook Validation ───────────────────────────────────────────────────────

  validateWebhookSignature(_payload, _signature) {
    // TODO: implement HMAC validation for production
    logger.debug('[ExotelService] Webhook signature validation skipped (dev mode)');
    return true;
  }
}

module.exports = new ExotelService();
