/**
 * CampaignService.js
 * Campaign execution engine — pure Exotel telecom flow.
 *
 * ARCHITECTURE (v2):
 *   1. Read leads from Excel → upsert into MongoDB (status: pending)
 *   2. Pull all pending leads from MongoDB
 *   3. For each lead: create CallLog → call Exotel API
 *   4. Exotel calls customer, fetches /voice-flow, plays greeting, records
 *   5. Recording webhook → RecordingService → ai-python transcription
 *   6. MongoDB updated throughout
 *
 *   ADB / Android dialing is NOT used anywhere in this class.
 */

const path   = require('path');
const XLSX   = require('xlsx');
const logger = require('../utils/logger');
const ExotelService = require('./ExotelService');
const CallLog  = require('../mongodb/models/CallLog');
const Lead     = require('../mongodb/models/Lead');

class CampaignService {
  constructor() {
    this._isRunning    = false;
    this._shouldStop   = false;
    this._state        = null;
    this._leadsFile    = process.env.LEADS_FILE_PATH || path.join(__dirname, '../../leads/leads.xlsx');
    this._callDelay    = parseInt(process.env.CALL_DELAY_MS        || '3000', 10);
    this._maxPerMinute = parseInt(process.env.MAX_CALLS_PER_MINUTE || '10',   10);
  }

  // ─── Phone Normalisation ──────────────────────────────────────────────────────

  /**
   * Normalise a raw phone string to E.164.
   * Handles: 10-digit Indian, 0XXXXXXXXXX, +91XXXXXXXXXX
   */
  normalizePhone(raw) {
    if (!raw) return null;

    let cleaned = String(raw).replace(/[\s\-\(\)\.]/g, '');

    // Strip leading zeros: 09876543210 → 9876543210
    if (cleaned.startsWith('0') && cleaned.length === 11) {
      cleaned = cleaned.slice(1);
    }

    // Already has +91
    if (cleaned.startsWith('+91') && cleaned.length === 13) return cleaned;

    // Has 91 prefix without +
    if (cleaned.startsWith('91') && cleaned.length === 12) return `+${cleaned}`;

    // Bare 10-digit number
    if (/^\d{10}$/.test(cleaned)) return `+91${cleaned}`;

    // E.164 with non-Indian prefix
    if (/^\+\d{7,15}$/.test(cleaned)) return cleaned;

    logger.warn('[Campaign] Cannot normalise phone number', { raw, cleaned });
    return null;
  }

  // ─── Excel Import ─────────────────────────────────────────────────────────────

  /**
   * Read Excel file and upsert leads into MongoDB.
   * Returns { total, inserted, skipped, invalid }
   */
  async importLeadsFromExcel() {
    const resolved = path.resolve(this._leadsFile);
    logger.info('[Campaign] Reading leads from Excel', { file: resolved });

    let rows;
    try {
      const wb   = XLSX.readFile(resolved);
      const ws   = wb.Sheets[wb.SheetNames[0]];
      rows = XLSX.utils.sheet_to_json(ws, { defval: '' });
    } catch (err) {
      throw new Error(`Failed to read Excel file: ${err.message}`);
    }

    logger.info(`[Campaign] ${rows.length} rows found in Excel`);

    let inserted = 0;
    let skipped  = 0;
    let invalid  = 0;

    for (const row of rows) {
      const name     = String(row['Name'] || row['name'] || row['NAME'] || '').trim();
      const rawPhone = String(
        row['Mobile Number'] || row['Mobile'] ||
        row['Phone']         || row['phone']  ||
        row['PHONE']         || ''
      ).trim();

      if (!name || !rawPhone) {
        invalid++;
        continue;
      }

      const phone = this.normalizePhone(rawPhone);
      if (!phone) { invalid++; continue; }

      try {
        const exists = await Lead.findOne({ phone }).lean();
        if (exists) { skipped++; continue; }

        await Lead.create({ name, phone, status: 'pending' });
        inserted++;
      } catch (err) {
        invalid++;
        logger.error('[Campaign] Lead upsert error', { name, phone, error: err.message });
      }
    }

    logger.info('[Campaign] Excel import complete', { total: rows.length, inserted, skipped, invalid });
    return { total: rows.length, inserted, skipped, invalid };
  }

  // ─── Campaign Execution ───────────────────────────────────────────────────────

