/**
 * audioValidator.js
 * WAV metadata and telephony validation utilities.
 */

const fs = require('fs').promises;
const path = require('path');
const logger = require('../utils/logger');

const TELEPHONY_REQUIREMENTS = {
  channels: 1,
  sampleRate: 8000,
  bitsPerSample: 16,
  audioFormat: 1, // PCM
};

function parseWavHeader(buffer) {
  if (buffer.length < 44) {
    throw new Error('WAV header is too small');
  }

  const riff = buffer.toString('ascii', 0, 4);
  const wave = buffer.toString('ascii', 8, 12);

  if (riff !== 'RIFF' || wave !== 'WAVE') {
    throw new Error('Invalid WAV container header');
  }

  let offset = 12;
  let foundFormat = false;
  const metadata = {};

  while (offset + 8 <= buffer.length) {
    const chunkId = buffer.toString('ascii', offset, offset + 4);
    const chunkSize = buffer.readUInt32LE(offset + 4);

    if (chunkId === 'fmt ') {
      metadata.audioFormat = buffer.readUInt16LE(offset + 8);
      metadata.channels = buffer.readUInt16LE(offset + 10);
      metadata.sampleRate = buffer.readUInt32LE(offset + 12);
      metadata.byteRate = buffer.readUInt32LE(offset + 16);
      metadata.blockAlign = buffer.readUInt16LE(offset + 20);
      metadata.bitsPerSample = buffer.readUInt16LE(offset + 22);
      foundFormat = true;
      break;
    }

    offset += 8 + chunkSize;
  }

  if (!foundFormat) {
    throw new Error('WAV fmt chunk not found');
  }

  return metadata;
}

async function readWavMetadata(filePath) {
  const resolvedPath = path.resolve(filePath);
  const fileHandle = await fs.open(resolvedPath, 'r');

  try {
    const buffer = Buffer.alloc(256);
    const { bytesRead } = await fileHandle.read(buffer, 0, buffer.length, 0);
    if (bytesRead < 44) {
      throw new Error('WAV file too small to contain metadata');
    }

    return parseWavHeader(buffer.slice(0, bytesRead));
  } finally {
    await fileHandle.close();
  }
}

function buildValidationReport(metadata) {
  const errors = [];

  if (metadata.audioFormat !== TELEPHONY_REQUIREMENTS.audioFormat) {
    errors.push(`Unsupported audio format ${metadata.audioFormat} (must be PCM 1)`);
  }

  if (metadata.channels !== TELEPHONY_REQUIREMENTS.channels) {
    errors.push(`Invalid channel count ${metadata.channels} (must be mono)`);
  }

  if (metadata.sampleRate !== TELEPHONY_REQUIREMENTS.sampleRate) {
    errors.push(`Invalid sample rate ${metadata.sampleRate}Hz (must be 8000Hz)`);
  }

  if (metadata.bitsPerSample !== TELEPHONY_REQUIREMENTS.bitsPerSample) {
    errors.push(`Invalid bits per sample ${metadata.bitsPerSample} (must be 16)`);
  }

  return {
    valid: errors.length === 0,
    errors,
    metadata,
  };
}

async function validateWavFile(filePath) {
  try {
    const metadata = await readWavMetadata(filePath);
    const report = buildValidationReport(metadata);
    logger.debug('WAV validation result', { filePath, report });
    return report;
  } catch (error) {
    logger.error('WAV validation failed', { filePath, error: error.message });
    return {
      valid: false,
      errors: [error.message],
      metadata: null,
    };
  }
}

async function validateTelephonyWav(filePath) {
  const report = await validateWavFile(filePath);
  report.requirements = TELEPHONY_REQUIREMENTS;
  if (!report.valid) {
    report.message = 'WAV file is not telephony compatible';
  } else {
    report.message = 'WAV file meets telephony requirements';
  }
  return report;
}

module.exports = {
  validateWavFile,
  validateTelephonyWav,
  TELEPHONY_REQUIREMENTS,
};
