ter name="content">#!/usr/bin/env node
// Read existing MongoDB data and generate fresh statistics
const { MongoClient } = require('mongodb');
const fs = require('fs');

const DBHOST = process.env.DBHOST || 'localhost';
const DBNAME = 'cloneDetector';
const OUTPUT_FILE = 'logs/realtime-summary.json';

async function main() {
  console.log('Connecting to MongoDB at', DBHOST + '...');
  const uri = `mongodb://${DBHOST}:27017`;
  const client = new MongoClient(uri, { serverSelectionTimeoutMS: 10000 });
  
  try {
    await client.connect();
    console.log('Connected successfully!');
    const db = client.db(DBNAME);
    
    // Get counts
    const files = await db.collection('files').countDocuments();
    const chunks = await db.collection('chunks').countDocuments();
    const candidates = await db.collection('candidates').countDocuments();
    const clones = await db.collection('clones').countDocuments();
    const statusUpdates = await db.collection('statusUpdates').countDocuments();
    const statistics = await db.collection('statistics').find({}).toArray();
    const latestUpdates = await db.collection('statusUpdates')
      .find({})
      .sort({ timestamp: -1 })
      .limit(20)
      .toArray();
    
    // Calculate per-phase statistics
    const perPhase = {};
    statistics.forEach(stat => {
      const phase = stat.phase || 'unknown';
      if (!perPhase[phase]) {
        perPhase[phase] = { count: 0, totalDuration: 0, durations: [] };
      }
      perPhase[phase].count++;
      if (stat.durationMs) {
        perPhase[phase].totalDuration += stat.durationMs;
        perPhase[phase].durations.push(stat.durationMs);
      }
    });
    
    const perPhaseArray = Object.entries(perPhase).map(([phase, data]) => ({
      _id: phase,
      count: data.count,
      avgDurationMs: data.durations.length > 0 
        ? (data.totalDuration / data.durations.length).toFixed(2) 
        : 0,
      totalDurationMs: data.totalDuration
    }));
    
    const summary = {
      generatedAt: new Date().toISOString(),
      counts: { files, chunks, candidates, clones, statusUpdates },
      perPhase: perPhaseArray,
      latestUpdates,
      message: 'Data read from existing MongoDB database'
    };
    
    // Ensure directory exists
    fs.mkdirSync('logs', { recursive: true });
    fs.writeFileSync(OUTPUT_FILE, JSON.stringify(summary, null, 2));
    
    console.log('\n========== CURRENT STATISTICS ==========');
    console.log(`Files:     ${files}`);
    console.log(`Chunks:    ${chunks}`);
    console.log(`Candidates: ${candidates}`);
    console.log(`Clones:    ${clones}`);
    console.log(`Status Updates: ${statusUpdates}`);
    console.log('\nPer Phase Statistics:');
    perPhaseArray.forEach(p => {
      console.log(`  ${p._id}: ${p.count} runs, avg ${p.avgDurationMs}ms`);
    });
    console.log(`\nFull report saved to: ${OUTPUT_FILE}`);
    
    await client.close();
    process.exit(0);
  } catch (err) {
    console.error('Error:', err.message);
    process.exit(1);
  }
}

main();
