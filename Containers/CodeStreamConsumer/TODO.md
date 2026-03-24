# TODO: CodeStreamConsumer Modifications for Qualitas Corpus Processing

## Phase 1: MonitorTool Implementation ✓ COMPLETED
- [x] Create `src/MonitorTool.js` - Statistics collection and CSV export
- [x] Add per-stage timing tracking (read, chunkify, store, candidates, expand)
- [x] Implement global counters (total_chunks, total_candidates, total_expanded)
- [x] Add CSV logging for each stage with duration_ms

## Phase 2: CloneDetector Modifications ✓ COMPLETED
- [x] Add MonitorTool integration to track per-file metrics
- [x] Log file_name, file_size, chunks_generated, chunkify_time_ms
- [x] Track candidates_generated_now, candidate_time_ms
- [x] Track expanded_now, expand_time_ms, remaining_candidates
- [x] Add file size/token limits to skip huge files
- [x] Implement batch chunk processing (100 at a time)
- [x] Fix typo in expansion timing variable

## Phase 3: Main Application Modifications ✓ COMPLETED
- [x] Integrate MonitorTool into index.js
- [x] Add /monitor endpoint for detailed statistics
- [x] Add /progress endpoint for JSON progress
- [x] Add /export-csv for full CSV download
- [x] Add /export-sample for 100-line sample
- [x] Add graceful shutdown handlers for statistics export
- [x] Add error tracking via MonitorTool

## Phase 4: Docker/Deployment ✓ COMPLETED
- [x] Update Dockerfile with environment variables
- [x] Add docker-compose.yml for easy deployment
- [x] Configure MAX_FILE_SIZE, MAX_TOKENS, BATCH_SIZE

## Phase 5: Documentation ✓ COMPLETED
- [x] Create `CHANGES.md` highlighting all modifications
- [x] Create `ANALYSIS_REPORT.md` with analysis framework
- [x] Document answers to all analysis questions

## Remaining Tasks (Requires Running on Corpus)

### Phase 6: Data Collection
- [ ] Run cljDetector on full Qualitas Corpus once (baseline) and save logs/errors
- [ ] Check where it fails (RAM / disk / DB size / timeout) and note exact stop point
- [ ] Run experiments on 10%, 30%, 60%, 100% corpus and save `statistics.csv` each time

### Phase 7: Analysis Verification
- [ ] Verify if chunk time changes by plotting `chunkify_time_ms` vs `total_chunks`
- [ ] Verify if candidate time changes by plotting `candidate_time_ms` vs `total_candidates`
- [ ] Verify if expansion time changes by plotting `expand_time_ms` vs `total_expanded`

### Phase 8: Final Reports
- [ ] Compute average clone size from expanded clones and fill in ANALYSIS_REPORT.md
- [ ] Compute average chunks per file and fill in ANALYSIS_REPORT.md
- [ ] Export first ~100 lines as `statistics_sample_100.csv`
- [ ] Fill in all [TO BE FILLED] placeholders in ANALYSIS_REPORT.md

## Implementation Details

### MonitorTool API:
```javascript
MonitorTool.startStage(stageName)  // Start timing a stage
MonitorTool.endStage(stageName)    // End timing and log
MonitorTool.logFile(fileName, fileSize, chunks, timeMs)  // Log file processing
MonitorTool.logBatch(candidates, timeMs, totalCandidates)  // Log batch processing
MonitorTool.logExpansion(expanded, timeMs, remaining)  // Log expansion
MonitorTool.getStatistics()  // Get all collected stats
MonitorTool.exportCSV(filename)  // Export to CSV
```

### Metrics to Track:
1. Chunk Generation Time vs Total Chunks
2. Candidate Generation Time vs Total Candidates
3. Expansion Time vs Total Expanded
4. Average Clone Size
5. Average Chunks per File

## Files Created/Modified

| File | Status | Purpose |
|------|--------|---------|
| src/MonitorTool.js | NEW | Statistics collection and CSV export |
| src/CloneDetector.js | MODIFIED | Added monitoring, batch processing |
| src/index.js | MODIFIED | Added endpoints, monitoring integration |
| Dockerfile | MODIFIED | Added environment variables |
| docker-compose.yml | NEW | Docker Compose configuration |
| CHANGES.md | NEW | Detailed modification documentation |
| ANALYSIS_REPORT.md | NEW | Analysis framework and questions |
| TODO.md | NEW | This file |

## Usage After Modifications

### Run with Docker:
```bash
docker-compose up -d
```

### Access Endpoints:
- Main UI: http://localhost:3000/
- Monitoring: http://localhost:3000/monitor
- Progress: http://localhost:3000/progress?expected_total=1000
- Export CSV: http://localhost:3000/export-csv
- Export Sample: http://localhost:3000/export-sample

