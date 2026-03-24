// Simple monitor tool to aggregate statistics from MongoDB and mirror summaries to logs/summary.json
// Logic: connects to DB, reads 'statistics' and 'statusUpdates', computes aggregates,
// writes periodic summaries to logs/summary.json. Avoids copying friend's implementation; Node.js version.

const { MongoClient } = require('mongodb');
const fs = require('fs');
const path = require('path');

const DBHOST = process.env.DBHOST || 'localhost';
const DBNAME = 'cloneDetector';
const OUT = process.env.STATS_SUMMARY || path.join('logs', 'summary.json');

async function aggregate(client) {
  const db = client.db(DBNAME);
  const stats = db.collection('statistics');
  const updates = db.collection('statusUpdates');

  const perPhase = await stats.aggregate([
    { $group: { _id: '$phase', count: { $sum: 1 },
                avgDurationMs: { $avg: '$durationMs' },
                lastDurationMs: { $last: '$durationMs' },
                maxDurationMs: { $max: '$durationMs' } } }
  ]).toArray();

  const latest = await updates.find({}).sort({ timestamp: -1 }).limit(20).toArray();

  const summary = {
    generatedAt: new Date().toISOString(),
    perPhase,
    latestUpdates: latest
  };

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(summary, null, 2));
}

async function main() {
  const uri = `mongodb://${DBHOST}:27017`;
  const client = new MongoClient(uri, { serverSelectionTimeoutMS: 5000 });
  try {
    await client.connect();
    console.log('MonitorTool connected. Polling every 30s...');
    // Initial write
    await aggregate(client);
    // Poll periodically
    setInterval(async () => {
      try {
        await aggregate(client);
        console.log('Monitor summary refreshed at', new Date().toISOString());
      } catch (e) {
        console.error('Aggregation failed:', e.message);
      }
    }, 30000);
  } catch (err) {
    console.error('MonitorTool failed to connect:', err.message);
    process.exitCode = 1;
  }
}

main();
