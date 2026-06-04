/**
 * CallLog.js
 * MongoDB schema for Exotel-based call logs and tracking
 * Stores complete call lifecycle data including Exotel integration
 */

const mongoose = require('mongoose');

const callLogSchema = new mongoose.Schema(
  {
    // Campaign reference
    campaignId: {
      type: String,
      required: true,
      index: true,
    },

    // Customer information
    phoneNumber: {
      type: String,
      required: true,
      index: true,
    },

    customerName: {
      type: String,
      required: false,
    },

    // Exotel call tracking
    callSid: {
      type: String,
      unique: true,
      sparse: true,
      index: true,
    },

    // Call status lifecycle
    status: {
      type: String,
      enum: [
        'pending',
        'initiated',
        'ringing',
        'answered',
        'completed',
        'failed',
        'recording_received',
        'transcribed',
        'transcription_failed',
      ],
      default: 'pending',
      index: true,
    },

    // Intent detection results
    intent: {
      type: String,
      enum: ['YES', 'NO', 'UNCLEAR', null],
      default: null,
    },

    confidence: {
      type: Number,
      min: 0,
      max: 1,
      default: null,
    },

    // Transcription result
    transcription: {
      type: String,
      default: null,
    },

    // Recording information
    recordingPath: {
      type: String,
      default: null,
    },

    recordingSid: {
      type: String,
      default: null,
    },

    recordingUrl: {
      type: String,
      default: null,
    },

    // Call timing
    duration: {
      type: Number,
      default: 0,
    },

    startTime: {
      type: Date,
      default: null,
    },

    answeredAt: {
      type: Date,
      default: null,
    },

    endTime: {
      type: Date,
      default: null,
    },

    recordedAt: {
      type: Date,
      default: null,
    },

    transcribedAt: {
      type: Date,
      default: null,
    },

    // Error tracking
    errorMessage: {
      type: String,
      default: null,
    },

    // Exotel metadata
    exotelResponse: {
      type: mongoose.Schema.Types.Mixed,
      default: null,
    },

    // Retry tracking
    retryCount: {
      type: Number,
      default: 0,
    },

    // Notes
    notes: {
      type: String,
      default: null,
    },
  },
  {
    timestamps: true,
    collection: 'call_logs',
  }
);

// Indexes for common queries
callLogSchema.index({ campaignId: 1, status: 1 });
callLogSchema.index({ phoneNumber: 1, createdAt: -1 });
callLogSchema.index({ intent: 1 });
callLogSchema.index({ createdAt: -1 });

// Virtual for call duration in human-readable format
callLogSchema.virtual('durationFormatted').get(function () {
  if (!this.startTime || !this.endTime) return null;
  const seconds = Math.round((this.endTime - this.startTime) / 1000);
  return `${seconds}s`;
});

// Pre-save hook for validation
callLogSchema.pre('save', function (next) {
  if (this.status === 'transcribed' && !this.intent) {
    this.status = 'recording_received';
  }
  next();
});

module.exports = mongoose.model('CallLog', callLogSchema);
