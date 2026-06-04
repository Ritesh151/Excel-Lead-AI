/**
 * callController.js
 * Handles HTTP requests for Exotel-based calling workflow
 * Delegates to CampaignService for lead processing and calling
 */

const CampaignService = require('../../services/CampaignService');
const logger = require('../../utils/logger');

/**
 * POST /api/call/start
 * Initiate campaign calling using Exotel
 */
async function startCalling(req, res) {
  try {
    const { campaignName } = req.body || {};

    const result = await CampaignService.startCampaign(campaignName || 'Campaign');

    logger.info('Campaign started via API', {
      campaignId: result.campaignId,
      totalLeads: result.totalLeads,
    });

    return res.status(200).json({
      success: true,
      message: 'Campaign started successfully',
      data: result,
    });
  } catch (error) {
    logger.error('Campaign start failed', { error: error.message });
    return res.status(500).json({
      success: false,
      message: `Failed to start campaign: ${error.message}`,
    });
  }
}

/**
 * POST /api/call/stop
 * Stop the running campaign
 */
async function stopCalling(req, res) {
  try {
    const result = CampaignService.stopCampaign();

    logger.info('Campaign stop requested via API');

    return res.status(200).json({
      success: true,
      message: 'Campaign stop requested',
      data: result,
    });
  } catch (error) {
    logger.error('Campaign stop failed', { error: error.message });
    return res.status(500).json({
      success: false,
      message: `Failed to stop campaign: ${error.message}`,
    });
  }
}

/**
 * GET /api/call/status
 * Return current campaign status
 */
async function getCallStatus(req, res) {
  try {
    const status = CampaignService.getCampaignStatus();

    return res.status(200).json({
      success: true,
      data: status,
    });
  } catch (error) {
    logger.error('getCallStatus failed', { error: error.message });
    return res.status(500).json({
      success: false,
      message: 'Failed to get campaign status',
    });
  }
}

module.exports = { startCalling, stopCalling, getCallStatus };
