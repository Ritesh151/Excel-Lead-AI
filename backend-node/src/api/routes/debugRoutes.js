/**
 * debugRoutes.js
 * Live diagnostics endpoints — production debugging for the ADB campaign pipeline.
 *
 * GET /api/debug/device    — ADB device state (via ai-python)
 * GET /api/debug/campaign  — Campaign queue and progress state
 * GET /api/debug/adb       — Raw ADB telephony state (via ai-python)
 * GET /api/debug/queue     — MongoDB lead queue counts
 * GET /api/debug/pipeline  — Full end-to-end pipeline health check
 */

'use strict';

const express  = require('express');
const axios    = require('axios');
const logger   = require('../../utils/logger');
const Lead     = require('../../mongodb/models/Lead');
const Campaign = require('../../mongodb/models/Campaign');
const wsServer = require('../../socket/WebSocketServer');
const AdbCampaignService = require('../../services/AdbCampaignService');

const router = express.Router();
const AI_URL = (process.env.AI_ENGINE_URL || 'http://localhost:8000').replace(/\/$/, '');
const TIMEOUT = 10_000;

// ── GET /api/debug/device ─────────────────────────────────────────────────────

router.get('/device', async (req, res) => {
  try {
    const resp = await axios.get(`${AI_URL}/api/debug/device`, { timeout: TIMEOUT });
    res.json({ source: 'ai-python', ...resp.data });
  } catch (err) {
    res.status(503).json({
      success: false,
      source: 'ai-python',
      error: err.message,
      hint: `ai-python unreachable at ${AI_URL} — is it running?`,
    });
  }
});

// ── GET /api/debug/campaign ───────────────────────────────────────────────────

router.get('/campaign', async (req, res) => {
  try {
    const [status, campaigns, pendingCount, callingCount, completedCount, failedCount] =
      await Promise.all([
        AdbCampaignService.getCampaignStatus(),
        Campaign.find({}).sort({ createdAt: -1 }).limit(5).lean(),
        Lead.countDocuments({ status: 'pending' }),
        Lead.countDocuments({ status: 'calling' }),
        Lead.countDocuments({ status: 'completed' }),
        Lead.countDocuments({ status: 'failed' }),
      ]);

    res.json({
      campaign:   status,
      recentRuns: campaigns.map((c) => ({
        id:         c.campaignId,
        name:       c.name,
        status:     c.status,
        total:      c.totalLeads,
        processed:  c.processedLeads,
        yes:        c.yesCount,
        no:         c.noCount,
        failed:     c.failedCalls,
        startTime:  c.startTime,
        endTime:    c.endTime,
      })),
      leadCounts: {
        pending:   pendingCount,
        calling:   callingCount,
        completed: completedCount,
        failed:    failedCount,
        total:     pendingCount + callingCount + completedCount + failedCount,
      },
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// ── GET /api/debug/adb ────────────────────────────────────────────────────────

router.get('/adb', async (req, res) => {
  try {
    const resp = await axios.get(`${AI_URL}/api/debug/adb`, { timeout: TIMEOUT });
    res.json({ source: 'ai-python', ...resp.data });
  } catch (err) {
    res.status(503).json({
      success: false,
      source: 'ai-python',
      error: err.message,
      hint: `ai-python unreachable at ${AI_URL}`,
    });
  }
});

// ── GET /api/debug/queue ─────────────────────────────────────────────────────

router.get('/queue', async (req, res) => {
  try {
    const [pending, calling, completed, failed, skipped] = await Promise.all([
      Lead.countDocuments({ status: 'pending' }),
      Lead.countDocuments({ status: 'calling' }),
      Lead.countDocuments({ status: 'completed' }),
      Lead.countDocuments({ status: 'failed' }),
      Lead.countDocuments({ status: 'skipped' }),
    ]);

    // Fetch next 5 pending leads for preview
    const nextLeads = await Lead.find({ status: 'pending' })
      .sort({ createdAt: 1 })
      .limit(5)
      .select('name phone createdAt')
      .lean();

    // Fetch last 5 calls with results
    const recentCalls = await Lead.find({ status: { $in: ['completed', 'failed'] } })
      .sort({ calledAt: -1 })
      .limit(5)
      .select('name phone status response transcription calledAt errorMessage')
      .lean();

    res.json({
      counts: { pending, calling, completed, failed, skipped,
                total: pending + calling + completed + failed + skipped },
      campaignRunning: AdbCampaignService._isRunning,
      currentPhone:    AdbCampaignService._state?.currentPhone ?? null,
      nextLeads:       nextLeads.map((l) => ({ name: l.name, phone: l.phone })),
      recentCalls:     recentCalls.map((l) => ({
        name:          l.name,
        phone:         l.phone,
        status:        l.status,
        response:      l.response,
        transcription: (l.transcription || '').slice(0, 100),
        calledAt:      l.calledAt,
        error:         l.errorMessage,
      })),
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// ── GET /api/debug/pipeline ───────────────────────────────────────────────────

router.get('/pipeline', async (req, res) => {
  const checks = {
    timestamp:     new Date().toISOString(),
    mongodb:       { ok: false, error: null },
    aiPython:      { ok: false, url: AI_URL, error: null },
    adbDevice:     { ok: false, devices: [], error: null },
    websocket:     { clients: wsServer.clientCount, androidConnected: wsServer.androidConnected },
    leadsFile:     { ok: false, path: process.env.LEADS_FILE_PATH || '../leads/leads.xlsx', error: null },
    queue:         { pending: 0, calling: 0 },
    overall:       'FAIL',
  };

  // MongoDB
  try {
    await Lead.findOne().lean();
    checks.mongodb.ok = true;
  } catch (err) {
    checks.mongodb.error = err.message;
  }

  // ai-python
  try {
    const resp = await axios.get(`${AI_URL}/health`, { timeout: 5000 });
    checks.aiPython.ok = resp.status === 200;
  } catch (err) {
    checks.aiPython.error = err.message;
  }

  // ADB device (via ai-python)
  try {
    const resp = await axios.get(`${AI_URL}/api/debug/device`, { timeout: 8000 });
    const onlineDevices = (resp.data.devices || []).filter((d) => d.state === 'device');
    checks.adbDevice.ok      = onlineDevices.length > 0;
    checks.adbDevice.devices = onlineDevices;
    if (!checks.adbDevice.ok) {
      checks.adbDevice.error = `No online ADB device found. Devices: ${JSON.stringify(resp.data.devices)}`;
    }
  } catch (err) {
    checks.adbDevice.error = err.message;
  }

  // Leads file
  const fs = require('fs');
  const leadsPath = require('path').resolve(
    process.env.LEADS_FILE_PATH || require('path').join(__dirname, '../../../../leads/leads.xlsx')
  );
  checks.leadsFile.path = leadsPath;
  checks.leadsFile.ok   = fs.existsSync(leadsPath);
  if (!checks.leadsFile.ok) {
    checks.leadsFile.error = `File not found: ${leadsPath}`;
  }

  // Queue counts
  try {
    checks.queue.pending = await Lead.countDocuments({ status: 'pending' });
    checks.queue.calling = await Lead.countDocuments({ status: 'calling' });
  } catch (_) {}

  // Overall
  const critical = checks.mongodb.ok && checks.aiPython.ok && checks.adbDevice.ok;
  checks.overall = critical ? 'OK' : (checks.mongodb.ok && checks.aiPython.ok ? 'PARTIAL' : 'FAIL');

  const httpStatus = checks.overall === 'OK' ? 200 : 503;
  res.status(httpStatus).json(checks);
});

module.exports = router;
