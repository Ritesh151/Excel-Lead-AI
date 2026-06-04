/**
 * ExotelService.js
 * Cloud telephony integration with Exotel Voice API
 * Responsibilities:
 *   - Authenticate with Exotel
 *   - Make outbound calls
 *   - Handle call status tracking
 *   - Retry failed attempts
 *   - Validate Exotel responses
 */

const axios = require('axios');
const logger = require('../utils/logger');

class ExotelService {
  constructor() {
    this.sid = process.env.EXOTEL_SID || '';
    this.apiToken = process.env.EXOTEL_API_TOKEN || '';
    this.apiKey = process.env.EXOTEL_API_KEY || '';
    this.callerId = process.env.EXOTEL_CALLER_ID || '';
    this.baseUrl = process.env.EXOTEL_BASE_URL || 'https://api.exotel.com/v1';
    this.maxRetries = parseInt(process.env.EXOTEL_MAX_RETRIES || '3', 10);
    this.retryDelay = parseInt(process.env.EXOTEL_RETRY_DELAY || '2000', 10);

    this.validateConfig();
    this.axiosInstance = this.createAxiosInstance();
  }

  /**
   * Validate required Exotel configuration
   */
  validateConfig() {
    const required = ['sid', 'apiToken', 'apiKey', 'callerId'];
    const missing = required.filter((key) => !this[key]);

    if (missing.length > 0) {
      const error = `Missing Exotel config: ${missing.join(', ')}`;
      logger.error(error);
      throw new Error(error);
    }

    logger.info('Exotel configuration validated', {
      sid: this.sid,
      callerId: this.callerId,
    });
  }

  /**
   * Create axios instance with Exotel auth headers
   */
  createAxiosInstance() {
    const auth = Buffer.from(`${this.apiKey}:${this.apiToken}`).toString('base64');

    return axios.create({
      baseURL: this.baseUrl,
      headers: {
        'Authorization': `Basic ${auth}`,
        'Content-Type': 'application/x-www-form-urlencoded',
        'Accept': 'application/json',
      },
      timeout: 30000,
    });
  }

  /**
   * Initiate outbound call to customer
   * @param {Object} options - Call options
   * @param {string} options.phoneNumber - Customer phone number (E.164 format)
   * @param {string} options.campaignId - Campaign ID for tracking
   * @param {string} options.leadId - Lead ID for tracking
   * @returns {Promise<Object>} Call response with call_sid
   */
  async initiateCall(options) {
    const { phoneNumber, campaignId, leadId } = options;

    if (!phoneNumber) {
      throw new Error('Phone number is required');
    }

    try {
      logger.info('Initiating Exotel call', {
        phoneNumber,
        campaignId,
        leadId,
      });

      const payload = {
        From: this.callerId,
        To: phoneNumber,
        CallerId: this.callerId,
        Url: `${this.baseCallbackUrl}/voice-flow?campaignId=${campaignId}&leadId=${leadId}`,
        TimeLimit: '600', // 10 minutes max call duration
        Timeout: '30', // Ring for 30 seconds
        StatusCallbackUrl: `${this.baseCallbackUrl}/webhook/call-status`,
        StatusCallbackMethod: 'POST',
        CustomData: JSON.stringify({ campaignId, leadId }),
      };

      const response = await this.axiosInstance.post(
        `/Accounts/${this.sid}/Calls/connect.json`,
        new URLSearchParams(payload).toString()
      );

      logger.info('Call initiated successfully', {
        callSid: response.data.Call?.Sid,
        status: response.data.Call?.Status,
      });

      return {
        success: true,
        callSid: response.data.Call?.Sid,
        status: response.data.Call?.Status,
        phoneNumber,
      };
    } catch (error) {
      logger.error('Call initiation failed', {
        phoneNumber,
        error: error.message,
        response: error.response?.data,
      });
      throw error;
    }
  }

  /**
   * Get call details and status
   * @param {string} callSid - Exotel call SID
   * @returns {Promise<Object>} Call details
   */
  async getCallStatus(callSid) {
    if (!callSid) {
      throw new Error('Call SID is required');
    }

    try {
      const response = await this.axiosInstance.get(
        `/Accounts/${this.sid}/Calls/${callSid}.json`
      );

      return {
        success: true,
        callSid,
        status: response.data.Call?.Status,
        duration: response.data.Call?.Duration,
        recordings: response.data.Call?.Recordings || [],
        startTime: response.data.Call?.StartTime,
        endTime: response.data.Call?.EndTime,
      };
    } catch (error) {
      logger.error('Failed to fetch call status', {
        callSid,
        error: error.message,
      });
      throw error;
    }
  }

  /**
   * Download recording from Exotel
   * @param {string} recordingSid - Recording SID from Exotel
   * @returns {Promise<Buffer>} Audio buffer
   */
  async downloadRecording(recordingSid) {
    if (!recordingSid) {
      throw new Error('Recording SID is required');
    }

    try {
      logger.info('Downloading recording', { recordingSid });

      const response = await this.axiosInstance.get(
        `/Accounts/${this.sid}/Recordings/${recordingSid}.wav`,
        {
          responseType: 'arraybuffer',
        }
      );

      logger.info('Recording downloaded successfully', { recordingSid });
      return response.data;
    } catch (error) {
      logger.error('Failed to download recording', {
        recordingSid,
        error: error.message,
      });
      throw error;
    }
  }

  /**
   * Initiate call with retry logic
   * @param {Object} options - Call options
   * @param {number} attempt - Current attempt number
   * @returns {Promise<Object>} Call response
   */
  async initiateCallWithRetry(options, attempt = 1) {
    try {
      return await this.initiateCall(options);
    } catch (error) {
      if (attempt < this.maxRetries) {
        logger.warn('Call attempt failed, retrying', {
          attempt,
          maxRetries: this.maxRetries,
          delay: this.retryDelay,
        });

        await new Promise((resolve) => setTimeout(resolve, this.retryDelay));
        return this.initiateCallWithRetry(options, attempt + 1);
      }

      throw error;
    }
  }

  /**
   * Generate voice XML for Exotel to play audio and record response
   * @param {Object} options - XML options
   * @param {string} options.audioUrl - URL to greeting audio
   * @param {string} options.recordingWebhookUrl - URL for recording webhook
   * @returns {string} Exotel-compatible XML
   */
  generateVoiceXML(options) {
    const { audioUrl, recordingWebhookUrl } = options;

    if (!audioUrl || !recordingWebhookUrl) {
      throw new Error('audioUrl and recordingWebhookUrl are required');
    }

    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Play loop="1">${audioUrl}</Play>
  <Record
    action="${recordingWebhookUrl}"
    method="POST"
    maxLength="60"
    timeout="10"
    finishOnKey="#"
    playBeep="true">
    <Speak>Please share your response after the beep.</Speak>
  </Record>
  <Hangup/>
</Response>`;

    return xml;
  }

  get baseCallbackUrl() {
    return (process.env.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
  }

  /**
   * Validate Exotel webhook signature (if applicable)
   * @param {Object} payload - Webhook payload
   * @param {string} signature - Provided signature
   * @returns {boolean} Signature valid
   */
  validateWebhookSignature(payload, signature) {
    // Exotel provides signatures via X-Exotel-Signature header
    // For now, we'll accept all webhooks in development
    // In production, implement proper HMAC verification
    logger.warn('Webhook signature validation not yet implemented');
    return true;
  }
}

module.exports = new ExotelService();
