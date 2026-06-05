/**
 * AdbCampaignService.js
 * Production campaign orchestration engine — ADB+Android+Kotlin GSM flow.
 *
 * FIXED:
 *  - Pre-flight ai-python health check before campaign start
 *  - Explicit Excel column resolution (Name, Mobile Number)
 *  - Retry logic with backoff when ai-python is temporarily unavailable
 *  - Stuck-lead recovery: leads stuck in 'calling' status are reset on startup
 *  - Detailed logging at every step
 *  - getCampaignStatus() always returns consistent shape
 */

'use strict';

const path   = require('path');
const XLSX   = require('xlsx');
const logger = require('../utils/logger');
const Lead   = require('../mongodb/models/Lead');
const Campaign = require('../mongodb/models/Campaign');
const wsServer = require('../socket/WebSocketServer');
const aiPython = require('./AiPythonClient');
const { syncLeadFromCallResult, normalizeIntent } = require('./LeadSyncService');

// ─── Constants ────────────────────────────────────────────────────────────────

const CALL_DELAY_MS       = parseInt(process.env.CALL_DELAY_MS        || '5000',  10);
const CALL_TIMEOUT_MS     = parseInt(process.env.CALL_TIMEOUT_MS      || '180000', 10);
const AI_RETRY_ATTEMPTS   = parseInt(process.env.AI_ENGINE_MAX_RETRIES || '2',     10);
const AI_RETRY_DELAY_MS   = parseInt(process.env.AI_ENGINE_RETRY_DELAY_MS || '3000', 10);
const PREFLIGHT_TIMEOUT   = 10_000;


class AdbCampaignService {
  constructor() {
    this._isRunning    = false;
    this._shouldStop   = false;
    this._state        = null;
    this._leadsFile    = process.env.LEADS_FILE_PATH
      || path.join(__dirname, '../../../leads/leads.xlsx');
  }

  // ─── Phone normalisation ────────────────────────────────────────────────────

  normalizePhone(raw) {
    if (!raw) return null;
    let cleaned = String(raw).replace(/[\s\-\(\)\.]/g, '');

    // Remove leading zeros for 11-digit numbers (0XXXXXXXXXX)
    if (cleaned.startsWith('0') && cleaned.length === 11) cleaned = cleaned.slice(1);

    // Already E.164 with country code
    if (cleaned.startsWith('+91') && cleaned.length === 13) return cleaned;
    if (cleaned.startsWith('91') && cleaned.length === 12)  return `+${cleaned}`;

    // Bare 10-digit Indian number
    if (/^\d{10}$/.test(cleaned)) return `+91${cleaned}`;

    // International format
    if (/^\+\d{7,15}$/.test(cleaned)) return cleaned;

    logger.warn('[AdbCampaign] Cannot normalise phone', { raw, cleaned });
    return null;
  }

  // ─── Excel import ────────────────────────────────────────────────────────────

