/**
 * WebSocketServer.js
 * Real-time WebSocket server for frontend-kotlin and dashboard clients.
 *
 * Uses the built-in Node.js 'ws' library (no Socket.IO dependency).
 * Runs on the same HTTP server as Express so only one port is needed.
 *
 * Events emitted TO clients:
 *   call_started         { phone, name, campaignId, timestamp }
 *   call_connected       { phone, timestamp }
 *   greeting_playing     { phone, fileName, strategy, timestamp }
 *   greeting_played      { phone, success, strategies, timestamp }
 *   recording_started    { phone, timestamp }
 *   recording_saved      { phone, filePath, durationSec, timestamp }
 *   transcription_done   { phone, transcription, intent, confidence, timestamp }
 *   intent_detected      { phone, intent, confidence, keyword, timestamp }
 *   call_completed       { phone, intent, transcription, dbId, timestamp }
 *   call_failed          { phone, error, timestamp }
 *   campaign_progress    { campaignId, processed, total, yesCount, noCount, timestamp }
 *   campaign_done        { campaignId, summary, timestamp }
 *   device_status        { serial, model, status, timestamp }
 *   android_connected    { timestamp }
 *   log                  { level, message, timestamp }
 *
 * Events received FROM clients (Android app):
 *   android_ready        — app connected, ready for commands
 *   call_state           { state, phone, flowId }
 *   playback_result      { file, strategy, success }
 *   recording_saved      { path, duration }
 *   watchdog             { ts }
 */

'use strict';

const { WebSocketServer: WSServer } = require('ws');
const logger = require('../utils/logger');

class WebSocketServer {
  constructor() {
    /** @type {WSServer|null} */
    this._wss = null;
    /** @type {Set<import('ws').WebSocket>} */
    this._clients = new Set();
    this._androidClient = null;
  }

  /**
   * Attach the WebSocket server to an existing HTTP server.
   * @param {import('http').Server} httpServer
   */
  attach(httpServer) {
    this._wss = new WSServer({ server: httpServer, path: '/' });

    this._wss.on('connection', (ws, req) => {
      const ip = req.socket.remoteAddress || 'unknown';
      const ua = req.headers['user-agent'] || '';
      logger.info(`[WS] Client connected ip=${ip} ua="${ua}"`);

      this._clients.add(ws);

      // Identify Android client
      if (ua.includes('okhttp') || ua.includes('Android') || ua.toLowerCase().includes('android')) {
        this._androidClient = ws;
        logger.info('[WS] Android client registered');
      }

      ws.on('message', (data) => this._handleMessage(ws, data));

      ws.on('close', (code, reason) => {
        this._clients.delete(ws);
        if (this._androidClient === ws) {
          this._androidClient = null;
          logger.info('[WS] Android client disconnected');
        }
        logger.info(`[WS] Client disconnected code=${code} reason=${reason?.toString() || ''}`);
      });

      ws.on('error', (err) => {
        logger.error(`[WS] Client error: ${err.message}`);
        this._clients.delete(ws);
      });

      ws.on('pong', () => {
        // Keep-alive confirmed
      });

      // Send welcome + current state
      this._sendToOne(ws, { event: 'connected', message: 'AI Calling backend ready', timestamp: Date.now() });
    });

    // Ping all clients every 30s to keep connections alive
    this._pingInterval = setInterval(() => {
      this._clients.forEach((ws) => {
        if (ws.readyState === ws.OPEN) {
          ws.ping();
        } else {
          this._clients.delete(ws);
        }
      });
    }, 30_000);

    logger.info('[WS] WebSocket server attached to HTTP server on path /');
    return this;
  }

  // ─── Message handler (from clients) ───────────────────────────────────────

