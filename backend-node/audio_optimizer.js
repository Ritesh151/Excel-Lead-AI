/**
 * audio_optimizer.js
 * Convert all WAV assets to telephony-compatible WAV files.
 */

require('dotenv').config();
const fs = require('fs').promises;
const path = require('path');
const { execFileSync } = require('child_process');
const { validateTelephonyWav } = require('./src/audio/audioValidator');
const logger = require('./src/utils/logger');

const audioDir = path.resolve(process.env.AUDIO_DIR || path.join(__dirname, '../audio'));

function getOutputFilePath(inputFilePath) {
  const { dir, name } = path.parse(inputFilePath);
  return path.join(dir, `${name}_telephony.wav`);
}

function ensureFfmpegAvailable() {
  try {
    execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' });
    return true;
  } catch (error) {
    throw new Error('ffmpeg is not installed or not available in PATH');
  }
}

async function optimizeFile(inputFilePath, outputFilePath) {
  logger.info('Optimizing audio file for telephony', { inputFilePath, outputFilePath });

  try {
    execFileSync(
      'ffmpeg',
      [
        '-y',
        '-i', inputFilePath,
        '-ac', '1',
        '-ar', '8000',
        '-sample_fmt', 's16',
        '-c:a', 'pcm_s16le',
        outputFilePath,
      ],
      { stdio: 'inherit' }
    );
  } catch (error) {
    throw new Error(`ffmpeg conversion failed for ${inputFilePath}: ${error.message}`);
  }

  const validation = await validateTelephonyWav(outputFilePath);
  if (!validation.valid) {
    throw new Error(`Optimized file ${outputFilePath} failed validation: ${validation.errors.join('; ')}`);
  }

  logger.info('Telephony audio optimized successfully', { outputFilePath });
}

async function scanAndOptimize() {
  ensureFfmpegAvailable();

  const files = await fs.readdir(audioDir);
  const inputFiles = files.filter((file) => file.toLowerCase().endsWith('.wav') && !file.toLowerCase().includes('_telephony.wav'));

  if (inputFiles.length === 0) {
    throw new Error(`No WAV files found in audio directory: ${audioDir}`);
  }

  for (const fileName of inputFiles) {
    const inputFilePath = path.join(audioDir, fileName);
    const outputFilePath = getOutputFilePath(inputFilePath);
    await optimizeFile(inputFilePath, outputFilePath);
  }

  logger.info('Audio optimization completed for all WAV files');
}

async function run() {
  try {
    await scanAndOptimize();
    console.log('Audio optimization completed successfully.');
  } catch (error) {
    console.error('Audio optimization failed:', error.message);
    process.exit(1);
  }
}

if (require.main === module) {
  run();
}
