/**
 * config.js
 * Centralized, validated configuration for backend-node.
 *
 * All environment variables are read, validated, and exported from here.
 * Import this instead of reading process.env directly throughout the app.
 *
 * Fails fast at startup if required variables are missing or malformed.
 */

'use strict';

require('dotenv').config();

// ─── Validators ───────────────────────────────────────────────────────────────

function requireString(name, fallback) {
  const val = process.env[name];
  if (val !== undefined && val.trim() !== '') return val.trim();
  if (fallback !== undefined) return fallback;
  throw new Error(`[Config] Required environment variable "${name}" is not set`);
}

function requireInt(name, fallback) {
  const val = process.env[name];
  if (val !== undefined && val.trim() !== '') {
    const n = parseInt(val, 10);
    if (isNaN(n)) throw new Error(`[Config] "${name}" must be an integer, got: "${val}"`);
    return n;
  }
  if (fallback !== undefined) return fallback;
  throw new Error(`[Config] Required environment variable "${name}" is not set`);
}

function requireBool(name, fallback) {
  const val = process.env[name];
  if (val !== undefined && val.trim() !== '') {
    const lower = val.trim().toLowerCase();
    if (lower === 'true' || lower === '1') return true;
    if (lower === 'false' || lower === '0') return false;
    throw new Error(`[Config] "${name}" must be true/false, got: "${val}"`);
  }
  if (fallback !== undefined) return fallback;
  throw new Error(`[Config] Required environment variable "${name}" is not set`);
}

// ─── Build config object ──────────────────────────────────────────────────────

const config = {
  // App
  nodeEnv:  requireString('NODE_ENV', 'development'),
  port:     requireInt('PORT', 3000),
  isDev:    (process.env.NODE_ENV || 'development') !== 'production',

  // MongoDB
  mongo: {
    uri:         requireString('MONGO_URI', 'mongodb://localhost:27017/ai_calling'),
    dbName:      requireString('MONGO_DB_NAME', 'ai_calling'),
    maxPoolSize: requireInt('MONGO_MAX_POOL_SIZE', 10),
    minPoolSize: requireInt('MONGO_MIN_POOL_SIZE', 2),
  },

  // ai-python
  aiEngine: {
    url:          requireString('AI_ENGINE_URL', 'http://localhost:8000'),
    maxRetries:   requireInt('AI_ENGINE_MAX_RETRIES', 2),
    retryDelayMs: requireInt('AI_ENGINE_RETRY_DELAY_MS', 3000),
    timeout:      requireInt('CALL_TIMEOUT_MS', 180000),
  },

  // Campaign
  campaign: {
    callDelayMs:          requireInt('CALL_DELAY_MS', 5000),
    callTimeoutMs:        requireInt('CALL_TIMEOUT_MS', 180000),
    transcriptionTimeout: requireInt('TRANSCRIPTION_TIMEOUT_MS', 120000),
    leadsFilePath:        requireString('LEADS_FILE_PATH', '../leads/leads.xlsx'),
  },

  // Files
  files: {
    audioDir:      requireString('AUDIO_DIR', '../audio'),
    recordingsDir: requireString('RECORDINGS_DIR', '../recordings'),
  },

  // Auth
  internalApiToken: requireString('INTERNAL_API_TOKEN', ''),

  // Logging
  log: {
    level: requireString('LOG_LEVEL', 'debug'),
    dir:   requireString('LOG_DIR', './logs'),
  },
};

// ─── Startup validation ───────────────────────────────────────────────────────

function validate() {
  const errors = [];

  if (!config.mongo.uri.startsWith('mongodb')) {
    errors.push('MONGO_URI must start with "mongodb"');
  }
  if (!config.aiEngine.url.startsWith('http')) {
    errors.push('AI_ENGINE_URL must be a valid http/https URL');
  }
  if (config.campaign.callDelayMs < 0) {
    errors.push('CALL_DELAY_MS must be >= 0');
  }

  if (errors.length > 0) {
    throw new Error(`[Config] Validation failed:\n  - ${errors.join('\n  - ')}`);
  }
}

validate();

module.exports = config;
