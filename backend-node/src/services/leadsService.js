/**
 * leadsService.js
 * Business logic for reading leads from Excel and persisting to MongoDB.
 */

const path = require('path');
const XLSX = require('xlsx');
const logger = require('../utils/logger');
const Lead = require('../mongodb/models/Lead');

/**
 * Normalise a phone number to E.164 format.
 * @param {string} raw
 * @returns {string}
 */
function normalizePhone(raw) {
  if (!raw) return '';
  let cleaned = String(raw).replace(/[\s\-\(\)]/g, '');
  if (!cleaned.startsWith('+')) {
    cleaned = '+' + cleaned;
  }
  return cleaned;
}

/**
 * Read all rows from the configured Excel file and upsert them into MongoDB.
 * Phone number is used as the unique identifier (no duplicates).
 *
 * Expected columns: Name, Mobile Number (or variations)
 *
 * @returns {Promise<{ inserted: number, skipped: number, invalid: number }>}
 */
async function importFromExcel() {
  const filePath = process.env.LEADS_FILE_PATH || '../leads/leads.xlsx';
  const resolvedPath = path.resolve(__dirname, '../../', filePath);

  logger.info(`Importing leads from Excel: ${resolvedPath}`);

  const workbook = XLSX.readFile(resolvedPath);
  const sheetName = workbook.SheetNames[0];
  const sheet = workbook.Sheets[sheetName];
  const rows = XLSX.utils.sheet_to_json(sheet, { defval: '' });

  logger.info(`Excel rows found: ${rows.length}`);

  let inserted = 0;
  let skipped = 0;
  let invalid = 0;

  for (const row of rows) {
    // Normalize column names — handle "Name", "Mobile Number", "Phone", etc.
    const name = String(row['Name'] || row['name'] || row['NAME'] || '').trim();
    const rawPhone = String(
      row['Mobile Number'] ||
      row['Mobile'] ||
      row['Phone'] ||
      row['phone'] ||
      row['PHONE'] ||
      ''
    ).trim();

    if (!name || !rawPhone) {
      invalid++;
      logger.warn(`Skipping row: missing name or phone (name="${name}" phone="${rawPhone}")`);
      continue;
    }

    const phone = normalizePhone(rawPhone);
    if (phone.length < 8) {
      invalid++;
      logger.warn(`Skipping row: invalid phone "${rawPhone}" normalised to "${phone}"`);
      continue;
    }

    try {
      const existing = await Lead.findOne({ phone });
      if (existing) {
        skipped++;
        continue;
      }

      await Lead.create({
        name,
        phone,
        status: 'pending',
      });
      inserted++;
    } catch (err) {
      invalid++;
      logger.error(`Failed to upsert lead ${name} (${phone}): ${err.message}`);
    }
  }

  logger.info(`Import complete: ${inserted} inserted, ${skipped} skipped, ${invalid} invalid`);
  return { inserted, skipped, invalid };
}

/**
 * Fetch all leads that are still in 'pending' state.
 * @returns {Promise<Lead[]>}
 */
async function getPendingLeads() {
  return Lead.find({ status: 'pending' }).sort({ createdAt: 1 });
}

module.exports = { importFromExcel, getPendingLeads };
