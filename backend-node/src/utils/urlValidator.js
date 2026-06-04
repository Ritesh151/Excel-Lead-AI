/**
 * urlValidator.js
 * Public URL and hostname validation utilities.
 */

const dns = require('dns').promises;
const { URL } = require('url');

const PRIVATE_IP_RANGES = [
  { start: '10.0.0.0', end: '10.255.255.255' },
  { start: '172.16.0.0', end: '172.31.255.255' },
  { start: '192.168.0.0', end: '192.168.255.255' },
  { start: '127.0.0.0', end: '127.255.255.255' },
  { start: '169.254.0.0', end: '169.254.255.255' },
  { start: '::1', end: '::1' },
];

function ipToLong(ip) {
  if (ip.includes(':')) {
    return null;
  }

  return ip.split('.').reduce((acc, octet) => (acc << 8) + Number(octet), 0) >>> 0;
}

function isPrivateIp(ip) {
  if (ip.includes(':')) {
    return ip === '::1' || ip.startsWith('fc') || ip.startsWith('fd');
  }

  const numeric = ipToLong(ip);
  if (numeric === null) {
    return false;
  }

  return PRIVATE_IP_RANGES.some((range) => {
    const start = ipToLong(range.start);
    const end = ipToLong(range.end);
    return numeric >= start && numeric <= end;
  });
}

async function resolveHost(hostname) {
  try {
    return await dns.lookup(hostname, { all: true });
  } catch (error) {
    return [];
  }
}

function normalizeUrl(value) {
  try {
    return new URL(value);
  } catch (error) {
    throw new Error('Invalid URL format');
  }
}

async function validatePublicUrl(value) {
  const report = {
    raw: value,
    valid: false,
    secure: false,
    errors: [],
    warnings: [],
    hostname: null,
    protocol: null,
    addresses: [],
  };

  let url;
  try {
    url = normalizeUrl(value);
  } catch (error) {
    report.errors.push(error.message);
    return report;
  }

  report.protocol = url.protocol;
  report.hostname = url.hostname;

  if (url.protocol !== 'https:') {
    report.errors.push('URL must use HTTPS');
  } else {
    report.secure = true;
  }

  if (['localhost', '127.0.0.1', '::1'].includes(url.hostname)) {
    report.errors.push('URL must not be localhost or loopback');
  }

  const addresses = await resolveHost(url.hostname);
  report.addresses = addresses.map((entry) => entry.address);

  if (addresses.length === 0) {
    report.errors.push('Hostname could not be resolved to a public IP');
    return report;
  }

  for (const entry of addresses) {
    if (isPrivateIp(entry.address)) {
      report.errors.push(`Resolved address ${entry.address} is private or loopback`);
    }
  }

  report.valid = report.errors.length === 0;
  return report;
}

module.exports = {
  validatePublicUrl,
  isPrivateIp,
  normalizeUrl,
};
