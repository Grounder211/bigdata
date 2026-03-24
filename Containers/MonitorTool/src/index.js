const express = require('express');
const { MongoClient } = require('mongodb');

const app = express();
const port = 3001;
const mongoUrl = `mongodb://${process.env.DBHOST || 'dbstorage'}:27017`;
const dbName = 'cloneDetector';
const collections = ['files', 'chunks', 'candidates', 'clones'];

let statsHistory = [];
let lastCounts = { files: 0, chunks: 0, candidates: 0, clones: 0 };
let lastTime = Date.now();

async function getCounts(client) {
  const db = client.db(dbName);
  const counts = {};
  for (const coll of collections) {
    // Retry/backoff in case collection is dropped during clear
    let attempts = 0;
    while (attempts < 3) {
      try {
        counts[coll] = await db.collection(coll).countDocuments();
        break;
      } catch (err) {
        attempts++;
        await new Promise(r => setTimeout(r, 1000 * attempts));
      }
    }
    if (counts[coll] === undefined) counts[coll] = 0;
  }
  return counts;
}

async function getStatusUpdates(client, since) {
  const db = client.db(dbName);
  const updates = await db.collection('statusUpdates').find({ timestamp: { $gt: since } }).toArray();
  return updates;
}

function calculateStats(currentCounts, previousCounts, timeDiff) {
  const stats = {};
  for (const coll of collections) {
    const delta = currentCounts[coll] - previousCounts[coll];
    stats[coll] = {
      count: currentCounts[coll],
      rate: delta / (timeDiff / 1000), // per second
      timePerUnit: delta > 0 ? (timeDiff / 1000) / delta : 0
    };
  }
  return stats;
}

async function monitor() {
  const client = new MongoClient(mongoUrl);
  try {
    await client.connect();
    console.log('Connected to MongoDB');

    const fs = require('fs');
  const csvPath = '/tmp/logs.csv';
    // Ensure header
    if (!fs.existsSync(csvPath)) {
      fs.writeFileSync(csvPath, 'timestamp,files,chunks,candidates,clones,filesRate,chunksRate,candidatesRate,clonesRate\n');
    }

    setInterval(async () => {
      const currentTime = Date.now();
      const timeDiff = currentTime - lastTime;

      const counts = await getCounts(client);
      const stats = calculateStats(counts, lastCounts, timeDiff);

      const updates = await getStatusUpdates(client, new Date(lastTime).toISOString());

      statsHistory.push({
        timestamp: new Date(currentTime).toISOString(),
        stats,
        updates: updates.map(u => ({ timestamp: u.timestamp, message: u.message }))
      });

      // Keep only last 100 entries
      if (statsHistory.length > 100) {
        statsHistory.shift();
      }

      lastCounts = { ...counts };
      lastTime = currentTime;

      console.log(`[${new Date().toISOString()}] Stats:`, stats);
      // Append to CSV
      const line = [
        new Date(currentTime).toISOString(),
        stats.files.count, stats.chunks.count, stats.candidates.count, stats.clones.count,
        stats.files.rate, stats.chunks.rate, stats.candidates.rate, stats.clones.rate
      ].join(',') + '\n';
      fs.appendFile(csvPath, line, () => {});
    }, 10000); // Poll every 10 seconds

  } catch (err) {
    console.error('Error connecting to MongoDB:', err);
  }
}

app.get('/', (req, res) => {
  res.send(`
    <!DOCTYPE html>
    <html>
    <head>
      <title>Monitor Tool</title>
      <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    </head>
    <body>
      <h1>Big Data Analytics Monitor</h1>
      <div id="currentStats"></div>
      <h2>Status Updates</h2>
      <div id="statusUpdates"></div>
      <h2>Processing Time Trends</h2>
      <canvas id="timeChart" width="400" height="200"></canvas>
      <script>
        async function updateData() {
          const response = await fetch('/data');
          const data = await response.json();
          document.getElementById('currentStats').innerHTML = '<pre>' + JSON.stringify(data.current, null, 2) + '</pre>';
          document.getElementById('statusUpdates').innerHTML = '<pre>' + JSON.stringify(data.updates, null, 2) + '</pre>';

          const ctx = document.getElementById('timeChart').getContext('2d');
          new Chart(ctx, {
            type: 'line',
            data: {
              labels: data.history.map(h => h.timestamp),
              datasets: [
                {
                  label: 'Time per Chunk (s)',
                  data: data.history.map(h => h.stats.chunks.timePerUnit),
                  borderColor: 'blue'
                },
                {
                  label: 'Time per Clone (s)',
                  data: data.history.map(h => h.stats.clones.timePerUnit),
                  borderColor: 'red'
                }
              ]
            }
          });
        }
        updateData();
        setInterval(updateData, 10000);
      </script>
    </body>
    </html>
  `);
});

app.get('/data', (req, res) => {
  const latest = statsHistory[statsHistory.length - 1] || { stats: {}, updates: [] };
  res.json({
    current: latest.stats,
    updates: latest.updates,
    history: statsHistory
  });
});

app.listen(port, () => {
  console.log(`Monitor Tool listening at http://localhost:${port}`);
});

monitor();
