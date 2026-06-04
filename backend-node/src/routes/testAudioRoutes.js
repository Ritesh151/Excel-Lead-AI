/**
 * testAudioRoutes.js
 * Diagnostics endpoint for telephony audio and Exotel fetch validation.
 */

const express = require('express');
const path = require('path');
const axios = require('axios');
const logger = require('../utils/logger');
const { validatePublicUrl } = require('../utils/urlValidator');
const { validateTelephonyWav } = require('../audio/audioValidator');

const router = express.Router();

router.get('/test-audio', async (req, res) => {
  const baseUrl = process.env.BASE_URL || 'http://localhost:3000';
  const audioFileName = process.env.AUDIO_FILE_NAME || 'greeting_telephony.wav';
  const audioDir = path.resolve(process.env.AUDIO_DIR || path.join(__dirname, '../../audio'));
  const filePath = path.join(audioDir, audioFileName);
  const audioUrl = `${baseUrl.replace(/\/$/, '')}/audio/${encodeURIComponent(audioFileName)}`;

  const diagnostics = {
    audioFileName,
    audioUrl,
    baseUrl,
    publicUrlValidation: null,
    fileValidation: null,
    fetchHeaders: null,
    fetchSample: null,
    success: false,
  };

  try {
    diagnostics.fileValidation = await validateTelephonyWav(filePath);
    diagnostics.publicUrlValidation = await validatePublicUrl(baseUrl).catch((error) => ({ valid: false, errors: [error.message] }));
    diagnostics.urlValidation = await validatePublicUrl(audioUrl).catch((error) => ({ valid: false, errors: [error.message] }));

    diagnostics.fetchHeaders = await axios.head(audioUrl, {
      timeout: 20000,
      validateStatus: (status) => status >= 200 && status < 400,
    }).then((response) => ({
      status: response.status,
      headers: response.headers,
    })).catch((error) => ({
      status: error.response?.status || null,
      error: error.message,
      headers: error.response?.headers || null,
    }));

    diagnostics.fetchSample = await axios.get(audioUrl, {
      timeout: 20000,
      responseType: 'arraybuffer',
      headers: { Range: 'bytes=0-63' },
      validateStatus: (status) => status >= 200 && status < 400,
    }).then((response) => ({
      status: response.status,
      headers: response.headers,
      sampleHeader: Buffer.from(response.data).slice(0, 16).toString('ascii'),
    })).catch((error) => ({
      status: error.response?.status || null,
      error: error.message,
      headers: error.response?.headers || null,
    }));

    diagnostics.success = !!(
      diagnostics.fileValidation.valid &&
      diagnostics.publicUrlValidation.valid &&
      diagnostics.urlValidation.valid &&
      diagnostics.fetchHeaders.status &&
      diagnostics.fetchSample.status
    );

    return res.status(200).json(diagnostics);
  } catch (error) {
    logger.error('Test audio diagnostics failed', { error: error.message, diagnostics });
    diagnostics.error = error.message;
    return res.status(500).json(diagnostics);
  }
});

module.exports = router;
