/**
 * audioRoutes.js
 * Static audio middleware and safe WAV serving for Exotel.
 */

const express = require('express');
const path = require('path');
const fs = require('fs').promises;
const logger = require('../utils/logger');
const { validateTelephonyWav } = require('../audio/audioValidator');

const router = express.Router();
const audioDir = path.resolve(process.env.AUDIO_DIR || path.join(__dirname, '../../audio'));
const allowedExtensions = new Set(['.wav']);

router.get('/:fileName', async (req, res) => {
  try {
    const { fileName } = req.params;
    if (!fileName || fileName.includes('..')) {
      return res.status(400).json({ success: false, message: 'Invalid audio file path' });
    }

    const extension = path.extname(fileName).toLowerCase();
    if (!allowedExtensions.has(extension)) {
      return res.status(400).json({ success: false, message: 'Only WAV audio files are supported' });
    }

    const filePath = path.join(audioDir, fileName);
    await fs.access(filePath);

    if (fileName.endsWith('_telephony.wav')) {
      const report = await validateTelephonyWav(filePath);
      if (!report.valid) {
        logger.error('Telephony audio served with invalid WAV format', { fileName, report });
        return res.status(500).json({ success: false, message: 'Telephony audio file is invalid', report });
      }
    }

    res.set({
      'Content-Type': 'audio/wav',
      'Cache-Control': 'no-cache, no-store, must-revalidate',
      Pragma: 'no-cache',
      Expires: '0',
      'Accept-Ranges': 'bytes',
    });

    return res.sendFile(filePath, (error) => {
      if (error) {
        logger.error('Failed to send audio file', { fileName, error: error.message });
        if (!res.headersSent) {
          res.status(500).json({ success: false, message: 'Unable to serve audio file' });
        }
      }
    });
  } catch (error) {
    logger.error('Audio route error', { error: error.message, params: req.params });
    return res.status(404).json({ success: false, message: 'Audio file not found or inaccessible' });
  }
});

module.exports = router;