  async importLeadsFromExcel() {
    const resolved = path.resolve(this._leadsFile);
    logger.info('[AdbCampaign] Reading leads from Excel', { file: resolved });

    let rows;
    try {
      const wb   = XLSX.readFile(resolved);
      const ws   = wb.Sheets[wb.SheetNames[0]];
      rows = XLSX.utils.sheet_to_json(ws, { defval: '' });
    } catch (err) {
      throw new Error(`Failed to read Excel file "${resolved}": ${err.message}`);
    }

    logger.info(`[AdbCampaign] Excel rows read: ${rows.length}`);

    if (rows.length === 0) {
      logger.warn('[AdbCampaign] Excel file is empty or has no data rows');
      return { total: 0, inserted: 0, skipped: 0, invalid: 0 };
    }

    // Log first row's keys to help debug column name issues
    logger.info('[AdbCampaign] Excel columns detected:', Object.keys(rows[0]));

    let inserted = 0, skipped = 0, invalid = 0;

    for (const row of rows) {
      // Flexible column name resolution
      const name = String(
        row['Name'] || row['name'] || row['NAME'] ||
        row['Customer Name'] || row['customer_name'] || ''
      ).trim();

      const rawPhone = String(
        row['Mobile Number'] || row['mobile number'] || row['Mobile'] || row['mobile_number'] ||
        row['Phone'] || row['phone'] || row['Phone Number'] ||
        row['mobile'] || row['MOBILE'] || ''
      ).trim();

      if (!rawPhone) {
        logger.debug('[AdbCampaign] Row skipped — no phone field', { row });
        invalid++;
        continue;
      }

      const phone = this.normalizePhone(rawPhone);
      if (!phone) {
        logger.warn('[AdbCampaign] Invalid phone, row skipped', { name, rawPhone });
        invalid++;
        continue;
      }

      try {
        const exists = await Lead.findOne({ phone }).lean();
        if (exists) {
          // If existing lead is not pending (completed, failed, skipped), reset to pending
          if (exists.status !== 'pending') {
            await Lead.findOneAndUpdate({ phone }, { $set: { status: 'pending', campaignId: null } });
            logger.debug(`[AdbCampaign] Lead reset to pending: ${phone} (was ${exists.status})`);
            inserted++;
            continue;
          }
          logger.debug(`[AdbCampaign] Duplicate skipped: ${phone} (already pending)`);
          skipped++;
          continue;
        }
        await Lead.create({ name: name || 'Unknown', phone, status: 'pending' });
        logger.debug(`[AdbCampaign] Lead inserted: ${name} (${phone})`);
        inserted++;
      } catch (err) {
        invalid++;
        logger.error('[AdbCampaign] Lead insert error', { name, phone, error: err.message });
      }
    }

    const summary = { total: rows.length, inserted, skipped, invalid };
    logger.info('[AdbCampaign] Import complete', summary);
    return summary;
  }

  // ─── Pre-flight checks ────────────────────────────────────────────────────────

  async _preflightChecks() {
    logger.info('[AdbCampaign] Running pre-flight checks...');

    // 1. ai-python health
    try {
      const health = await Promise.race([
        aiPython.health(),
        new Promise((_, reject) =>
          setTimeout(() => reject(new Error('health check timed out')), PREFLIGHT_TIMEOUT)
        ),
      ]);
      logger.info('[AdbCampaign] ✓ ai-python is reachable', { status: health?.status });
    } catch (err) {
      throw new Error(
        `ai-python is NOT reachable at ${aiPython.baseUrl}. ` +
        `Start it with: cd ai-python && python main.py server\n` +
        `Error: ${err.message}`
      );
    }

    // 2. Reset stuck leads (status='calling' from a previous crashed campaign)
    const stuckCount = await Lead.countDocuments({ status: 'calling' });
    if (stuckCount > 0) {
      logger.warn(`[AdbCampaign] Resetting ${stuckCount} stuck leads (status=calling → pending)`);
      await Lead.updateMany({ status: 'calling' }, { $set: { status: 'pending' } });
    }

    logger.info('[AdbCampaign] ✓ Pre-flight checks passed');
  }

  // ─── Campaign start ────────────────────────────────────────────────────────────

  async startCampaign(campaignName = 'ADB Campaign') {
    if (this._isRunning) {
      throw new Error('Campaign already running — call /api/adb/stop first');
    }

    // Pre-flight: ensure ai-python is up before we even import leads
    await this._preflightChecks();

    this._isRunning  = true;
    this._shouldStop = false;

    const campaignId   = `adb_${Date.now()}`;
    const importResult = await this.importLeadsFromExcel();

    const leads = await Lead.find({ status: 'pending' })
      .sort({ createdAt: 1 })
      .lean();

    if (leads.length === 0) {
      this._isRunning = false;
      throw new Error(
        'No pending leads to process. ' +
        'Either the Excel file has no valid rows or all leads are already processed.'
      );
    }

    logger.info(`[AdbCampaign] Starting campaign ${campaignId} with ${leads.length} leads`);

    this._state = {
      campaignId,
      campaignName,
      totalLeads:      leads.length,
      processedLeads:  0,
      successfulCalls: 0,
      failedCalls:     0,
      yesCount:        0,
      noCount:         0,
      startTime:       new Date(),
      endTime:         null,
      currentPhone:    null,
    };

    await Campaign.create({
      campaignId,
      name:        campaignName,
      mode:        'adb+android',
      status:      'running',
      totalLeads:  leads.length,
    }).catch((e) => logger.warn('[AdbCampaign] Campaign doc create failed', { error: e.message }));

    wsServer.emitLog('info', `Campaign ${campaignId} started — ${leads.length} leads queued`);
    wsServer.emit('campaign_started', {
      campaignId,
      totalLeads: leads.length,
      mode: 'adb+android',
    });

    // Run async — do not await here so HTTP response returns immediately
    this._runCampaignAsync(leads, campaignId).catch((err) => {
      logger.error('[AdbCampaign] Async run fatal error', { error: err.message });
      this._isRunning = false;
    });

    return {
      success:      true,
      campaignId,
      totalLeads:   leads.length,
      mode:         'adb+android',
      message:      `ADB campaign started — ${leads.length} leads queued`,
      importResult,
    };
  }

