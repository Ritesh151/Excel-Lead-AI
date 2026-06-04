/**
 * ExotelService.js
 * Exotel Cloud Telephony — outbound calling, XML generation, recording download.
 *
 * CLUSTER NOTES (confirmed by live API test):
 *   Singapore cluster : https://api.exotel.com/v1       ← correct for this account
 *   Mumbai cluster    : https://api.in.exotel.com/v1    ← 401 for this account
 *
 * PHONE NUMBER FORMAT (Exotel v1 API):
 *   From / CallerId : ExoPhone with leading 0  e.g. 09513886363
 *   To              : Customer with leading 0  e.g. 09427047705
 *   Exotel v1 does NOT accept E.164 (+91XXXXXXXXXX) for From/CallerId.
 *   The `To` field works with both formats; we normalise to 0XXXXXXXXXX.
 *
 * KNOWN ACCOUNT STATES (live-tested):
 *   HTTP 200 → call queued
 *   HTTP 403 + "KYC compliant" → account not KYC verified yet (account-level block)
 *   HTTP 403 + "Trial" → trial account, number not whitelisted
 *   HTTP 401 → wrong cluster or invalid credentials
 *   HTTP 400 → bad payload (missing field, wrong phone format)
 */

const axios  = require('axios');
const logger = require('../utils/logger');

// ─── Phone Helpers ────────────────────────────────────────────────────────────

/**
 * Convert any Indian phone number to Exotel v1 format: 0XXXXXXXXXX (11 digits).
 * Exotel v1 API requires this format for From / To / CallerId.
 *
 * Accepts:  9427047705  |  +919427047705  |  919427047705  |  09427047705
 * Returns:  09427047705
 * Returns null if input cannot be normalised.
 */
function toExotelFormat(raw) {
  if (!raw) return null;
  let s = String(raw).replace(/[\s\-\(\)\+]/g, '');

  // Strip country code
  if (s.startsWith('91') && s.length === 12) s = s.slice(2);   // 919427047705 → 9427047705
  if (s.startsWith('0')  && s.length === 11) s = s.slice(1);   // 09427047705  → 9427047705

  if (!/^\d{10}$/.test(s)) {
    logger.warn('[ExotelPhone] Cannot normalise to Exotel format', { raw, cleaned: s });
    return null;
  }
  return '0' + s;  // → 09427047705
}

/**
 * Convert any Indian phone number to E.164 (+91XXXXXXXXXX).
 * Used for logging and MongoDB storage only — NOT sent to Exotel v1 API.
 */
function toE164(raw) {
  if (!raw) return null;
  let s = String(raw).replace(/[\s\-\(\)\+]/g, '');
  if (s.startsWith('91') && s.length === 12) return '+' + s;
  if (s.startsWith('0')  && s.length === 11) return '+91' + s.slice(1);
  if (/^\d{10}$/.test(s)) return '+91' + s;
  return null;
}


// ─── ExotelService ────────────────────────────────────────────────────────────

class ExotelService {
  constructor() {
    this.sid        = process.env.EXOTEL_SID        || '';
    this.apiKey     = process.env.EXOTEL_API_KEY    || '';
    this.apiToken   = process.env.EXOTEL_API_TOKEN  || '';
    this.callerId   = process.env.EXOTEL_CALLER_ID  || '';   // e.g. 09513886363
    this.maxRetries = parseInt(process.env.EXOTEL_MAX_RETRIES  || '1', 10);
    this.retryDelay = parseInt(process.env.EXOTEL_RETRY_DELAY  || '3000', 10);

    // Correct base URL — Singapore cluster, confirmed working with this account
    this.apiBaseUrl = 'https://api.exotel.com';

    this._validateConfig();
    this._http = this._createHttpClient();
  }

  // ─── Internal: config ─────────────────────────────────────────────────────

