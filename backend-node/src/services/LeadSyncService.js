/**
 * LeadSyncService.js
 * Synchronizes MongoDB Lead documents from call_results / transcription payloads.
 * Single source of truth for campaign UI: leads collection on backend-node.
 */

'use strict';

const Lead = require('../mongodb/models/Lead');
const logger = require('../utils/logger');

/**
 * Normalise intent for Lead.response enum: YES | NO | unknown | null
 */
function normalizeIntent(intent) {
  if (!intent) return 'unknown';
  const u = String(intent).toUpperCase();
  if (u === 'YES') return 'YES';
  if (u === 'NO') return 'NO';
  return 'unknown';
}

/**
 * Normalise phone to E.164 (+91…) for lookup
 */
function normalizePhone(raw) {
  if (!raw) return null;
  let cleaned = String(raw).replace(/[\s\-\(\)\.]/g, '');
  if (cleaned.startsWith('0') && cleaned.length === 11) cleaned = cleaned.slice(1);
  if (cleaned.startsWith('+91') && cleaned.length === 13) return cleaned;
  if (cleaned.startsWith('91') && cleaned.length === 12) return `+${cleaned}`;
  if (/^\d{10}$/.test(cleaned)) return `+91${cleaned}`;
  if (/^\+\d{7,15}$/.test(cleaned)) return cleaned;
  if (!cleaned.startsWith('+')) return `+${cleaned}`;
  return cleaned;
}

/**
 * Update lead by phone after transcription or call completion.
 */
async function syncLeadFromCallResult({
  phone,
  intent,
  transcription,
  recordingPath,
  success = true,
  errorMessage = null,
}) {
  const normalized = normalizePhone(phone);
  if (!normalized) {
    logger.warn('[LeadSync] Cannot sync — invalid phone', { phone });
    return null;
  }

  const status = success ? 'completed' : 'failed';
  const response = normalizeIntent(intent);

  try {
    const lead = await Lead.findOneAndUpdate(
      { phone: normalized },
      {
        $set: {
          status,
          transcription: transcription || null,
          response,
          recordingPath: recordingPath || null,
          calledAt: new Date(),
          errorMessage: errorMessage || null,
        },
      },
      { new: true, sort: { createdAt: -1 } }
    ).lean();

    if (lead) {
      logger.info('[LeadSync] Lead updated', { phone: normalized, status, response });
    } else {
      logger.debug('[LeadSync] No lead document for phone', { phone: normalized });
    }
    return lead;
  } catch (err) {
    logger.error('[LeadSync] Update failed', { phone: normalized, error: err.message });
    return null;
  }
}

module.exports = {
  syncLeadFromCallResult,
  normalizeIntent,
  normalizePhone,
};
