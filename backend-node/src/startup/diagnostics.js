/**
 * diagnostics.js
 * Startup validation for Exotel audio, public URLs, and ngrok tunnel support.
 */

const path = require('path');
const fs = require('fs').promises;
const logger = require('../utils/logger');
const { validatePublicUrl } = require('../utils/urlValidator');
const { validateTelephonyWav } = require('../audio/audioValidator');
const ExotelDebugger = require('../services/ExotelDebugger');
const ngrokService = require('../services/NgrokService');
const audioProcessor = require('../audio/audioProcessor');

async function validateEnvironment() {
  const configuredBaseUrl = process.env.BASE_URL || '';
  const audioDir = path.resolve(process.env.AUDIO_DIR || path.join(__dirname, '../../audio'));
  const audioFileName = process.env.AUDIO_FILE_NAME || 'greeting_telephony.wav';
  const sourceAudioFileName = process.env.AUDIO_SOURCE_FILE_NAME || audioFileName.replace(/_telephony(?=\.[^.]+$)/, '');
  const audioFilePath = path.join(audioDir, audioFileName);

  logger.info('Running startup diagnostics', {
    configuredBaseUrl,
    audioDir,
    audioFileName,
    sourceAudioFileName,
  });

  const diagnostics = {
    configuredBaseUrl,
    resolvedBaseUrl: configuredBaseUrl,
    audioDir,
    audioFileName,
    baseUrlValidation: null,
    fileValidation: null,
    audioFetchValidation: null,
    ngrok: {
      active: false,
      publicUrl: null,
    },
    warnings: [],
    errors: [],
  };

  const allowLocalBaseUrl = process.env.ALLOW_LOCAL_BASE_URL === 'true' || process.env.NODE_ENV !== 'production';

  const ngrokResult = await ngrokService.ensureActiveTunnel();
  diagnostics.ngrok = ngrokResult;

  if (ngrokResult.active) {
    diagnostics.resolvedBaseUrl = ngrokResult.publicUrl;
    process.env.BASE_URL = ngrokResult.publicUrl;
  }

  if (!diagnostics.resolvedBaseUrl) {
    diagnostics.errors.push('BASE_URL is not configured. Set BASE_URL to your ngrok HTTPS URL or start a local ngrok tunnel.');
  }

  diagnostics.baseUrlValidation = await validatePublicUrl(diagnostics.resolvedBaseUrl).catch((error) => ({ valid: false, errors: [error.message] }));
  const baseUrlAllowed = diagnostics.baseUrlValidation.valid || allowLocalBaseUrl;

  if (!diagnostics.baseUrlValidation.valid) {
    const message = allowLocalBaseUrl
      ? 'BASE_URL is not public HTTPS, but local dev mode allows startup. Exotel cannot fetch localhost directly.'
      : 'BASE_URL is invalid or not publicly resolvable.';

    if (allowLocalBaseUrl) {
      diagnostics.warnings.push(message);
    } else {
      diagnostics.errors.push(message);
    }
  }

  try {
    await fs.access(audioDir);
  } catch (error) {
    diagnostics.errors.push(`AUDIO_DIR does not exist or is not accessible: ${audioDir}`);
  }

  try {
    const resolvedTelephonyPath = await audioProcessor.validateAndConvert(sourceAudioFileName, audioFileName);
    diagnostics.fileValidation = await validateTelephonyWav(resolvedTelephonyPath);
    if (!diagnostics.fileValidation.valid) {
      diagnostics.errors.push(`Audio file validation failed: ${diagnostics.fileValidation.errors.join('; ')}`);
    }
  } catch (error) {
    diagnostics.errors.push(`Unable to validate or convert telephony audio file: ${error.message}`);
  }

  const audioUrl = `${diagnostics.resolvedBaseUrl.replace(/\/$/, '')}/audio/${encodeURIComponent(audioFileName)}`;
  diagnostics.audioFetchValidation = await ExotelDebugger.validateAudioUrl(audioUrl, audioFilePath).catch((error) => ({ error: error.message }));

  if (!baseUrlAllowed || !diagnostics.fileValidation?.valid || diagnostics.audioFetchValidation?.sampleFetch?.error || diagnostics.audioFetchValidation?.headerFetch?.error) {
    if (!baseUrlAllowed) {
      diagnostics.errors.push('BASE_URL is invalid or not publicly resolvable.');
    }

    if (!diagnostics.fileValidation?.valid) {
      diagnostics.errors.push('Audio file validation failed.');
    }

    if (diagnostics.audioFetchValidation?.sampleFetch?.error || diagnostics.audioFetchValidation?.headerFetch?.error) {
      diagnostics.warnings.push('Audio URL fetch validation failed. This may be expected for local-only URLs or before ngrok is configured.');
    }
  }

  if (diagnostics.errors.length > 0) {
    logger.error('Startup diagnostics failed', diagnostics);
    throw new Error('Startup diagnostics detected critical issues. Fix BASE_URL and telephony audio file configuration before running the backend.');
  }

  if (diagnostics.baseUrlValidation.warnings?.length) {
    diagnostics.warnings.push(...diagnostics.baseUrlValidation.warnings);
  }

  if (diagnostics.ngrok.active) {
    logger.info('Ngrok public URL detected and applied to BASE_URL', diagnostics.ngrok);
  }

  logger.info('Startup diagnostics passed', diagnostics);
  return diagnostics;
}

module.exports = {
  validateEnvironment,
};