  _validateConfig() {
    const missing = ['sid', 'apiKey', 'apiToken', 'callerId']
      .filter((k) => !this[k]);

    if (missing.length > 0) {
      throw new Error(`ExotelService: missing required env vars: ${missing.join(', ')}`);
    }

    const callerFormatted = toExotelFormat(this.callerId);
    if (!callerFormatted) {
      throw new Error(`ExotelService: EXOTEL_CALLER_ID "${this.callerId}" is not a valid Indian number`);
    }
    // Normalise stored callerId to Exotel format (0XXXXXXXXXX)
    this.callerId = callerFormatted;

    logger.info('[ExotelService] Configured', {
      sid:        this.sid,
      callerId:   this.callerId,
      apiBaseUrl: this.apiBaseUrl,
      cluster:    'Singapore (api.exotel.com)',
    });
  }

  _createHttpClient() {
    const auth = Buffer.from(`${this.apiKey}:${this.apiToken}`).toString('base64');

    return axios.create({
      baseURL: this.apiBaseUrl,
      headers: {
        Authorization:  `Basic ${auth}`,
        'Content-Type': 'application/x-www-form-urlencoded',
        Accept:         'application/json',
      },
      timeout: 30000,
      // Do not throw on 4xx/5xx — we handle status codes manually for rich logging
      validateStatus: () => true,
    });
  }

  // ─── Internal: public base URL ────────────────────────────────────────────

