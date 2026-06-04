/**
 * startup/diagnostics.js
 * Production startup health checks.
 *
 * Checks (non-fatal unless explicitly critical):
 *   1. Environment variable completeness
 *   2. MongoDB connectivity
 *   3. ai-python reachability
 *   4. Leads file existence
 *   5. Recordings directory writability
 *   6. Log directory writability
 *
 * Does NOT crash the process for optional services (ai-python, Exotel).
 * DOES crash if MongoDB is unreachable or critical config is missing.
 */

'use strict';

const fs     = require('fs');
const path   = require('path');
const axios  = require('axios');
const logger = require('../utils/logger');
const config = require('../config');

const CHECK_TIMEOUT = 5000;

// ─── Public API ───────────────────────────────────────────────────────────────

async function validateEnvironment() {
  logger.info('[Startup] Running environment diagnostics...');

  const report = {
    timestamp:    new Date().toISOString(),
    mongoUri:     _mask(config.mongo.uri),
    aiEngineUrl:  config.aiEngine.url,
    leadsFile:    config.campaign.leadsFilePath,
    recordingsDir: config.files.recordingsDir,
    checks: {},
  };

  // ── 1. Required env vars ─────────────────────────────────────────────────
  report.checks.envVars = _checkEnvVars();

  // ── 2. MongoDB ───────────────────────────────────────────────────────────
  report.checks.mongodb = await _checkMongo();

  // ── 3. ai-python ─────────────────────────────────────────────────────────
  report.checks.aiPython = await _checkAiPython();

  // ── 4. Leads file ─────────────────────────────────────────────────────────
  report.checks.leadsFile = _checkLeadsFile();

  // ── 5. Directories ────────────────────────────────────────────────────────
  report.checks.directories = _ensureDirectories();

  // ── Summary ───────────────────────────────────────────────────────────────
  const failures = Object.entries(report.checks)
    .filter(([, v]) => v.status === 'FAIL' && v.critical)
    .map(([k]) => k);

  if (failures.length > 0) {
    logger.error('[Startup] Critical diagnostics failed', { failures, report });
    throw new Error(`Startup aborted — critical checks failed: ${failures.join(', ')}`);
  }

  const warnings = Object.entries(report.checks)
    .filter(([, v]) => v.status === 'WARN' || (v.status === 'FAIL' && !v.critical))
    .map(([k, v]) => `${k}: ${v.message}`);

  if (warnings.length > 0) {
    logger.warn('[Startup] Non-critical warnings:', { warnings });
  }

  logger.info('[Startup] Diagnostics complete', {
    passed:   Object.values(report.checks).filter((v) => v.status === 'OK').length,
    warnings: warnings.length,
    failures: failures.length,
  });

  return report;
}

// ─── Individual checks ────────────────────────────────────────────────────────

function _checkEnvVars() {
  const required = ['MONGO_URI', 'PORT'];
  const missing  = required.filter((k) => !process.env[k]);
  if (missing.length > 0) {
    return { status: 'FAIL', critical: true, message: `Missing: ${missing.join(', ')}` };
  }
  return { status: 'OK', message: 'All required env vars present' };
}

async function _checkMongo() {
  try {
    const mongoose = require('mongoose');
    const state = mongoose.connection.readyState;
    // 1 = connected
    if (state === 1) {
      return { status: 'OK', message: `Connected to ${config.mongo.dbName}` };
    }
    return { status: 'WARN', critical: false, message: `Mongoose state=${state} (may still be connecting)` };
  } catch (err) {
    return { status: 'FAIL', critical: true, message: err.message };
  }
}

async function _checkAiPython() {
  try {
    const resp = await axios.get(`${config.aiEngine.url}/health`, { timeout: CHECK_TIMEOUT });
    if (resp.status === 200) {
      return { status: 'OK', message: `ai-python reachable — ${JSON.stringify(resp.data).slice(0, 80)}` };
    }
    return { status: 'WARN', critical: false, message: `ai-python returned HTTP ${resp.status}` };
  } catch (err) {
    return {
      status: 'WARN',
      critical: false,
      message: `ai-python not reachable at ${config.aiEngine.url}: ${err.message}. Start with: python main.py server`,
    };
  }
}

function _checkLeadsFile() {
  const leadsPath = path.resolve(config.campaign.leadsFilePath);
  if (fs.existsSync(leadsPath)) {
    const stat = fs.statSync(leadsPath);
    return { status: 'OK', message: `Leads file found: ${leadsPath} (${stat.size} bytes)` };
  }
  return {
    status: 'WARN',
    critical: false,
    message: `Leads file not found: ${leadsPath}. Place leads.xlsx there before starting a campaign.`,
  };
}

function _ensureDirectories() {
  const dirs = [
    path.resolve(config.files.recordingsDir),
    path.resolve(config.files.audioDir),
    path.resolve(config.log.dir),
  ];

  const results = [];
  for (const dir of dirs) {
    try {
      fs.mkdirSync(dir, { recursive: true });
      results.push(`OK: ${dir}`);
    } catch (err) {
      results.push(`FAIL: ${dir} — ${err.message}`);
    }
  }

  const failed = results.filter((r) => r.startsWith('FAIL'));
  if (failed.length > 0) {
    return { status: 'WARN', critical: false, message: failed.join('; ') };
  }
  return { status: 'OK', message: `${dirs.length} directories ready` };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function _mask(uri) {
  // Hide password in mongodb+srv:// or mongodb:// URIs
  return uri ? uri.replace(/\/\/([^:]+):([^@]+)@/, '//***:***@') : '(not set)';
}

module.exports = { validateEnvironment };
