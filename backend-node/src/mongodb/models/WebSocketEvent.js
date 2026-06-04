/**
 * WebSocketEvent.js
 * Persists significant WebSocket events for audit and replay.
 * Auto-expires after 24 hours via TTL index.
 */

'use strict';

const mongoose = require('mongoose');

const wsEventSchema = new mongoose.Schema(
  {
    event: {
      type: String,
      required: true,
      index: true,
    },

    payload: {
      type: mongoose.Schema.Types.Mixed,
      default: {},
    },

    /** Which service emitted this event */
    source: {
      type: String,
      enum: ['backend_node', 'ai_python', 'android', 'system'],
      default: 'backend_node',
    },

    /** Number of WebSocket clients that received this event */
    clientCount: {
      type: Number,
      default: 0,
    },
  },
  {
    timestamps: true,
    collection: 'websocket_events',
  }
);

// TTL: documents expire 24 hours after creation
wsEventSchema.index({ createdAt: 1 }, { expireAfterSeconds: 86400 });
wsEventSchema.index({ event: 1, createdAt: -1 });

module.exports = mongoose.model('WebSocketEvent', wsEventSchema);