  // ─── Campaign execution loop ──────────────────────────────────────────────────

  async _runCampaignAsync(leads, campaignId) {
    logger.info(`[AdbCampaign] Campaign loop started: ${leads.length} leads`);

    for (let i = 0; i < leads.length; i++) {
      if (this._shouldStop) {
        logger.info('[AdbCampaign] Stop requested — halting campaign loop');
        break;
      }

      const lead = leads[i];
      logger.info(
        `[AdbCampaign] ──── Lead ${i + 1}/${leads.length}: ${lead.name} (${lead.phone}) ────`
      );

      if (this._state) this._state.currentPhone = lead.phone;

      await this._callSingleLead(lead, campaignId);

      if (this._state) {
        wsServer.emitCampaignProgress(
          campaignId,
          this._state.processedLeads,
          this._state.totalLeads,
          this._state.yesCount,
          this._state.noCount
        );
      }

      // Inter-call delay (skip after last lead)
      if (i < leads.length - 1 && !this._shouldStop) {
        logger.info(`[AdbCampaign] Waiting ${CALL_DELAY_MS}ms before next call...`);
        await new Promise((resolve) => setTimeout(resolve, CALL_DELAY_MS));
      }
    }

    // Campaign complete
    this._isRunning = false;
    if (this._state) {
      this._state.endTime = new Date();
      this._state.currentPhone = null;
    }

    const summary = {
      processed: this._state?.processedLeads  ?? 0,
      total:     this._state?.totalLeads       ?? 0,
      yes:       this._state?.yesCount         ?? 0,
      no:        this._state?.noCount          ?? 0,
      failed:    this._state?.failedCalls      ?? 0,
    };

    logger.info('[AdbCampaign] Campaign complete', summary);
    wsServer.emitCampaignDone(campaignId, summary);
    wsServer.emitLog('info', `Campaign complete: ${JSON.stringify(summary)}`);

    await Campaign.findOneAndUpdate(
      { campaignId },
      {
        status:          'completed',
        endTime:         new Date(),
        processedLeads:  this._state?.processedLeads,
        successfulCalls: this._state?.successfulCalls,
        failedCalls:     this._state?.failedCalls,
        yesCount:        this._state?.yesCount,
        noCount:         this._state?.noCount,
      }
    ).catch(() => {});
  }

  // ─── Single lead call ─────────────────────────────────────────────────────────

