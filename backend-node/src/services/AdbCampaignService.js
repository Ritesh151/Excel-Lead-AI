/**
 * AdbCampaignService.js
 * Campaign orchestration engine for ADB+Android+Kotlin GSM flow.
 */

const path   = require('path');
const XLSX   = require('xlsx');
const logger = require('../utils/logger');
const Lead   = require('../mongodb/models/Lead');
const Campaign = require('../mongodb/models/Campaign');
const wsServer = require('../socket/WebSocketServer');
const aiPython = require('./AiPythonClient');
const { syncLeadFromCallResult, normalizeIntent } = require('./LeadSyncService');

class AdbCampaignService {
  constructor() {
    this._isRunning    = false;
    this._shouldStop   = false;
    this._state        = null;
    this._leadsFile    = process.env.LEADS_FILE_PATH || path.join(__dirname, '../../leads/leads.xlsx');
    this._callDelay    = parseInt(process.env.CALL_DELAY_MS        || '5000',  10);
    this._callTimeout  = parseInt(process.env.CALL_TIMEOUT_MS      || '180000', 10);
  }

  normalizePhone(raw) {
    if (!raw) return null;
    let cleaned = String(raw).replace(/[\s\-\(\)\.]/g, '');
    if (cleaned.startsWith('0') && cleaned.length === 11) cleaned = cleaned.slice(1);
    if (cleaned.startsWith('+91') && cleaned.length === 13) return cleaned;
    if (cleaned.startsWith('91') && cleaned.length === 12) return `+${cleaned}`;
    if (/^\d{10}$/.test(cleaned)) return `+91${cleaned}`;
    if (/^\+\d{7,15}$/.test(cleaned)) return cleaned;
    logger.warn('[AdbCampaign] Cannot normalise phone', { raw, cleaned });
    return null;
  }

  async importLeadsFromExcel() {
    const resolved = path.resolve(this._leadsFile);
    logger.info('[AdbCampaign] Reading leads from Excel', { file: resolved });

    let rows;
    try {
      const wb = XLSX.readFile(resolved);
      const ws = wb.Sheets[wb.SheetNames[0]];
      rows = XLSX.utils.sheet_to_json(ws, { defval: '' });
    } catch (err) {
      throw new Error(`Failed to read Excel file: ${err.message}`);
    }

    let inserted = 0, skipped = 0, invalid = 0;

    for (const row of rows) {
      const name = String(row['Name'] || row['name'] || row['NAME'] || '').trim();
      const rawPhone = String(
        row['Mobile Number'] || row['Mobile'] || row['Phone'] || row['phone'] || ''
      ).trim();

      if (!rawPhone) { invalid++; continue; }

      const phone = this.normalizePhone(rawPhone);
      if (!phone) { invalid++; continue; }

      try {
        const exists = await Lead.findOne({ phone }).lean();
        if (exists) { skipped++; continue; }
        await Lead.create({ name: name || 'Unknown', phone, status: 'pending' });
        inserted++;
      } catch (err) {
        invalid++;
        logger.error('[AdbCampaign] Lead upsert error', { name, phone, error: err.message });
      }
    }

    return { total: rows.length, inserted, skipped, invalid };
  }

  async startCampaign(campaignName = 'ADB Campaign') {
    if (this._isRunning) throw new Error('Campaign already running');

    this._isRunning  = true;
    this._shouldStop = false;

    const campaignId = `adb_${Date.now()}`;
    const importResult = await this.importLeadsFromExcel();
    const leads = await Lead.find({ status: 'pending' }).sort({ createdAt: 1 }).lean();

    if (leads.length === 0) {
      this._isRunning = false;
      throw new Error('No pending leads to process');
    }

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
    };

    await Campaign.create({
      campaignId,
      name: campaignName,
      mode: 'adb+android',
      status: 'running',
      totalLeads: leads.length,
    }).catch((e) => logger.warn('[AdbCampaign] Campaign doc save failed', { error: e.message }));

    wsServer.emitLog('info', `Campaign started: ${campaignId} (${leads.length} leads)`);

    this._runCampaignAsync(leads, campaignId).catch((err) => {
      logger.error('[AdbCampaign] Async run error', { error: err.message });
      this._isRunning = false;
    });

