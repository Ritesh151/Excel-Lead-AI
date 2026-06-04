/**
 * NgrokService.js
 * Detects and validates an active ngrok HTTP tunnel for public HTTPS callback URLs.
 */

const axios = require('axios');
const logger = require('../utils/logger');
const { validatePublicUrl } = require('../utils/urlValidator');

class NgrokService {
  constructor() {
    this.apiUrl = process.env.NGROK_API_URL || 'http://127.0.0.1:4040';
    this.requiredProto = 'https';
  }

  async getTunnels() {
    try {
      const response = await axios.get(`${this.apiUrl}/api/tunnels`, {
        timeout: 5000,
      });
      return Array.isArray(response.data.tunnels) ? response.data.tunnels : [];
    } catch (error) {
      logger.warn('Unable to fetch ngrok tunnels from local API', {
        apiUrl: this.apiUrl,
        error: error.message,
      });
      return [];
    }
  }

  async getActivePublicUrl() {
    const tunnels = await this.getTunnels();
    const httpsTunnel = tunnels.find((tunnel) => tunnel.proto === this.requiredProto && tunnel.public_url.startsWith('https://'));

    if (!httpsTunnel) {
      logger.warn('No active HTTPS ngrok tunnel found', { tunnels: tunnels.map((t) => ({ name: t.name, proto: t.proto, public_url: t.public_url })) });
      return null;
    }

    const validation = await validatePublicUrl(httpsTunnel.public_url);
    if (!validation.valid) {
      logger.warn('Detected ngrok tunnel is not publicly valid', { publicUrl: httpsTunnel.public_url, validation });
      return null;
    }

    logger.info('Detected active ngrok public URL', { publicUrl: httpsTunnel.public_url });
    return httpsTunnel.public_url;
  }

  async ensureActiveTunnel() {
    const publicUrl = await this.getActivePublicUrl();
    if (publicUrl) {
      return {
        active: true,
        publicUrl,
      };
    }

    return {
      active: false,
      publicUrl: null,
    };
  }
}

module.exports = new NgrokService();
