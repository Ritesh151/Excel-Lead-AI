/**
 * callRoutes.js
 * Express router for /api/call endpoints.
 */

const { Router } = require('express');
const { startCalling, stopCalling, getCallStatus } = require('../controllers/callController');

const router = Router();

// POST /api/call/start    — begin sequential calling loop
router.post('/start', startCalling);

// POST /api/call/stop     — request graceful stop
router.post('/stop', stopCalling);

// GET  /api/call/status   — current session state
router.get('/status', getCallStatus);

module.exports = router;
