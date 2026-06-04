/**
 * leadsController.js
 * Handles HTTP requests related to lead management.
 */

const Lead = require('../../mongodb/models/Lead');
const leadsService = require('../../services/leadsService');
const logger = require('../../utils/logger');

/**
 * GET /api/leads
 * Return all leads with their current status.
 */
async function getAllLeads(req, res) {
  try {
    const leads = await Lead.find().sort({ createdAt: 1 }).lean();
    return res.status(200).json({ success: true, count: leads.length, data: leads });
  } catch (error) {
    logger.error('getAllLeads failed', { error: error.message });
    return res.status(500).json({ success: false, message: 'Failed to fetch leads' });
  }
}

/**
 * GET /api/leads/:id
 * Return a single lead by MongoDB _id.
 */
async function getLeadById(req, res) {
  try {
    const lead = await Lead.findById(req.params.id).lean();
    if (!lead) {
      return res.status(404).json({ success: false, message: 'Lead not found' });
    }
    return res.status(200).json({ success: true, data: lead });
  } catch (error) {
    logger.error('getLeadById failed', { id: req.params.id, error: error.message });
    return res.status(500).json({ success: false, message: 'Failed to fetch lead' });
  }
}

/**
 * POST /api/leads/import
 * Trigger Excel import — reads LEADS_FILE_PATH and upserts all rows.
 */
async function importLeads(req, res) {
  try {
    const result = await leadsService.importFromExcel();
    logger.info('Lead import completed', result);
    return res.status(200).json({
      success: true,
      message: `Import complete: ${result.inserted} inserted, ${result.skipped} skipped, ${result.invalid} invalid`,
      data: result,
    });
  } catch (error) {
    logger.error('importLeads failed', { error: error.message });
    return res.status(500).json({ success: false, message: `Import failed: ${error.message}` });
  }
}

module.exports = { getAllLeads, getLeadById, importLeads };