  /**
   * Start a new campaign.
   * 1. Imports Excel into MongoDB
   * 2. Pulls all pending leads
   * 3. Calls each lead via Exotel sequentially in background
   */
  async startCampaign(campaignName = 'Campaign') {
    if (this._isRunning) throw new Error('Campaign already running');

    this._isRunning  = true;
    this._shouldStop = false;

    const campaignId = `camp_${Date.now()}`;

    logger.info('[Campaign] Starting campaign', { campaignId, campaignName });

    // Import fresh leads
    const importResult = await this.importLeadsFromExcel();

    // Pull pending leads
    const leads = await Lead.find({ status: 'pending' }).sort({ createdAt: 1 }).lean();

    if (leads.length === 0) {
      this._isRunning = false;
      throw new Error('No pending leads to process. Import leads first.');
    }

    this._state = {
      campaignId,
      campaignName,
      totalLeads:      leads.length,
      processedLeads:  0,
      successfulCalls: 0,
      failedCalls:     0,
      startTime:       new Date(),
      endTime:         null,
    };

    logger.info('[Campaign] Campaign initialised', {
      campaignId,
      leads: leads.length,
      importResult,
    });

    // Run calling loop in background — do not await
    this._runCampaignAsync(leads, campaignId).catch((err) => {
      logger.error('[Campaign] Async run error', { error: err.message });
      this._isRunning = false;
    });

    return {
      success:    true,
      campaignId,
      totalLeads: leads.length,
      message:    `Campaign started — ${leads.length} leads queued`,
    };
  }

  /**
   * Sequential calling loop — runs in background.
   */
  async _runCampaignAsync(leads, campaignId) {
    logger.info('[Campaign] Calling loop started', { total: leads.length });

    for (let i = 0; i < leads.length; i++) {
      if (this._shouldStop) {
        logger.info('[Campaign] Stop requested — halting loop');
        break;
      }

      const lead = leads[i];

      await this._callSingleLead(lead, campaignId);

      // Rate limiting: pause between calls
      if (i < leads.length - 1 && !this._shouldStop) {
        logger.debug(`[Campaign] Waiting ${this._callDelay}ms before next call`);
        await new Promise((resolve) => setTimeout(resolve, this._callDelay));
      }
    }

    this._isRunning = false;
    if (this._state) this._state.endTime = new Date();

    logger.info('[Campaign] Campaign complete', {
      campaignId,
      successful: this._state?.successfulCalls,
      failed:     this._state?.failedCalls,
    });
  }

  /**
   * Place a single Exotel call for one lead.
   */
  async _callSingleLead(lead, campaignId) {
    const { _id: leadMongoId, name, phone } = lead;

    logger.info('[Campaign] Processing lead', { name, phone, campaignId });

    // Create CallLog entry
    let callLog;
    try {
      callLog = await CallLog.create({
        campaignId,
        phoneNumber:  phone,
        customerName: name,
        status:       'initiated',
        startTime:    new Date(),
      });
    } catch (err) {
      logger.error('[Campaign] Failed to create CallLog', { phone, error: err.message });
      if (this._state) this._state.failedCalls++;
      if (this._state) this._state.processedLeads++;
      return;
    }

    // Mark lead as calling in MongoDB
    await Lead.findByIdAndUpdate(leadMongoId, { status: 'calling', calledAt: new Date() }).catch(() => {});

    // Place Exotel call
    try {
      const result = await ExotelService.initiateCallWithRetry({
        phoneNumber: phone,
        campaignId,
        leadId:      callLog._id.toString(),
      });

      await CallLog.findByIdAndUpdate(callLog._id, {
        callSid:        result.callSid,
        status:         'ringing',
        exotelResponse: result,
      });

      if (this._state) this._state.successfulCalls++;

      logger.info('[Campaign] Call initiated successfully', {
        callSid:  result.callSid,
        phone,
        name,
      });
    } catch (err) {
      logger.error('[Campaign] Exotel call failed', { phone, name, error: err.message });

      await CallLog.findByIdAndUpdate(callLog._id, {
        status:       'failed',
        errorMessage: err.message,
      }).catch(() => {});

      await Lead.findByIdAndUpdate(leadMongoId, { status: 'failed', errorMessage: err.message }).catch(() => {});

      if (this._state) this._state.failedCalls++;
    }

    if (this._state) this._state.processedLeads++;
  }

  // ─── Campaign Control ─────────────────────────────────────────────────────────

  stopCampaign() {
    this._shouldStop = true;
    logger.info('[Campaign] Stop requested');
    return { success: true, message: 'Campaign stop requested — will stop after current call' };
  }

  getCampaignStatus() {
    if (!this._state) {
      return { isRunning: false, message: 'No active campaign' };
    }

    const processed = this._state.processedLeads;
    const total     = this._state.totalLeads;

    return {
      isRunning:        this._isRunning,
      campaignId:       this._state.campaignId,
      campaignName:     this._state.campaignName,
      totalLeads:       total,
      processedLeads:   processed,
      successfulCalls:  this._state.successfulCalls,
      failedCalls:      this._state.failedCalls,
      progress:         `${processed}/${total}`,
      progressPercent:  total > 0 ? Math.round((processed / total) * 100) : 0,
      startTime:        this._state.startTime,
      endTime:          this._state.endTime,
      mode:             'exotel',
      adbDisabled:      true,
    };
  }
}

module.exports = new CampaignService();
