// MonitorTool: Monitors the clone detection database
// Tracks: files, chunks, candidates, clones counts and processing statistics
const http = require('http');
const fs = require('fs');
const path = require('path');
const { MongoClient } = require('mongodb');

const PORT = process.env.PORT || 3001;
const DBHOST = process.env.DBHOST || 'dbstorage';
const DBNAME = 'cloneDetector';
const STATS_FILE = process.env.STATS_SUMMARY || path.join('/app', 'logs', 'summary.json');

// Statistics tracking
let prevStats = null;
let startTime = Date.now();

async function getCounts(db) {
  const files = await db.collection('files').countDocuments();
  const chunks = await db.collection('chunks').countDocuments();
  const candidates = await db.collection('candidates').countDocuments();
  const clones = await db.collection('clones').countDocuments();
  const updates = await db.collection('statusUpdates').countDocuments();
  return { files, chunks, candidates, clones, updates };
}

async function getStatistics(db) {
  const stats = await db.collection('statistics').find({}).toArray();
  return stats;
}

async function getLatestUpdates(db, limit = 20) {
  const updates = await db.collection('statusUpdates')
    .find({})
    .sort({ timestamp: -1 })
    .limit(limit)
    .toArray();
  return updates;
}

async function calculateRates(current, prev) {
  if (!prev) return current;
  
  const timeDiff = current.timestamp - prev.timestamp;
  if (timeDiff === 0) return current;
  
  const filesRate = (current.files - prev.files) / (timeDiff / 1000);
  const chunksRate = (current.chunks - prev.chunks) / (timeDiff / 1000);
  const candidatesRate = (current.candidates - prev.candidates) / (timeDiff / 1000);
  const clonesRate = (current.clones - prev.clones) / (timeDiff / 1000);
  
  const timePerFile = filesRate > 0 ? 1000 / filesRate : 0;
  const timePerChunk = chunksRate > 0 ? 1000 / chunksRate : 0;
  
  return {
    ...current,
    rates: {
      filesPerSec: filesRate.toFixed(2),
      chunksPerSec: chunksRate.toFixed(2),
      candidatesPerSec: candidatesRate.toFixed(2),
      clonesPerSec: clonesRate.toFixed(2)
    },
    timePerUnit: {
      fileMs: timePerFile.toFixed(6),
      chunkMs: timePerChunk.toFixed(6)
    }
  };
}

async function monitor() {
  const uri = `mongodb://${DBHOST}:27017`;
  const client = new MongoClient(uri, { serverSelectionTimeoutMS: 5000 });
  
  try {
    await client.connect();
    const db = client.db(DBNAME);
    
    const counts = await getCounts(db);
    const timestamp = Date.now();
    const stats = await getStatistics(db);
    const latestUpdates = await getLatestUpdates(db);
    
    const current = { ...counts, timestamp };
    const withRates = await calculateRates(current, prevStats);
    prevStats = current;
    
    const summary = {
      generatedAt: new Date().toISOString(),
      uptime: Math.floor((Date.now() - startTime) / 1000),
      counts: {
        files: counts.files,
        chunks: counts.chunks,
        candidates: counts.candidates,
        clones: counts.clones,
        statusUpdates: counts.updates
      },
      withRates,
      statistics: stats.slice(-100), // Last 100 stats
      latestUpdates
    };
    
    // Ensure directory exists
    const dir = path.dirname(STATS_FILE);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(STATS_FILE, JSON.stringify(summary, null, 2));
    
    await client.close();
    return summary;
  } catch (err) {
    console.error('Monitor error:', err.message);
    // Return cached or empty summary on error
    try {
      if (fs.existsSync(STATS_FILE)) {
        return JSON.parse(fs.readFileSync(STATS_FILE));
      }
    } catch {}
    return { error: err.message };
  }
}

