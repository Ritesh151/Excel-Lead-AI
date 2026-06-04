/**
 * RecordingService.js
 * Recording download, storage, and transcription pipeline
 * Responsibilities:
 *   - Download recordings from Exotel
 *   - Validate and store recordings
 *   - Trigger transcription requests
 *   - Handle webhook responses
 *   - Retry failed downloads
 */

const fs = require('fs').promises;
const path = require('path');
const logger = require('../utils/logger');
const aiPython = require('./AiPythonClient');
const CallLog = require('../mongodb/models/CallLog');
const ExotelService = require('./ExotelService');

class RecordingService {
  constructor() {
    this.recordingsDir = process.env.RECORDINGS_DIR || path.join(__dirname, '../../recordings');
    this.aiEngineUrl = process.env.AI_ENGINE_URL || 'http://localhost:8000';
    this.maxRetries = parseInt(process.env.RECORDING_MAX_RETRIES || '3', 10);
    this.retryDelay = parseInt(process.env.RECORDING_RETRY_DELAY || '2000', 10);

    this.ensureRecordingsDir();
  }

  /**
   * Ensure recordings directory exists
   */
  async ensureRecordingsDir() {
    try {
      await fs.mkdir(this.recordingsDir, { recursive: true });
      logger.info('Recordings directory ready', { path: this.recordingsDir });
    } catch (error) {
      logger.error('Failed to create recordings directory', {
        path: this.recordingsDir,
        error: error.message,
      });
    }
  }

  /**
   * Download recording from Exotel
   * @param {string} callSid - Exotel call SID
   * @param {string} recordingSid - Recording SID
   * @returns {Promise<Object>} Recording file info
   */
  async downloadRecording(callSid, recordingSid) {
    if (!callSid || !recordingSid) {
      throw new Error('Call SID and Recording SID are required');
    }

    try {
      logger.info('Downloading recording', { callSid, recordingSid });

      const buffer = await ExotelService.downloadRecording(recordingSid);
      const filename = `${callSid}_${Date.now()}.wav`;
      const filePath = path.join(this.recordingsDir, filename);

      await fs.writeFile(filePath, buffer);

      const stats = await fs.stat(filePath);
      logger.info('Recording saved', {
        filename,
        size: stats.size,
        path: filePath,
      });

      return {
        filename,
        filePath,
        size: stats.size,
        recordingSid,
      };
    } catch (error) {
      logger.error('Failed to download recording', {
        callSid,
        recordingSid,
        error: error.message,
      });
      throw error;
    }
  }

  /**
   * Download recording with retry logic
   * @param {string} callSid - Call SID
   * @param {string} recordingSid - Recording SID
   * @param {number} attempt - Current attempt
   * @returns {Promise<Object>} Recording info
   */
  async downloadRecordingWithRetry(callSid, recordingSid, attempt = 1) {
    try {
      return await this.downloadRecording(callSid, recordingSid);
    } catch (error) {
      if (attempt < this.maxRetries) {
        logger.warn('Recording download failed, retrying', {
          attempt,
          maxRetries: this.maxRetries,
        });

        await new Promise((resolve) => setTimeout(resolve, this.retryDelay));
        return this.downloadRecordingWithRetry(callSid, recordingSid, attempt + 1);
      }

      throw error;
    }
  }

  /**
   * Validate recording file
   * @param {string} filePath - Path to recording file
   * @returns {Promise<Object>} Validation result
   */
  async validateRecording(filePath) {
    try {
      logger.info('Validating recording', { filePath });

      const stats = await fs.stat(filePath);

      if (stats.size === 0) {
        throw new Error('Recording file is empty');
      }

      // Check for WAV header (RIFF)
      const buffer = await fs.readFile(filePath, { flag: 'r' });
      const header = buffer.toString('ascii', 0, 4);

      if (header !== 'RIFF') {
        throw new Error('Invalid WAV file header');
      }

      logger.info('Recording validation passed', {
        filePath,
        size: stats.size,
      });

      return {
        valid: true,
        size: stats.size,
        mimeType: 'audio/wav',
      };
    } catch (error) {
      logger.error('Recording validation failed', {
        filePath,
        error: error.message,
      });
      throw error;
    }
  }

