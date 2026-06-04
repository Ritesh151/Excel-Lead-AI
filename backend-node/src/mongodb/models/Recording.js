/**
 * Recording.js — metadata for uploaded call recordings
 */

const mongoose = require('mongoose');

const recordingSchema = new mongoose.Schema(
  {
    phone: { type: String, required: true, index: true },
    filePath: { type: String, required: true },
    fileSize: { type: Number, default: 0 },
    durationSec: { type: Number, default: 0 },
    source: { type: String, enum: ['android', 'exotel', 'adb'], default: 'android' },
    transcription: { type: String, default: null },
    intent: { type: String, default: null },
    callRecordId: { type: String, default: null },
  },
  { timestamps: true, collection: 'recordings' }
);

recordingSchema.index({ createdAt: -1 });

module.exports = mongoose.model('Recording', recordingSchema);