  get publicBaseUrl() {
    // Re-read at call time — startup diagnostics may have updated BASE_URL
    // with the live ngrok URL after this singleton was constructed.
    return (process.env.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
  }

  // ─── Outbound Call ────────────────────────────────────────────────────────

  /**
   * Place an outbound call via Exotel v1 API.
   *
   * Exotel flow:
   *   1. POST /v1/Accounts/{SID}/Calls/connect.json
   *   2. Exotel calls `callerId` (your ExoPhone) first — ignore, it's internal
   *   3. Exotel then calls `phoneNumber` (customer)
   *   4. When customer answers, Exotel GET-fetches `Url` (voice-flow)
   *   5. voice-flow returns XML: <Play> audio URL </Play>
   *
   * @param {Object} opts
   * @param {string} opts.phoneNumber  - Customer phone (any Indian format)
   * @param {string} opts.campaignId   - Campaign identifier for tracking
   * @param {string} opts.leadId       - CallLog MongoDB _id (sent as CustomData)
   */
  async initiateCall({ phoneNumber, campaignId, leadId }) {
    if (!phoneNumber) throw new Error('phoneNumber is required');

    // Normalise customer number to Exotel format
    const toFormatted = toExotelFormat(phoneNumber);
    if (!toFormatted) {
      throw new Error(`Invalid phone number: "${phoneNumber}" — cannot normalise to Exotel format`);
    }

    const voiceFlowUrl      = `${this.publicBaseUrl}/voice-flow?campaignId=${encodeURIComponent(campaignId || '')}&leadId=${encodeURIComponent(leadId || '')}`;
    const statusCallbackUrl = `${this.publicBaseUrl}/webhook/call-status`;

    const endpoint = `/v1/Accounts/${this.sid}/Calls/connect.json`;

    const payload = {
      From:                 this.callerId,    // ExoPhone: 09513886363
      To:                   toFormatted,      // Customer: 09427047705
      CallerId:             this.callerId,    // Same ExoPhone
      Url:                  voiceFlowUrl,     // Exotel fetches this when customer answers
      CallType:             'trans',          // trans = transactional (outbound to customer)
      TimeLimit:            '120',            // max call duration in seconds
      Timeout:              '30',             // ring timeout before treating as no-answer
      StatusCallbackUrl:    statusCallbackUrl,
      StatusCallbackMethod: 'POST',
      CustomData:           JSON.stringify({ campaignId, leadId }),
    };

    // ── Full request logging ──────────────────────────────────────────────────
    logger.info('[ExotelService] ▶ Initiating outbound call', {
      endpoint:       `POST ${this.apiBaseUrl}${endpoint}`,
      From:           payload.From,
      To:             payload.To,
      CallerId:       payload.CallerId,
      Url:            payload.Url,
      CallType:       payload.CallType,
      Timeout:        payload.Timeout,
      TimeLimit:      payload.TimeLimit,
      StatusCallback: payload.StatusCallbackUrl,
      phoneRaw:       phoneNumber,
      phoneE164:      toE164(phoneNumber),
      campaignId,
      leadId,
    });

    const response = await this._http.post(
      endpoint,
      new URLSearchParams(payload).toString()
    );

    // ── Full response logging ─────────────────────────────────────────────────
    logger.info('[ExotelService] ◀ Exotel API response', {
      httpStatus: response.status,
      body:       response.data,
      endpoint,
      To:         payload.To,
    });

    // ── Error handling with actionable messages ───────────────────────────────
    if (response.status !== 200 && response.status !== 201) {
      this._handleApiError(response, phoneNumber);
    }

    const call = response.data?.Call || {};

    logger.info('[ExotelService] ✓ Call queued successfully', {
      CallSid:    call.Sid,
      Status:     call.Status,
      To:         call.To,
      From:       call.From,
      phoneE164:  toE164(phoneNumber),
    });

    return {
      success:    true,
      callSid:    call.Sid,
      status:     call.Status,
      phoneRaw:   phoneNumber,
      phoneDial:  toFormatted,
      phoneE164:  toE164(phoneNumber),
    };
  }

  /**
   * Parse Exotel error responses and throw with a clear actionable message.
   */
  _handleApiError(response, phoneNumber) {
    const status  = response.status;
    const body    = response.data  || {};
    const message = body?.RestException?.Message || body?.message || JSON.stringify(body);

    // ── KYC not complete ─────────────────────────────────────────────────────
    if (status === 403 && message.toLowerCase().includes('kyc')) {
      const err = new Error(
        `[Exotel 403 KYC] Account "${this.sid}" is not KYC verified. ` +
        `Login to https://my.exotel.com → Settings → KYC and complete verification. ` +
        `No outbound calls are possible until KYC is approved.`
      );
      err.code = 'EXOTEL_KYC_REQUIRED';
      err.httpStatus = 403;
      err.exotelBody = body;
      throw err;
    }

    // ── Trial account — number not whitelisted ────────────────────────────────
    if (status === 403 && (message.toLowerCase().includes('trial') || message.toLowerCase().includes('whitelist'))) {
      const err = new Error(
        `[Exotel 403 Trial] "${phoneNumber}" is not whitelisted on the trial account. ` +
        `Go to https://my.exotel.com → Numbers → Test Numbers and add this number, ` +
        `or upgrade to a paid account for unrestricted dialing.`
      );
      err.code = 'EXOTEL_TRIAL_RESTRICTION';
      err.httpStatus = 403;
      err.exotelBody = body;
      throw err;
    }

    // ── Authentication failure ────────────────────────────────────────────────
    if (status === 401) {
      const err = new Error(
        `[Exotel 401 Auth] Authentication failed for SID "${this.sid}". ` +
        `Verify EXOTEL_API_KEY and EXOTEL_API_TOKEN in .env. ` +
        `Also confirm your account is on the Singapore cluster (api.exotel.com).`
      );
      err.code = 'EXOTEL_AUTH_FAILED';
      err.httpStatus = 401;
      err.exotelBody = body;
      throw err;
    }

    // ── Bad request (wrong payload) ───────────────────────────────────────────
    if (status === 400) {
      const err = new Error(
        `[Exotel 400 BadRequest] ${message}. ` +
        `Check: From="${this.callerId}", To="${toExotelFormat(phoneNumber)}", ` +
        `Url must be a reachable HTTPS URL.`
      );
      err.code = 'EXOTEL_BAD_REQUEST';
      err.httpStatus = 400;
      err.exotelBody = body;
      throw err;
    }

    // ── Generic error ─────────────────────────────────────────────────────────
    const err = new Error(`[Exotel ${status}] ${message}`);
    err.code = 'EXOTEL_API_ERROR';
    err.httpStatus = status;
    err.exotelBody = body;
    throw err;
  }

  /**
   * Initiate call with limited retry (only on network/5xx errors, not on 4xx).
   */
  async initiateCallWithRetry(options, attempt = 1) {
    try {
      return await this.initiateCall(options);
    } catch (error) {
      const isRetryable = !error.httpStatus || error.httpStatus >= 500;

      if (isRetryable && attempt < this.maxRetries) {
        logger.warn('[ExotelService] Retrying call after error', {
          attempt,
          maxRetries: this.maxRetries,
          error:      error.message,
          code:       error.code,
        });
        await new Promise((resolve) => setTimeout(resolve, this.retryDelay));
        return this.initiateCallWithRetry(options, attempt + 1);
      }

      // Log full error detail before re-throwing
      logger.error('[ExotelService] Call failed — not retrying', {
        code:       error.code,
        httpStatus: error.httpStatus,
        message:    error.message,
        exotelBody: error.exotelBody,
        phone:      options.phoneNumber,
      });

      throw error;
    }
  }

  // ─── Account Validation ───────────────────────────────────────────────────

  /**
   * Validate Exotel credentials and account status without placing a call.
   * Use this at startup to detect KYC issues early.
   * Returns { valid, kyc, message, httpStatus }
   */
  async validateAccount() {
    const endpoint = `/v1/Accounts/${this.sid}.json`;
    logger.info('[ExotelService] Validating account', { endpoint });

    const response = await this._http.get(endpoint);

    logger.info('[ExotelService] Account validation response', {
      httpStatus: response.status,
      body:       response.data,
    });

    if (response.status === 200) {
      return { valid: true, kyc: true, httpStatus: 200, message: 'Account active' };
    }

    if (response.status === 401) {
      return {
        valid: false, kyc: false, httpStatus: 401,
        message: 'Authentication failed — check EXOTEL_API_KEY and EXOTEL_API_TOKEN',
      };
    }

    if (response.status === 403) {
      const msg = response.data?.RestException?.Message || '';
      return {
        valid: false,
        kyc: !msg.toLowerCase().includes('kyc'),
        httpStatus: 403,
        message: msg || 'Account access denied',
      };
    }

    return {
      valid: false, kyc: false,
      httpStatus: response.status,
      message: JSON.stringify(response.data),
    };
  }

  // ─── Call Status ──────────────────────────────────────────────────────────

  async getCallStatus(callSid) {
    if (!callSid) throw new Error('callSid is required');

    const response = await this._http.get(
      `/v1/Accounts/${this.sid}/Calls/${callSid}.json`
    );

    if (response.status !== 200) {
      throw new Error(`Exotel getCallStatus ${response.status}: ${JSON.stringify(response.data)}`);
    }

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

  // ─── Recording Download ───────────────────────────────────────────────────

  async downloadRecording(recordingSid) {
    if (!recordingSid) throw new Error('recordingSid is required');

    logger.info('[ExotelService] Downloading recording', { recordingSid });

    const response = await this._http.get(
      `/v1/Accounts/${this.sid}/Recordings/${recordingSid}.wav`,
      { responseType: 'arraybuffer', validateStatus: () => true }
    );

    if (response.status !== 200) {
      throw new Error(`Recording download failed ${response.status}: ${recordingSid}`);
    }

    logger.info('[ExotelService] Recording downloaded', { recordingSid, bytes: response.data.byteLength });
    return response.data;
  }

  // ─── Voice XML ────────────────────────────────────────────────────────────

  /**
   * Generate Exotel-compatible ExoML (Exotel XML) response.
   *
   * Structure:
   *   <Play>  — plays greeting_telephony.wav to the customer
   *   <Record> — records customer response, POSTs webhook when done
   *   <Hangup> — ends the call
   *
   * Content-Type MUST be: text/xml (Exotel rejects application/xml)
   */
  generateVoiceXML({ audioUrl, recordingWebhookUrl } = {}) {
    const audio   = audioUrl          || `${this.publicBaseUrl}/audio/${process.env.AUDIO_FILE_NAME || 'greeting_telephony.wav'}`;
    const webhook = recordingWebhookUrl || `${this.publicBaseUrl}/webhook/recording`;

    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Play>${audio}</Play>
  <Record
    maxLength="30"
    timeout="5"
    recordingStatusCallback="${webhook}"
    recordingStatusCallbackMethod="POST"
    playBeep="false"/>
  <Hangup/>
</Response>`;

    logger.info('[ExotelService] Voice XML generated', { audio, webhook });
    return xml;
  }
}

// ─── Exports ──────────────────────────────────────────────────────────────────

module.exports = new ExotelService();
module.exports.toExotelFormat = toExotelFormat;
module.exports.toE164         = toE164;
