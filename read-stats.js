// Export first 100 statistics lines as CSV for assignment appendix
// Usage: node read-stats.js
const fs = require('fs');
const path = require('path');

const STATS_FILE = process.env.STATS_LOG || 'logs/stats.ndjson';
const OUTPUT_CSV = 'logs/statistics_sample_100.csv';

function ednToObject(line) {
  try {
    // Simple EDN parser for our stats format (timestamp phase durationMs etc)
    const match = line.match(/(\{.*\})/);
    if (match) {
      return eval('(' + match[1] + ')'); // DANGER: eval for demo - use proper parser in prod
    }
  } catch {}
  return null;
}

function objectToCsvRow(obj) {
  if (!obj) return '';
  const headers = ['timestamp', 'phase', 'durationMs', 'chunks', 'files', 'candidates', 'clones'];
  return headers.map(h => obj[h] || '').join(',') + '\n';
}

try {
  const statsData = fs.readFileSync(STATS_FILE, 'utf8');
  const lines = statsData.trim().split('\n').slice(0, 100);
  
  let csv = 'timestamp,phase,durationMs,chunks,files,candidates,clones\n';
  lines.forEach(line => {
    const obj = ednToObject(line);
    if (obj) csv += objectToCsvRow(obj);
  });
  
  fs.writeFileSync(OUTPUT_CSV, csv);
  console.log(`Exported ${lines.length} stats to ${OUTPUT_CSV}`);
} catch (err) {
  console.error('Error:', err.message);
  console.log('Run cljdetector first to generate stats.ndjson');
}

