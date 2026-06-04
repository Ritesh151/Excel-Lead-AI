/**
 * AiPythonClient.js
 * HTTP client for ai-python FastAPI with retries and timeouts.
 */

'use strict';

const axios = require('axios');
const logger = require('../utils/logger');

class AiPythonClient {
  constructor() {
    this.baseUrl = (process.env.AI_ENGINE_URL || 'http://localhost:8000').replace(/\/$/, '');
    this.defaultTimeout = parseInt(process.env.CALL_TIMEOUT_MS || '180000', 10);
    this.transcribeTimeout = parseInt(process.env.TRANSCRIPTION_TIMEOUT_MS || '120000', 10);
    this.maxRetries = parseInt(process.env.AI_ENGINE_MAX_RETRIES || '2', 10);
    this.retryDelayMs = parseInt(process.env.AI_ENGINE_RETRY_DELAY_MS || '2000', 10);
  }

  async _withRetry(fn, label) {
    let lastErr;
    for (let attempt = 1; attempt <= this.maxRetries; attempt++) {
      try {
        return await fn();
      } catch (err) {
        lastErr = err;
        const retryable =
          !err.response ||
          err.response.status >= 500 ||
          err.code === 'ECONNABORTED' ||
          err.code === 'ECONNREFUSED';
        if (!retryable || attempt >= this.maxRetries) break;
        logger.warn(`[AiPythonClient] ${label} retry ${attempt}/${this.maxRetries}`, {
          error: err.message,
        });
        await new Promise((r) => setTimeout(r, this.retryDelayMs * attempt));
      }
    }
    throw lastErr;
  }

  /**
   * POST /api/call/execute — full ADB + Android call flow
   */
  async executeCall(name, phone) {
    return this._withRetry(
      () =>
        axios.post(
          `${this.baseUrl}/api/call/execute`,
          { name, phone },
          { timeout: this.defaultTimeout }
        ),
      'executeCall'
    ).then((r) => r.data);
  }

  /**
   * POST /api/call/transcribe — transcribe file on disk (Exotel path)
   */
  async transcribe(payload) {
    return this._withRetry(
      () =>
        axios.post(`${this.baseUrl}/api/call/transcribe`, payload, {
          timeout: this.transcribeTimeout,
        }),
      'transcribe'
    ).then((r) => r.data);
  }

  /**
   * GET /api/call/status — engine health
   */
  async getEngineStatus() {
    const res = await axios.get(`${this.baseUrl}/api/call/status`, { timeout: 15_000 });
    return res.data;
  }

  /**
   * GET /health
   */
  async health() {
    const res = await axios.get(`${this.baseUrl}/health`, { timeout: 10_000 });
    return res.data;
  }
}

module.exports = new AiPythonClient();
