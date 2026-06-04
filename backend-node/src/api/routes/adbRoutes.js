/**
 * adbRoutes.js
 * Routes for ADB+Android+Kotlin GSM calling flow.
 *
 * POST /api/adb/start   — Start full ADB campaign
 * POST /api/adb/stop    — Stop campaign
 * GET  /api/adb/status  — Campaign status
 * POST /api/adb/call    — Single test call
 */

const { Router } = require('express');
const {
  startAdbCampaign,
  stopAdbCampaign,
  getAdbStatus,
  executeSingleCall,
} = require('../controllers/adbCallController');

const router = Router();

router.post('/start',  startAdbCampaign);
router.post('/stop',   stopAdbCampaign);
router.get('/status',  getAdbStatus);
router.post('/call',   executeSingleCall);

module.exports = router;
