/**
 * audioProcessor.js
 * Telephony WAV conversion and validation utilities.
 */

const { execFileSync } = require('child_process');
const path = require('path');
const fs = require('fs');
const logger = require('../utils/logger');
const { validateTelephonyWav } = require('./audioValidator');

class AudioProcessor {
  constructor() {
    this.audioDir = path.resolve(process.env.AUDIO_DIR || path.join(__dirname, '../../audio'));
  }

  getSourcePath(sourceName) {
    return path.resolve(this.audioDir, sourceName);
  }

  getTelephonyPath(sourceName, targetName = null) {
    if (targetName) {
      return path.resolve(this.audioDir, targetName);
    }

    const parsed = path.parse(sourceName);
    if (parsed.name.endsWith('_telephony')) {
      return path.resolve(this.audioDir, sourceName);
    }

    return path.resolve(this.audioDir, `${parsed.name}_telephony${parsed.ext}`);
  }

  ensureFfmpegAvailable() {
    try {
      execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' });
      return true;
    } catch (error) {
      logger.error('ffmpeg is not available', { error: error.message });
      throw new Error('ffmpeg is not installed or not available in PATH');
    }
  }

  convertToTelephony(sourceName, targetName = null) {
    const sourcePath = this.getSourcePath(sourceName);
    const targetPath = this.getTelephonyPath(sourceName, targetName);
    const useTempPath = sourcePath === targetPath;
    const finalTargetPath = useTempPath ? `${targetPath}.tmp.wav` : targetPath;

    if (!fs.existsSync(sourcePath)) {
      throw new Error(`Source audio file not found: ${sourcePath}`);
    }

    this.ensureFfmpegAvailable();

    logger.info('Converting audio to telephony WAV', { sourcePath, targetPath: finalTargetPath });

    try {
      execFileSync('ffmpeg', [
        '-y',
        '-i', sourcePath,
        '-ac', '1',
        '-ar', '8000',
        '-sample_fmt', 's16',
        '-c:a', 'pcm_s16le',
        finalTargetPath,
      ], { stdio: 'inherit' });
    } catch (error) {
      logger.error('ffmpeg conversion failed', { sourcePath, finalTargetPath, error: error.message });
      throw new Error(`ffmpeg conversion failed for ${sourceName}: ${error.message}`);
    }

    if (useTempPath) {
      fs.renameSync(finalTargetPath, targetPath);
    }

    const report = validateTelephonyWav(targetPath);
    if (!report.valid) {
      throw new Error(`Telephony output invalid: ${report.errors.join('; ')}`);
    }

    logger.info('Audio conversion completed', { targetPath });
    return targetPath;
  }

  async validateAndConvert(sourceName, targetName = null) {
    const sourcePath = this.getSourcePath(sourceName);
    const telephonyPath = this.getTelephonyPath(sourceName, targetName);

    if (!fs.existsSync(sourcePath)) {
      throw new Error(`Missing source audio file: ${sourcePath}`);
    }

    if (fs.existsSync(telephonyPath)) {
      const report = await validateTelephonyWav(telephonyPath);
      if (report.valid) {
        logger.info('Telephony WAV already valid', { telephonyPath });
        return telephonyPath;
      }
      logger.warn('Existing telephony WAV is invalid and will be re-generated', { telephonyPath, report });
    }

    return this.convertToTelephony(sourceName, targetName);
  }
}

module.exports = new AudioProcessor();
