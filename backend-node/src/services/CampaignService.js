/**
 * CampaignService.js
 * Campaign execution and lead processing engine
 * Responsibilities:
 *   - Read leads from Excel
 *   - Validate phone numbers
 *   - Manage campaign state
 *   - Execute sequential calling
 *   - Handle retries and failures
 *   - Track progress and results
 */

const fs = require('fs').promises;
const path = require('path');
const XLSX = require('xlsx');
const logger = require('../utils/logger');
const ExotelService = require('./ExotelService');
const CallLog = require('../mongodb/models/CallLog');
const Lead = require('../mongodb/models/Lead');

class CampaignService {
  constructor() {
    this.campaignState = null;
    this.isRunning = false;
    this.leadsFile = process.env.LEADS_FILE_PATH || path.join(__dirname, '../../leads/leads.xlsx');
    this.callDelay = parseInt(process.env.CALL_DELAY_MS || '2000', 10); // 2 second delay between calls
    this.maxCallsPerMinute = parseInt(process.env.MAX_CALLS_PER_MINUTE || '10', 10);
  }

  /**
   * Read and parse Excel leads file
   * @returns {Promise<Array>} Array of lead objects
   */
  async readLeads() {
    try {
      logger.info('Reading leads from Excel', { file: this.leadsFile });

      const fileContent = await fs.readFile(this.leadsFile);
      const workbook = XLSX.read(fileContent, { type: 'buffer' });
      const sheetName = workbook.SheetNames[0];
      const sheet = workbook.Sheets[sheetName];
      const data = XLSX.utils.sheet_to_json(sheet);

      logger.info(`Read ${data.length} rows from Excel`);

      return data;
    } catch (error) {
      logger.error('Failed to read leads file', { error: error.message });
      throw error;
    }
  }

  /**
   * Normalize and validate phone number to E.164 format
   * @param {string} phoneNumber - Raw phone number
   * @returns {string|null} Normalized phone or null if invalid
   */
  normalizePhoneNumber(phoneNumber) {
    if (!phoneNumber) return null;

    // Remove spaces, dashes, parentheses
    let cleaned = phoneNumber.toString().replace(/[\s\-()]/g, '');

    // Remove leading 0 if followed by 10 digits (India standard)
    if (cleaned.startsWith('0') && cleaned.length === 11) {
      cleaned = cleaned.substring(1);
    }

    // Add country code if not present
    if (!cleaned.startsWith('+')) {
      if (cleaned.startsWith('91') && cleaned.length === 12) {
        // Already has country code
        cleaned = `+${cleaned}`;
      } else if (cleaned.length === 10) {
        // Assume India (91)
        cleaned = `+91${cleaned}`;
      }
    }

    // Validate E.164 format: + followed by 1-15 digits
    if (/^\+\d{1,15}$/.test(cleaned)) {
      return cleaned;
    }

    logger.warn('Invalid phone number', { original: phoneNumber, cleaned });
    return null;
  }

  /**
   * Process and validate leads from Excel
   * @param {Array} rawLeads - Raw lead data from Excel
   * @returns {Promise<Array>} Validated lead objects
   */
  async processLeads(rawLeads) {
    const validLeads = [];
    const phoneNumbers = new Set();

    for (const lead of rawLeads) {
      const name = (lead.Name || lead.name || '').trim();
      const phoneNumber = this.normalizePhoneNumber(lead['Mobile Number'] || lead['mobile number'] || lead.Phone);

      if (!name || !phoneNumber) {
        logger.warn('Skipping invalid lead', { name, phoneNumber });
        continue;
      }

      // Remove duplicates
      if (phoneNumbers.has(phoneNumber)) {
        logger.warn('Duplicate phone number, skipping', { phoneNumber });
        continue;
      }

      phoneNumbers.add(phoneNumber);
      validLeads.push({
        name,
        phoneNumber,
        status: 'pending',
        createdAt: new Date(),
      });
    }

    logger.info('Processed leads', {
      total: rawLeads.length,
      valid: validLeads.length,
      duplicates: rawLeads.length - validLeads.length,
    });

    return validLeads;
  }

