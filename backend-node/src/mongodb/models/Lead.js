/**
 * Lead.js
 * Mongoose model for a single calling lead imported from Excel.
 */

const mongoose = require('mongoose');

const leadSchema = new mongoose.Schema(
  {
    name: {
      type: String,
      trim: true,
      default: '',
    },

    phone: {
      type: String,
      required: [true, 'Phone number is required'],
      trim: true,
    },

    /** Tracks where this lead is in the calling workflow */
    status: {
      type: String,
      enum: ['pending', 'calling', 'completed', 'failed', 'skipped'],
      default: 'pending',
    },

    /** Caller's raw transcription text from Whisper (populated after call) */
    transcription: {
      type: String,
      default: null,
    },

    /** Detected intent from caller's voice response */
    response: {
      type: String,
      enum: ['YES', 'NO', 'UNKNOWN', 'unknown', null],
      default: null,
    },

    campaignId: {
      type: String,
      default: null,
      index: true,
    },

    /** Path to the saved recording file */
    recordingPath: {
      type: String,
      default: null,
    },

    /** Timestamp when the call was attempted */
    calledAt: {
      type: Date,
      default: null,
    },

    /** Any error message captured during the call attempt */
    errorMessage: {
      type: String,
      default: null,
    },
  },

  {
    timestamps: true, // adds createdAt, updatedAt
    collection: 'leads',
  }
);

// Index phone for quick lookups; status for queue filtering
leadSchema.index({ phone: 1 });
leadSchema.index({ status: 1 });

module.exports = mongoose.model('Lead', leadSchema);