    return {
      success:    true,
      campaignId,
      totalLeads: leads.length,
      mode:       'adb+android',
      message:    `ADB campaign started — ${leads.length} leads queued`,
      importResult,
    };
  }

  async _runCampaignAsync(leads, campaignId) {
    for (let i = 0; i < leads.length; i++) {
      if (this._shouldStop) break;

      const lead = leads[i];
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

      if (i < leads.length - 1 && !this._shouldStop) {
        await new Promise((resolve) => setTimeout(resolve, this._callDelay));
      }
    }

    this._isRunning = false;
    if (this._state) this._state.endTime = new Date();

    const summary = {
      processed: this._state?.processedLeads,
      total:     this._state?.totalLeads,
      yes:       this._state?.yesCount,
      no:        this._state?.noCount,
      failed:    this._state?.failedCalls,
    };

    wsServer.emitCampaignDone(campaignId, summary);
    wsServer.emitLog('info', `Campaign complete: ${JSON.stringify(summary)}`);

    await Campaign.findOneAndUpdate(
      { campaignId },
      {
        status: 'completed',
        endTime: new Date(),
        processedLeads: this._state?.processedLeads,
        successfulCalls: this._state?.successfulCalls,
        failedCalls: this._state?.failedCalls,
        yesCount: this._state?.yesCount,
        noCount: this._state?.noCount,
      }
    ).catch(() => {});
  }

  async _callSingleLead(lead, campaignId) {
    const { _id: leadMongoId, name, phone } = lead;

    wsServer.emitCallStarted(phone, name, campaignId);

    await Lead.findByIdAndUpdate(leadMongoId, {
      status: 'calling',
      calledAt: new Date(),
      campaignId,
    }).catch(() => {});

    try {
      const result = await aiPython.executeCall(name, phone);

      const success = Boolean(result.success);
      const intentRaw = result.intent || 'UNKNOWN';
      const intentNorm = normalizeIntent(intentRaw);

      await syncLeadFromCallResult({
        phone,
        intent: intentRaw,
        transcription: result.transcription,
        recordingPath: result.recording_file,
        success,
        errorMessage: result.error,
      });

      await Lead.findByIdAndUpdate(leadMongoId, {
        status: success ? 'completed' : 'failed',
        transcription: result.transcription || null,
        response: intentNorm,
        recordingPath: result.recording_file || null,
        errorMessage: result.error || null,
      }).catch(() => {});

      if (success) {
        if (this._state) {
          this._state.successfulCalls++;
          if (intentRaw === 'YES') this._state.yesCount++;
          else if (intentRaw === 'NO') this._state.noCount++;
        }
        wsServer.emitCallCompleted(phone, intentRaw, result.transcription || '', null);
      } else {
        if (this._state) this._state.failedCalls++;
        wsServer.emitCallFailed(phone, result.error || 'Call failed');
      }
    } catch (err) {
      logger.error('[AdbCampaign] Call failed', { phone, name, error: err.message });

      await Lead.findByIdAndUpdate(leadMongoId, {
        status: 'failed',
        errorMessage: err.message,
      }).catch(() => {});

      await syncLeadFromCallResult({
        phone,
        intent: 'UNKNOWN',
        success: false,
        errorMessage: err.message,
      });

      if (this._state) this._state.failedCalls++;
      wsServer.emitCallFailed(phone, err.message);
    }

    if (this._state) this._state.processedLeads++;
  }

  stopCampaign() {
    this._shouldStop = true;
    if (this._state?.campaignId) {
      Campaign.findOneAndUpdate(
        { campaignId: this._state.campaignId },
        { status: 'stopped', endTime: new Date() }
      ).catch(() => {});
    }
    return { success: true, message: 'Campaign stop requested — will stop after current call' };
  }

  getCampaignStatus() {
    if (!this._state) {
      return { isRunning: false, mode: 'adb+android', message: 'No active campaign' };
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
      progress:         `${processed}/${total}`,
      progressPercent:  total > 0 ? Math.round((processed / total) * 100) : 0,
      startTime:        this._state.startTime,
      endTime:          this._state.endTime,
    };
  }
}

module.exports = new AdbCampaignService();