  /**
   * Start campaign execution
   * @param {string} campaignName - Campaign name
   * @returns {Promise<Object>} Campaign ID and initial status
   */
  async startCampaign(campaignName = 'Campaign') {
    if (this.isRunning) {
      throw new Error('Campaign already running');
    }

    try {
      logger.info('Starting campaign', { campaignName });

      // Read and process leads
      const rawLeads = await this.readLeads();
      const validLeads = await this.processLeads(rawLeads);

      if (validLeads.length === 0) {
        throw new Error('No valid leads to process');
      }

      // Initialize campaign
      this.campaignState = {
        campaignId: `camp_${Date.now()}`,
        campaignName,
        totalLeads: validLeads.length,
        processedLeads: 0,
        successfulCalls: 0,
        failedCalls: 0,
        startTime: new Date(),
        leads: validLeads,
        isRunning: true,
      };

      this.isRunning = true;
      logger.info('Campaign initialized', {
        campaignId: this.campaignState.campaignId,
        leads: this.campaignState.totalLeads,
      });

      // Start async campaign execution (non-blocking)
      this.executeCampaignAsync();

      return {
        success: true,
        campaignId: this.campaignState.campaignId,
        totalLeads: this.campaignState.totalLeads,
        message: 'Campaign started',
      };
    } catch (error) {
      this.isRunning = false;
      logger.error('Campaign start failed', { error: error.message });
      throw error;
    }
  }

  /**
   * Execute campaign asynchronously (non-blocking)
   */
  async executeCampaignAsync() {
    try {
      const { leads, campaignId } = this.campaignState;

      for (let i = 0; i < leads.length; i++) {
        if (!this.isRunning) {
          logger.info('Campaign stopped by user');
          break;
        }

        const lead = leads[i];
        await this.executeSingleCall(lead, campaignId);

        // Respect rate limiting
        if (i < leads.length - 1) {
          await new Promise((resolve) => setTimeout(resolve, this.callDelay));
        }
      }

      this.isRunning = false;
      this.campaignState.endTime = new Date();
      logger.info('Campaign completed', {
        campaignId,
        successful: this.campaignState.successfulCalls,
        failed: this.campaignState.failedCalls,
      });
    } catch (error) {
      logger.error('Campaign execution error', { error: error.message });
      this.isRunning = false;
    }
  }

  /**
   * Execute single call for a lead
   * @param {Object} lead - Lead object
   * @param {string} campaignId - Campaign ID
   */
  async executeSingleCall(lead, campaignId) {
    try {
      logger.info('Processing lead', {
        name: lead.name,
        phoneNumber: lead.phoneNumber,
      });

      // Create call log record
      const callLog = await CallLog.create({
        campaignId,
        phoneNumber: lead.phoneNumber,
        customerName: lead.name,
        status: 'initiated',
        startTime: new Date(),
      });

      // Initiate Exotel call
      const callResult = await ExotelService.initiateCallWithRetry({
        phoneNumber: lead.phoneNumber,
        campaignId,
        leadId: callLog._id.toString(),
      });

      // Update call log
      await CallLog.findByIdAndUpdate(callLog._id, {
        callSid: callResult.callSid,
        status: 'ringing',
        exotelResponse: callResult,
      });

      this.campaignState.successfulCalls += 1;
      logger.info('Call initiated successfully', {
        callSid: callResult.callSid,
        phoneNumber: lead.phoneNumber,
      });
    } catch (error) {
      this.campaignState.failedCalls += 1;
      logger.error('Call execution failed', {
        name: lead.name,
        phoneNumber: lead.phoneNumber,
        error: error.message,
      });

      // Log failure
      try {
        await CallLog.create({
          campaignId,
          phoneNumber: lead.phoneNumber,
          customerName: lead.name,
          status: 'failed',
          errorMessage: error.message,
          startTime: new Date(),
        });
      } catch (logError) {
        logger.error('Failed to log call error', { error: logError.message });
      }
    }

    this.campaignState.processedLeads += 1;
  }

  /**
   * Stop running campaign
   */
  stopCampaign() {
    this.isRunning = false;
    logger.info('Campaign stop requested');
    return {
      success: true,
      message: 'Campaign stop requested',
    };
  }

  /**
   * Get campaign status
   * @returns {Object} Current campaign status
   */
  getCampaignStatus() {
    if (!this.campaignState) {
      return {
        isRunning: false,
        message: 'No active campaign',
      };
    }

    return {
      isRunning: this.isRunning,
      campaignId: this.campaignState.campaignId,
      campaignName: this.campaignState.campaignName,
      totalLeads: this.campaignState.totalLeads,
      processedLeads: this.campaignState.processedLeads,
      successfulCalls: this.campaignState.successfulCalls,
      failedCalls: this.campaignState.failedCalls,
      progress: `${this.campaignState.processedLeads}/${this.campaignState.totalLeads}`,
      progressPercent: Math.round(
        (this.campaignState.processedLeads / this.campaignState.totalLeads) * 100
      ),
      startTime: this.campaignState.startTime,
      endTime: this.campaignState.endTime,
    };
  }
}

module.exports = new CampaignService();
