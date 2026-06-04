/**
 * connection.js
 * Establishes and exports the Mongoose connection to MongoDB.
 * Call connect() once at application startup.
 */

const mongoose = require('mongoose');
const logger = require('../utils/logger');

/**
 * Connect to MongoDB using MONGO_URI from environment.
 * Exits the process on failure so the app never starts in a broken state.
 */
async function connect() {
  const uri = process.env.MONGO_URI || process.env.MONGODB_URI;

  if (!uri) {
    logger.error('MONGO_URI (or MONGODB_URI) is not defined in environment variables');
    process.exit(1);
  }

  try {
    await mongoose.connect(uri, {
      serverSelectionTimeoutMS: 5000,
      maxPoolSize: parseInt(process.env.MONGO_MAX_POOL_SIZE || '10', 10),
      retryWrites: true,
    });

    logger.info(`MongoDB connected: ${mongoose.connection.host}`);
  } catch (error) {
    logger.error('MongoDB connection failed', { error: error.message });
    process.exit(1);
  }

  // Log disconnection events without crashing — let the app handle reconnects
  mongoose.connection.on('disconnected', () => {
    logger.warn('MongoDB disconnected');
  });

  mongoose.connection.on('error', (err) => {
    logger.error('MongoDB runtime error', { error: err.message });
  });
}

/**
 * Gracefully close the MongoDB connection.
 * Should be called during process shutdown.
 */
async function disconnect() {
  await mongoose.connection.close();
  logger.info('MongoDB connection closed');
}

module.exports = { connect, disconnect };
