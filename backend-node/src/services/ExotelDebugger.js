/**
 * ExotelDebugger.js
 * Diagnostics helper for Exotel XML, audio fetch and telephony validation.
 */

const axios = require('axios');
const logger = require('../utils/logger');
const { validatePublicUrl } = require('../utils/urlValidator');
const { validateTelephonyWav } = require('../audio/audioValidator');
const ExotelService = require('./ExotelService');

class ExotelDebugger {
  constructor() {
    this.timeout = 20000;
  }

  async fetchAudioHeaders(audioUrl) {
    try {
      const response = await axios.head(audioUrl, {
        timeout: this.timeout,
        maxRedirects: 5,
        validateStatus: (status) => status >= 200 && status < 400,
      });

      return {
        status: response.status,
        headers: response.headers,
      };
    } catch (error) {
      return {
        status: error.response?.status || null,
        headers: error.response?.headers || null,
        error: error.message,
      };
    }
  }

  async fetchAudioSample(audioUrl) {
    try {
      const response = await axios.get(audioUrl, {
        timeout: this.timeout,
        maxRedirects: 5,
        responseType: 'arraybuffer',
        headers: {
          Range: 'bytes=0-63',
        },
        validateStatus: (status) => status >= 200 && status < 400,
      });

      return {
        status: response.status,
        headers: response.headers,
        sample: Buffer.from(response.data).slice(0, 64).toString('ascii'),
      };
    } catch (error) {
      return {
        status: error.response?.status || null,
        headers: error.response?.headers || null,
        error: error.message,
      };
    }
  }

  async validateAudioUrl(audioUrl, localFilePath) {
    const publicReport = await validatePublicUrl(audioUrl);
    const headersReport = await this.fetchAudioHeaders(audioUrl);
    const sampleReport = await this.fetchAudioSample(audioUrl);
    const localReport = await validateTelephonyWav(localFilePath);

    const diagnostics = {
      audioUrl,
      localFilePath,
      baseUrlValid: publicReport.valid,
      urlReport: publicReport,
      headerFetch: headersReport,
      sampleFetch: sampleReport,
      localWavReport: localReport,
      voiceXml: null,
    };

    logger.info('Exotel audio diagnostics', diagnostics);
    return diagnostics;
  }

  generateVoiceXml(audioUrl, recordingWebhookUrl) {
    return ExotelService.generateVoiceXML({ audioUrl, recordingWebhookUrl });
  }
}

module.exports = new ExotelDebugger();
