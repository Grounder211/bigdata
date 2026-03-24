#!/bin/zsh

echo "🧹 Cleaning previous run..."
echo "Building cljdetector image..."
cd Containers/cljdetector && docker build -t cljdetector .
cd ../..
docker compose -f all-at-once.yaml down --volumes --remove-orphans
docker volume prune -f

echo "🚀 Starting DB + Monitor..."
docker compose -f all-at-once.yaml up -d dbstorage monitor-dashboard

echo "⏳ Waiting for services..."
sleep 15

echo "🔍 Starting Clone Detection (streaming)..."
docker compose -f all-at-once.yaml up clone-detector --no-deps

echo "📊 Open monitor: http://localhost:3001"
open http://localhost:3001

echo "📋 Tail logs in new terminal: tail -f logs/*.log"

echo "📈 Export stats: node read-stats.js"
echo "✅ Ready for REPORT.md generation!"