  _handleMessage(ws, rawData) {
    let msg;
    try {
      msg = JSON.parse(rawData.toString());
    } catch {
      logger.warn(`[WS] Received non-JSON message: ${rawData.toString().slice(0, 100)}`);
      return;
    }

    const { event } = msg;
    logger.info(`[WS] ← ${event}`, msg);

    switch (event) {
      case 'android_ready':
        this._androidClient = ws;
        logger.info('[WS] Android app ready');
        this.emit('android_connected', { timestamp: Date.now() });
        break;

      case 'call_state':
        // Android reporting call state change
        this.emit('call_state_update', {
          state: msg.state,
          phone: msg.phone,
          flowId: msg.flowId,
          timestamp: Date.now(),
        });
        break;

      case 'playback_result':
        this.emit('greeting_played', {
          phone: msg.phone,
          file: msg.file,
          strategy: msg.strategy,
          success: msg.success,
          timestamp: Date.now(),
        });
        break;

      case 'recording_saved':
        this.emit('recording_saved', {
          phone: msg.phone,
          path: msg.path,
          duration: msg.duration,
          timestamp: Date.now(),
        });
        break;

      case 'watchdog':
        // Heartbeat from Android — no action needed
        break;

      case 'call_result':
        this.emit('call_completed', {
          phone: msg.phone,
          intent: msg.intent,
          transcription: msg.transcription,
          flowId: msg.flowId,
          success: msg.success,
          timestamp: Date.now(),
        });
        break;

      default:
        logger.debug(`[WS] Unhandled event: ${event}`);
    }
  }

  // ─── Emit to all clients ───────────────────────────────────────────────────

  /**
   * Broadcast a typed event to all connected WebSocket clients.
   * @param {string} event
   * @param {object} payload
   */
  emit(event, payload = {}) {
    const message = JSON.stringify({ event, ...payload, timestamp: payload.timestamp || Date.now() });
    let sent = 0;
    this._clients.forEach((ws) => {
      if (ws.readyState === ws.OPEN) {
        ws.send(message, (err) => {
          if (err) logger.debug(`[WS] Send error: ${err.message}`);
        });
        sent++;
      }
    });
    if (this._clients.size > 0) {
      logger.debug(`[WS] → ${event} (${sent}/${this._clients.size} clients)`);
    }
  }

  /**
   * Send a message to a single client.
   * @private
   */
  _sendToOne(ws, payload) {
    if (ws.readyState === ws.OPEN) {
      ws.send(JSON.stringify(payload), (err) => {
        if (err) logger.debug(`[WS] sendToOne error: ${err.message}`);
      });
    }
  }

  // ─── Typed event helpers (called by services) ─────────────────────────────

  emitCallStarted(phone, name, campaignId) {
    this.emit('call_started', { phone, name, campaignId });
  }

  emitCallConnected(phone) {
    this.emit('call_connected', { phone });
  }

  emitGreetingPlaying(phone, fileName, strategy) {
    this.emit('greeting_playing', { phone, fileName, strategy });
  }

  emitGreetingPlayed(phone, success, strategies = []) {
    this.emit('greeting_played', { phone, success, strategies });
  }

  emitRecordingStarted(phone) {
    this.emit('recording_started', { phone });
  }

  emitRecordingSaved(phone, filePath, durationSec) {
    this.emit('recording_saved', { phone, filePath, durationSec });
  }

  emitTranscriptionDone(phone, transcription, intent, confidence) {
    this.emit('transcription_done', { phone, transcription, intent, confidence });
  }

  emitIntentDetected(phone, intent, confidence, keyword) {
    this.emit('intent_detected', { phone, intent, confidence, keyword });
  }

  emitCallCompleted(phone, intent, transcription, dbId) {
    this.emit('call_completed', { phone, intent, transcription, dbId });
  }

  emitCallFailed(phone, error) {
    this.emit('call_failed', { phone, error });
  }

  emitCampaignProgress(campaignId, processed, total, yesCount, noCount) {
    this.emit('campaign_progress', { campaignId, processed, total, yesCount, noCount });
  }

  emitCampaignDone(campaignId, summary) {
    this.emit('campaign_done', { campaignId, summary });
  }

  emitDeviceStatus(serial, model, status) {
    this.emit('device_status', { serial, model, status });
  }

  emitLog(level, message) {
    this.emit('log', { level, message });
  }

  // ─── Stats ─────────────────────────────────────────────────────────────────

  get clientCount() {
    return this._clients.size;
  }

  get androidConnected() {
    return this._androidClient !== null && this._androidClient.readyState === 1; // OPEN
  }

  // ─── Cleanup ───────────────────────────────────────────────────────────────

  close() {
    if (this._pingInterval) clearInterval(this._pingInterval);
    this._clients.forEach((ws) => ws.terminate());
    this._clients.clear();
    this._wss?.close();
    logger.info('[WS] WebSocket server closed');
  }
}

// Singleton — import this everywhere
const wsServer = new WebSocketServer();
module.exports = wsServer;
