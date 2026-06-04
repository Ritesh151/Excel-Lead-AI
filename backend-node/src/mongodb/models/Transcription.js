/**
 * Transcription.js
 * MongoDB model for Whisper transcription results.
 * Persisted after every /api/calls/recording or /api/call/transcribe call.
 */

'use strict';

const mongoose = require('mongoose');

const transcriptionSchema = new mongoose.Schema(
  {
    phone: {
      type: String,
      required: true,
      index: true,
    },

    text: {
      type: String,
      default: '',
    },

    intent: {
      type: String,
      enum: ['YES', 'NO', 'UNKNOWN', null],
      default: 'UNKNOWN',
      index: true,
    },

    confidence: {
      type: Number,
      min: 0,
      max: 1,
      default: 0,
    },

    recordingPath: {
      type: String,
      default: null,
    },

    /** Duration of the Whisper inference in milliseconds */
    durationMs: {
      type: Number,
      default: 0,
    },

    /** Which service produced this transcription */
    source: {
      type: String,
      enum: ['android_upload', 'exotel_webhook', 'manual', 'ai_python'],
      default: 'android_upload',
    },

    /** Reference to call_records._id in ai-python MongoDB (if available) */
    aiPythonDbId: {
      type: String,
      default: null,
    },

    campaignId: {
      type: String,
      default: null,
      index: true,
    },
  },
  {
    timestamps: true,
    collection: 'transcriptions',
  }
);

transcriptionSchema.index({ phone: 1, createdAt: -1 });
transcriptionSchema.index({ intent: 1, createdAt: -1 });
transcriptionSchema.index({ createdAt: -1 });

module.exports = mongoose.model('Transcription', transcriptionSchema);
