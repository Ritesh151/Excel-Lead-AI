/**
 * leadsRoutes.js
 * Express router for /api/leads endpoints.
 */

const { Router } = require('express');
const { getAllLeads, getLeadById, importLeads } = require('../controllers/leadsController');

const router = Router();

// GET  /api/leads           — list all leads
router.get('/', getAllLeads);

// GET  /api/leads/:id       — single lead by ID
router.get('/:id', getLeadById);

// POST /api/leads/import    — trigger Excel import
router.post('/import', importLeads);

module.exports = router;
