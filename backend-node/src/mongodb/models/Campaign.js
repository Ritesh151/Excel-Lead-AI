/**
 * Campaign.js — persisted campaign run metadata
 */

const mongoose = require('mongoose');

const campaignSchema = new mongoose.Schema(
  {
    campaignId: { type: String, required: true, unique: true, index: true },
    name: { type: String, default: 'ADB Campaign' },
    mode: { type: String, default: 'adb+android' },
    status: {
      type: String,
      enum: ['running', 'stopped', 'completed', 'failed'],
      default: 'running',
    },
    totalLeads: { type: Number, default: 0 },
    processedLeads: { type: Number, default: 0 },
    successfulCalls: { type: Number, default: 0 },
    failedCalls: { type: Number, default: 0 },
    yesCount: { type: Number, default: 0 },
    noCount: { type: Number, default: 0 },
    startTime: { type: Date, default: Date.now },
    endTime: { type: Date, default: null },
  },
  { timestamps: true, collection: 'campaigns' }
);

campaignSchema.index({ status: 1, createdAt: -1 });

module.exports = mongoose.model('Campaign', campaignSchema);
