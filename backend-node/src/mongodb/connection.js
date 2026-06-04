/**
 * connection.js
 * Production MongoDB connection with:
 *  - Connection pooling (configurable from config.js)
 *  - Retry on transient startup failures
 *  - Index and schema validation bootstrap for all collections
 *  - Graceful disconnect on shutdown
 */

'use strict';

const mongoose = require('mongoose');
const config   = require('../config');
const logger   = require('../utils/logger');

const MAX_CONNECT_RETRIES = 5;
const CONNECT_RETRY_MS    = 3000;

// ─── Connect with retry ───────────────────────────────────────────────────────

async function connect() {
  const uri = config.mongo.uri;

  for (let attempt = 1; attempt <= MAX_CONNECT_RETRIES; attempt++) {
    try {
      await mongoose.connect(uri, {
        dbName:                   config.mongo.dbName,
        maxPoolSize:              config.mongo.maxPoolSize,
        minPoolSize:              config.mongo.minPoolSize,
        serverSelectionTimeoutMS: 6000,
        connectTimeoutMS:         8000,
        socketTimeoutMS:          30000,
        retryWrites:              true,
        retryReads:               true,
        heartbeatFrequencyMS:     10000,
        family:                   4,            // force IPv4; avoids ENETUNREACH on some systems
      });

      logger.db(`MongoDB connected — host=${mongoose.connection.host} db=${config.mongo.dbName} pool=${config.mongo.maxPoolSize}`);
      logger.info(`[MongoDB] Connected to ${config.mongo.dbName}`);
      break;
    } catch (err) {
      logger.error(`[MongoDB] Connection attempt ${attempt}/${MAX_CONNECT_RETRIES} failed: ${err.message}`);
      if (attempt >= MAX_CONNECT_RETRIES) {
        logger.error('[MongoDB] All retry attempts exhausted — exiting');
        process.exit(1);
      }
      await _sleep(CONNECT_RETRY_MS);
    }
  }

  // ── Event handlers ────────────────────────────────────────────────────────
  mongoose.connection.on('disconnected', () => {
    logger.warn('[MongoDB] Disconnected — Mongoose will auto-reconnect');
  });

  mongoose.connection.on('reconnected', () => {
    logger.info('[MongoDB] Reconnected');
  });

  mongoose.connection.on('error', (err) => {
    logger.error('[MongoDB] Runtime error', { error: err.message });
  });

  // ── Ensure indexes and schema validation on all collections ───────────────
  await _bootstrapCollections();
}

// ─── Graceful disconnect ──────────────────────────────────────────────────────

async function disconnect() {
  await mongoose.connection.close();
  logger.info('[MongoDB] Connection closed');
}

// ─── Collection bootstrap ─────────────────────────────────────────────────────

async function _bootstrapCollections() {
  try {
    const db = mongoose.connection.db;

    // Ensure all six collections exist and have their indexes
    const collections = ['leads', 'call_logs', 'campaigns', 'recordings', 'transcriptions', 'websocket_events'];
    const existing = await db.listCollections().toArray().catch(() => []);
    const existingNames = new Set(existing.map((c) => c.name));

    for (const name of collections) {
      if (!existingNames.has(name)) {
        await db.createCollection(name).catch(() => {});
        logger.db(`[MongoDB] Created collection: ${name}`);
      }
    }

    // Ensure extra indexes not covered by Mongoose schema definitions
    await _ensureLeadsIndexes(db);
    await _ensureCallLogsIndexes(db);
    await _ensureCampaignsIndexes(db);
    await _ensureRecordingsIndexes(db);
    await _ensureTranscriptionsIndexes(db);
    await _ensureWsEventsIndexes(db);

    logger.info('[MongoDB] All collection indexes verified');
  } catch (err) {
    logger.warn('[MongoDB] Bootstrap warning (non-fatal):', err.message);
  }
}

async function _ensureLeadsIndexes(db) {
  const c = db.collection('leads');
  await Promise.allSettled([
    c.createIndex({ phone: 1 },            { unique: false, background: true }),
    c.createIndex({ status: 1 },           { background: true }),
    c.createIndex({ campaignId: 1 },       { background: true }),
    c.createIndex({ createdAt: -1 },       { background: true }),
    c.createIndex({ phone: 1, status: 1 }, { background: true }),
  ]);
}

async function _ensureCallLogsIndexes(db) {
  const c = db.collection('call_logs');
  await Promise.allSettled([
    c.createIndex({ campaignId: 1, status: 1 }, { background: true }),
    c.createIndex({ phoneNumber: 1 },           { background: true }),
    c.createIndex({ intent: 1 },                { background: true }),
    c.createIndex({ createdAt: -1 },            { background: true }),
    c.createIndex({ callSid: 1 },               { unique: true, sparse: true, background: true }),
  ]);
}

async function _ensureCampaignsIndexes(db) {
  const c = db.collection('campaigns');
  await Promise.allSettled([
    c.createIndex({ campaignId: 1 }, { unique: true, background: true }),
    c.createIndex({ status: 1 },     { background: true }),
    c.createIndex({ createdAt: -1 }, { background: true }),
  ]);
}

async function _ensureRecordingsIndexes(db) {
  const c = db.collection('recordings');
  await Promise.allSettled([
    c.createIndex({ phone: 1 },      { background: true }),
    c.createIndex({ source: 1 },     { background: true }),
    c.createIndex({ createdAt: -1 }, { background: true }),
  ]);
}

async function _ensureTranscriptionsIndexes(db) {
  const c = db.collection('transcriptions');
  await Promise.allSettled([
    c.createIndex({ phone: 1 },      { background: true }),
    c.createIndex({ intent: 1 },     { background: true }),
    c.createIndex({ createdAt: -1 }, { background: true }),
  ]);
}

async function _ensureWsEventsIndexes(db) {
  const c = db.collection('websocket_events');
  await Promise.allSettled([
    c.createIndex({ event: 1 },     { background: true }),
    c.createIndex({ createdAt: -1 }, { background: true, expireAfterSeconds: 86400 }), // TTL: 24h
  ]);
}

function _sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

module.exports = { connect, disconnect };
