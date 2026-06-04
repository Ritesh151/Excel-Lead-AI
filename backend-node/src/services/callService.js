/**
 * callService.js
 * Orchestrates the sequential calling workflow:
 *   1. Fetch next pending lead
 *   2. Trigger ADB call via Python AI engine
 *   3. Play greeting audio (handled by Python)
 *   4. Record caller response (handled by Python)
 *   5. Transcribe with Whisper (handled by Python)
 *   6. Save result to MongoDB (handled by Python)
 *
 * This service calls the Python FastAPI endpoints.
 */

const axios = require('axios');
const logger = require('../utils/logger');
const Lead = require('../mongodb/models/Lead');

/** Whether a calling session is currently active */
let _isRunning = false;

/** Signal to stop after the current call */
let _stopRequested = false;

/** Python AI engine base URL */
function getEngineUrl() {
  return process.env.AI_ENGINE_URL || 'http://localhost:8000';
}

/**
 * Call the Python AI engine to execute a single call.
 * @param {string} name
 * @param {string} phone
 * @returns {Promise<object>}
 */
async function callPythonEngine(name, phone) {
  const url = `${getEngineUrl()}/api/call/execute`;
  logger.info(`Calling Python engine: POST ${url}`, { name, phone });

  const response = await axios.post(url, { name, phone }, {
    timeout: 300000, // 5-minute timeout for the full call flow
    headers: { 'Content-Type': 'application/json' },
  });

  return response.data;
}

/**
 * Start the sequential calling loop.
 * Processes one lead at a time until none remain or stop() is called.
 */
async function startSequentialCalling() {
  if (_isRunning) {
    logger.warn('Calling session already running');
    return;
  }

  _isRunning = true;
  _stopRequested = false;
  logger.info('Calling session started');

  let processed = 0;
  let yesCount = 0;
  let noCount = 0;
  let failedCount = 0;

  try {
    while (!_stopRequested) {
      const leads = await Lead.find({ status: 'pending' })
        .sort({ createdAt: 1 })
        .limit(1);

      if (leads.length === 0) {
        logger.info('No more pending leads — campaign complete');
        break;
      }

      const lead = leads[0];
      processed++;

      // Mark as calling
      lead.status = 'calling';
      await lead.save();

      logger.info(`Processing lead ${processed}: ${lead.name} (${lead.phone})`);

      try {
        // Call Python engine to execute the call
        const result = await callPythonEngine(lead.name, lead.phone);

        // Update lead with results
        lead.status = result.success ? 'completed' : 'failed';
        lead.transcription = result.transcription || null;
        lead.response = result.intent || 'unknown';
        lead.recordingPath = result.recording_file || null;
        lead.calledAt = new Date();
        lead.errorMessage = result.error || null;
        await lead.save();

        if (result.intent === 'YES') {
          yesCount++;
          logger.info(`YES response from ${lead.name} (${lead.phone})`);
        } else if (result.intent === 'NO') {
          noCount++;
          logger.info(`NO response from ${lead.name} (${lead.phone})`);
        } else {
          logger.info(`Unknown response from ${lead.name} (${lead.phone})`);
        }
      } catch (err) {
        lead.status = 'failed';
        lead.errorMessage = err.message;
        lead.calledAt = new Date();
        await lead.save();
        failedCount++;
        logger.error(`Call failed for ${lead.name} (${lead.phone}): ${err.message}`);
      }

      // Small delay between calls (respectful calling)
      if (!_stopRequested) {
        const delayMs = parseInt(process.env.CALL_FLOW_BETWEEN_CALLS_DELAY || '3000', 10);
        await new Promise(r => setTimeout(r, delayMs));
      }
    }
  } catch (err) {
    logger.error('Calling session error', { error: err.message });
  } finally {
    _isRunning = false;
    _stopRequested = false;
    logger.info('Calling session ended', { processed, yesCount, noCount, failedCount });
  }
}

/**
 * Signal the calling loop to stop after the current call finishes.
 */
function stop() {
  if (!_isRunning) {
    logger.warn('No active calling session to stop');
    return;
  }
  _stopRequested = true;
  logger.info('Stop signal sent — will stop after current call');
}

/**
 * Return the current session state.
 * @returns {{ isRunning: boolean }}
 */
function getStatus() {
  return { isRunning: _isRunning };
}

module.exports = { startSequentialCalling, stop, getStatus };