// Simple HTML dashboard
function generateDashboard(summary) {
  const counts = summary.counts || {};
  const rates = summary.withRates?.rates || {};
  const timePerUnit = summary.withRates?.timePerUnit || {};
  const latestUpdates = summary.latestUpdates || [];
  const stats = summary.statistics || [];
  
  // Calculate phase statistics
  const phaseStats = {};
  if (stats.length > 0) {
    stats.forEach(s => {
      const phase = s.phase || 'unknown';
      if (!phaseStats[phase]) {
        phaseStats[phase] = { count: 0, totalDuration: 0, durations: [] };
      }
      phaseStats[phase].count++;
      if (s.durationMs) {
        phaseStats[phase].totalDuration += s.durationMs;
        phaseStats[phase].durations.push(s.durationMs);
      }
    });
  }
  
  let phaseHtml = '';
  for (const [phase, data] of Object.entries(phaseStats)) {
    const avg = data.durations.length > 0 
      ? (data.totalDuration / data.durations.length).toFixed(2) 
      : 'N/A';
    phaseHtml += `
      <div class="phase-card">
        <h3>${phase}</h3>
        <p>Count: ${data.count}</p>
        <p>Avg Duration: ${avg}ms</p>
      </div>
    `;
  }
  
  const updatesHtml = latestUpdates.slice(0, 15).map(u => `
    <tr>
      <td>${u.timestamp || 'N/A'}</td>
      <td>${u.message || ''}</td>
    </tr>
  `).join('');
  
  return `<!DOCTYPE html>
<html>
<head>
  <title>Clone Detection Monitor</title>
  <style>
    body { font-family: monospace; background: #1a1a2e; color: #eee; padding: 20px; }
    .header { background: #16213e; padding: 15px; margin-bottom: 20px; border-radius: 8px; }
    .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; margin-bottom: 20px; }
    .stat-card { background: #16213e; padding: 15px; border-radius: 8px; border: 1px solid #0f3460; }
    .stat-card h3 { color: #e94560; margin-bottom: 10px; }
    .stat-value { font-size: 1.8em; color: #00d9ff; }
    .stat-rate { color: #00ff88; font-size: 0.9em; }
    .section { background: #16213e; padding: 15px; border-radius: 8px; margin-bottom: 15px; }
    .section h2 { color: #e94560; margin-bottom: 10px; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 8px; text-align: left; border-bottom: 1px solid #0f3460; }
    th { color: #00d9ff; }
    .phase-card { background: #0f3460; padding: 10px; margin: 5px; border-radius: 5px; }
    .phase-card h3 { color: #00d9ff; margin: 0 0 5px 0; }
    .phase-card p { margin: 2px 0; font-size: 0.9em; }
    .refresh { margin-bottom: 15px; }
    .refresh button { background: #e94560; color: white; border: none; padding: 10px 20px; cursor: pointer; border-radius: 5px; }
  </style>
</head>
<body>
  <div class="header">
    <h1>🔍 Clone Detection Monitor</h1>
    <p>Uptime: ${summary.uptime || 0}s | Generated: ${summary.generatedAt || 'N/A'}</p>
  </div>
  
  <div class="refresh">
    <button onclick="location.reload()">🔄 Refresh</button>
  </div>
  
  <div class="stats-grid">
    <div class="stat-card">
      <h3>📁 Files</h3>
      <div class="stat-value">${counts.files || 0}</div>
      <div class="stat-rate">Rate: ${rates.filesPerSec || 0}/sec</div>
    </div>
    <div class="stat-card">
      <h3>📦 Chunks</h3>
      <div class="stat-value">${counts.chunks || 0}</div>
      <div class="stat-rate">Rate: ${rates.chunksPerSec || 0}/sec</div>
    </div>
    <div class="stat-card">
      <h3>🔍 Candidates</h3>
      <div class="stat-value">${counts.candidates || 0}</div>
      <div class="stat-rate">Rate: ${rates.candidatesPerSec || 0}/sec</div>
    </div>
    <div class="stat-card">
      <h3>🧬 Clones</h3>
      <div class="stat-value">${counts.clones || 0}</div>
      <div class="stat-rate">Rate: ${rates.clonesPerSec || 0}/sec</div>
    </div>
  </div>
  
  <div class="stats-grid">
    <div class="stat-card">
      <h3>⏱️ Processing Time</h3>
      <p>Time per file: ${timePerUnit.fileMs || 0}ms</p>
      <p>Time per chunk: ${timePerUnit.chunkMs || 0}ms</p>
    </div>
    <div class="stat-card">
      <h3>📋 Status Updates</h3>
      <div class="stat-value">${counts.statusUpdates || 0}</div>
    </div>
  </div>
  
  ${phaseHtml ? `
  <div class="section">
    <h2>📊 Phase Statistics</h2>
    <div style="display: flex; flex-wrap: wrap;">${phaseHtml}</div>
  </div>
  ` : ''}
  
  <div class="section">
    <h2>📋 Recent Status Updates</h2>
    <table>
      <thead><tr><th>Timestamp</th><th>Message</th></tr></thead>
      <tbody>${updatesHtml || '<tr><td colspan="2">No updates yet</td></tr>'}</tbody>
    </table>
  </div>
</body>
</html>`;
}

// HTTP Server
const server = http.createServer(async (req, res) => {
  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  
  if (req.url === '/' || req.url === '/index.html') {
    // Get fresh data and render dashboard
    const summary = await monitor();
    const html = generateDashboard(summary);
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(html);
  } else if (req.url === '/api/summary') {
    // JSON API
    const summary = await monitor();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(summary, null, 2));
  } else if (req.url === '/api/stats') {
    // Raw statistics from database
    const uri = `mongodb://${DBHOST}:27017`;
    const client = new MongoClient(uri, { serverSelectionTimeoutMS: 5000 });
    try {
      await client.connect();
      const db = client.db(DBNAME);
      const stats = await db.collection('statistics').find({}).toArray();
      await client.close();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(stats, null, 2));
    } catch (err) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: err.message }));
    }
  } else if (req.url.endsWith('.json')) {
    // Serve static JSON files from logs directory
    const filePath = path.join('/app', 'logs', req.url);
    if (filePath.startsWith('/app/logs/') && fs.existsSync(filePath)) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(fs.readFileSync(filePath));
    } else {
      res.writeHead(404);
      res.end('Not found');
    }
  } else {
    res.writeHead(404);
    res.end('Not found. Available: /, /api/summary, /api/stats, *.json');
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`MonitorTool running at http://localhost:${PORT}`);
  console.log(`API endpoints:`);
  console.log(`  - http://localhost:${PORT}/         (Dashboard)`);
  console.log(`  - http://localhost:${PORT}/api/summary (JSON)`);
  console.log(`  - http://localhost:${PORT}/api/stats   (Raw statistics)`);
  console.log(`  - http://localhost:${PORT}/summary.json (Cached summary)`);
  
  // Initial monitoring
  monitor();
  
  // Poll every 30 seconds
  setInterval(monitor, 30000);
});