  async _callSingleLead(lead, campaignId) {
    const { _id: leadMongoId, name, phone } = lead;

    logger.info(`[AdbCampaign] Calling: ${name} (${phone})`);
    wsServer.emitCallStarted(phone, name, campaignId);

    // Mark lead as 'calling'
    await Lead.findByIdAndUpdate(leadMongoId, {
      status: 'calling',
      calledAt: new Date(),
      campaignId,
    }).catch(() => {});

    let result = null;
    let lastError = null;

    // Retry loop for transient ai-python errors
    for (let attempt = 1; attempt <= AI_RETRY_ATTEMPTS; attempt++) {
      try {
        logger.info(`[AdbCampaign] POST /api/call/execute → ${phone} (attempt ${attempt}/${AI_RETRY_ATTEMPTS})`);

        result = await aiPython.executeCall(name, phone);

        logger.info('[AdbCampaign] ai-python response', {
          phone,
          success: result.success,
          intent:  result.intent,
          stage:   result.stage,
          transcription: (result.transcription || '').slice(0, 80),
        });

        // If we got a response (even unsuccessful), stop retrying
        break;

      } catch (err) {
        lastError = err;
        const isRetryable =
          !err.response ||
          err.response?.status >= 500 ||
          err.code === 'ECONNABORTED' ||
          err.code === 'ECONNREFUSED';

        logger.warn(`[AdbCampaign] Attempt ${attempt} failed: ${err.message}`, {
          phone,
          retryable: isRetryable,
          status: err.response?.status,
        });

        if (!isRetryable || attempt >= AI_RETRY_ATTEMPTS) break;

        const delay = AI_RETRY_DELAY_MS * attempt;
        logger.info(`[AdbCampaign] Retrying in ${delay}ms...`);
        await new Promise((r) => setTimeout(r, delay));
      }
    }

    // ── Process result ────────────────────────────────────────────────────────
    if (result) {
      const success    = Boolean(result.success);
      const intentRaw  = result.intent || 'UNKNOWN';
      const intentNorm = normalizeIntent(intentRaw);

      await syncLeadFromCallResult({
        phone,
        intent:        intentRaw,
        transcription: result.transcription,
        recordingPath: result.recording_file,
        success,
        errorMessage:  result.error,
      });

      await Lead.findByIdAndUpdate(leadMongoId, {
        status:        success ? 'completed' : 'failed',
        transcription: result.transcription || null,
        response:      intentNorm,
        recordingPath: result.recording_file || null,
        errorMessage:  result.error || null,
      }).catch(() => {});

      if (success) {
        if (this._state) {
          this._state.successfulCalls++;
          if (intentRaw === 'YES')      this._state.yesCount++;
          else if (intentRaw === 'NO')  this._state.noCount++;
        }
        wsServer.emitCallCompleted(phone, intentRaw, result.transcription || '', null);
        logger.info(`[AdbCampaign] ✓ ${phone} → ${intentRaw}`);
      } else {
        if (this._state) this._state.failedCalls++;
        wsServer.emitCallFailed(phone, result.error || 'Call returned success=false');
        logger.warn(`[AdbCampaign] ✗ ${phone} → failed: ${result.error || 'no error detail'}`);
      }

    } else {
      // ai-python never responded
      const errMsg = lastError?.message || 'ai-python unreachable';
      logger.error(`[AdbCampaign] No result for ${phone}: ${errMsg}`);

      await Lead.findByIdAndUpdate(leadMongoId, {
        status:       'failed',
        errorMessage: errMsg,
      }).catch(() => {});

      await syncLeadFromCallResult({
        phone,
        intent:       'UNKNOWN',
        success:      false,
        errorMessage: errMsg,
      });

      if (this._state) this._state.failedCalls++;
      wsServer.emitCallFailed(phone, errMsg);
    }

    if (this._state) this._state.processedLeads++;
  }

  // ─── Stop campaign ────────────────────────────────────────────────────────────

  stopCampaign() {
    this._shouldStop = true;
    logger.info('[AdbCampaign] Stop requested — will stop after current call completes');

    if (this._state?.campaignId) {
      Campaign.findOneAndUpdate(
        { campaignId: this._state.campaignId },
        { status: 'stopped', endTime: new Date() }
      ).catch(() => {});
    }

    return {
      success: true,
      message: 'Campaign stop requested — will stop after current call',
    };
  }

  // ─── Status ────────────────────────────────────────────────────────────────────

  getCampaignStatus() {
    if (!this._state) {
      return {
        isRunning:        false,
        mode:             'adb+android',
        message:          'No active campaign',
        totalLeads:       0,
        processedLeads:   0,
        successfulCalls:  0,
        failedCalls:      0,
        yesCount:         0,
        noCount:          0,
        progress:         '0/0',
        progressPercent:  0,
      };
    }

    const processed = this._state.processedLeads;
    const total     = this._state.totalLeads;

    return {
      isRunning:        this._isRunning,
      campaignId:       this._state.campaignId,
      campaignName:     this._state.campaignName,
      mode:             'adb+android',
      totalLeads:       total,
      processedLeads:   processed,
      successfulCalls:  this._state.successfulCalls,
      failedCalls:      this._state.failedCalls,
      yesCount:         this._state.yesCount,
      noCount:          this._state.noCount,
      currentPhone:     this._state.currentPhone,
      progress:         `${processed}/${total}`,
      progressPercent:  total > 0 ? Math.round((processed / total) * 100) : 0,
      startTime:        this._state.startTime,
      endTime:          this._state.endTime,
    };
  }
}

module.exports = new AdbCampaignService();