  /**
   * Send recording to ai-python for transcription
   * @param {Object} options - Transcription options
   * @param {string} options.recordingPath  - Path to recording file
   * @param {string} options.callSid        - Call SID for tracking
   * @param {string} options.phoneNumber    - Customer phone number
   * @param {string} [options.customerName] - Customer name (optional)
   * @returns {Promise<Object>} Transcription response
   */
  async sendForTranscription(options) {
    const { recordingPath, callSid, phoneNumber, customerName } = options;

    if (!recordingPath || !callSid) {
      throw new Error('Recording path and call SID are required');
    }

    try {
      logger.info('Sending recording for transcription', {
        recordingPath,
        callSid,
        phoneNumber,
      });

      // Validate recording exists
      await fs.stat(recordingPath);

      const data = await aiPython.transcribe({
        recording_path: recordingPath,
        call_sid:       callSid,
        phone_number:   phoneNumber,
        customer_name:  customerName || null,
      });

      logger.info('Transcription received', {
        callSid,
        transcription: data.transcription,
        intent: data.intent,
      });

      return {
        success: true,
        transcription: data.transcription,
        intent: data.intent,
        confidence: data.confidence || null,
      };
    } catch (error) {
      logger.error('Transcription request failed', {
        recordingPath,
        callSid,
        error: error.message,
      });
      throw error;
    }
  }

  /**
   * Process recording webhook from Exotel
   * @param {Object} webhookData - Webhook payload
   * @returns {Promise<Object>} Processing result
   */
  async processRecordingWebhook(webhookData) {
    const { CallSid, RecordingUrl, RecordingSid } = webhookData;

    if (!CallSid || !RecordingUrl) {
      logger.warn('Invalid recording webhook payload', { webhookData });
      throw new Error('Invalid recording webhook data');
    }

    try {
      logger.info('Processing recording webhook', { CallSid, RecordingSid });

      // Find call log
      const callLog = await CallLog.findOne({ callSid: CallSid });
      if (!callLog) {
        logger.warn('Call log not found', { CallSid });
        throw new Error('Call log not found');
      }

      // Download recording
      const recording = await this.downloadRecordingWithRetry(CallSid, RecordingSid);

      // Validate recording
      await this.validateRecording(recording.filePath);

      // Update call log with recording info
      await CallLog.findByIdAndUpdate(callLog._id, {
        recordingPath: recording.filePath,
        recordingSid: RecordingSid,
        recordingUrl: RecordingUrl,
        status: 'recording_received',
        recordedAt: new Date(),
      });

      // Send for transcription
      const transcriptionResult = await this.sendForTranscription({
        recordingPath: recording.filePath,
        callSid:       CallSid,
        phoneNumber:   callLog.phoneNumber,
        customerName:  callLog.customerName || null,
      });

      // Update call log with transcription
      await CallLog.findByIdAndUpdate(callLog._id, {
        transcription: transcriptionResult.transcription,
        intent: transcriptionResult.intent,
        confidence: transcriptionResult.confidence,
        status: 'transcribed',
        transcribedAt: new Date(),
      });

      logger.info('Recording processing completed', {
        CallSid,
        intent: transcriptionResult.intent,
      });

      return {
        success: true,
        callSid: CallSid,
        transcription: transcriptionResult.transcription,
        intent: transcriptionResult.intent,
      };
    } catch (error) {
      logger.error('Recording webhook processing failed', {
        CallSid,
        error: error.message,
      });

      // Update call log with error
      try {
        await CallLog.findOneAndUpdate(
          { callSid: CallSid },
          {
            status: 'transcription_failed',
            errorMessage: error.message,
          }
        );
      } catch (updateError) {
        logger.error('Failed to update call log with error', {
          error: updateError.message,
        });
      }

      throw error;
    }
  }

  /**
   * List recent recordings
   * @param {number} limit - Max recordings to list
   * @returns {Promise<Array>} Array of recording filenames
   */
  async listRecordings(limit = 50) {
    try {
      const files = await fs.readdir(this.recordingsDir);
      return files.slice(-limit);
    } catch (error) {
      logger.error('Failed to list recordings', { error: error.message });
      return [];
    }
  }
}

module.exports = new RecordingService();
