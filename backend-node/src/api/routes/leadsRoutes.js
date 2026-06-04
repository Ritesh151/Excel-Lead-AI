/**
 * leadsRoutes.js
 * Express router for /api/leads endpoints.
 */

const { Router } = require('express');
const { getAllLeads, getLeadById, importLeads } = require('../controllers/leadsController');

const router = Router();

// GET  /api/leads           — list all leads
router.get('/', getAllLeads);

// POST /api/leads/import    — trigger Excel import (before /:id)
router.post('/import', importLeads);

// GET  /api/leads/:id       — single lead by ID
router.get('/:id', getLeadById);

module.exports = router;
