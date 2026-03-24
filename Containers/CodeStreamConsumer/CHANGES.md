# CHANGES.md - CodeStreamConsumer Modifications for Qualitas Corpus Analysis

This document details all modifications made to the CodeStreamConsumer codebase to support processing the Qualitas Corpus and collecting comprehensive statistics.

## Overview

The original CodeStreamConsumer was designed for basic clone detection but had several limitations when processing large corpora like Qualitas Corpus:
1. No performance monitoring or statistics collection
2. No progress tracking or prediction capabilities
3. No memory management for large-scale processing
4. No CSV export for data analysis

## Modifications Summary

### 1. MonitorTool.js (NEW FILE)
**Purpose**: Comprehensive statistics collection and monitoring for analysis

**Key Features**:
- Singleton pattern for global access across the application
- High-precision timing using `process.hrtime.bigint()`
- Per-file statistics (file name, size, chunks, timing)
- Per-batch statistics (candidates generated, processing time)
- Per-expansion statistics (clones expanded, remaining candidates)
- Stage-level timing (preprocess, transform, matchDetect, etc.)
- CSV export with full and sample (100 lines) options
- Error/warning logging
- Progress prediction capabilities

**API Methods**:
```javascript
MonitorTool.getInstance()          // Get singleton instance
startStage(stageName)              // Start timing a stage
endStage(additionalData)           // End timing and log
logFile(fileName, size, chunks, time, ...)  // Log file processing
logBatch(candidates, time, files)  // Log batch processing
logExpansion(expanded, time, remaining)  // Log expansion
logCloneSize(size)                 // Track clone sizes
exportCSV(filename)                // Export to CSV
exportSample()                     // Export first 100 lines
getStatistics()                    // Get all collected data
reset()                            // Reset for multiple runs
```

### 2. CloneDetector.js (MODIFIED)
**Changes Made**:

#### Added Properties
- `#monitor`: MonitorTool instance for statistics collection
- `DEFAULT_BATCH_SIZE = 100`: Batch size for chunk processing

#### Added Methods
- `setMonitor(monitor)`: Inject MonitorTool instance (for testing)
- `#shouldSkipFile(file)`: Check file size/token limits before processing
- `getStatistics()`: Get monitoring statistics
- `getProgress(expectedTotalFiles)`: Get progress information
- `resetMonitoring()`: Reset monitoring for multiple runs

#### Modified Methods

##### `preprocess(file)` 
- Added file size validation using `#shouldSkipFile()`
- Logs warnings for skipped files

##### `transform(file)`
- Added high-precision timing for filter and chunkify stages
- Added batch indexing (100 chunks at a time) for memory management
- Added per-file statistics logging via MonitorTool
- Returns timing data for progress tracking

##### `matchDetect(file)`
- Added batch processing (100 chunks at a time) for memory efficiency
- Added comprehensive timing for match and expansion phases
- Logs stage completion with candidate/expansion counts

##### `pruneFile(file)`
- Added aggressive memory cleanup
- Stores only chunk metadata instead of full objects
- Deletes original contents after processing

##### `#filterCloneCandidates(file, compareFile)`
- Added batch statistics logging
- Tracks candidates generated per batch

##### `#expandCloneCandidates(file)`
- Fixed typo in timing variable (`hrtide` → `hrtime`)
- Added expansion statistics logging
- Tracks number of clones expanded

##### `#consolidateClones(file)`
- Added clone size tracking for average calculation
- Uses `MonitorTool.logCloneSize()`

### 3. index.js (MODIFIED)
**Changes Made**:

#### Added Imports
- `MonitorTool` for statistics collection

#### Added Global Variables
- `monitor`: Global MonitorTool instance

#### Error Handling
- Added MonitorTool logging for uncaught exceptions
- Added MonitorTool logging for unhandled rejections

#### New Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/monitor` | GET | View detailed monitoring statistics in HTML |
| `/progress` | GET | Get JSON progress (supports `?expected_total=N`) |
| `/export-csv` | GET | Download full statistics as CSV |
| `/export-sample` | GET | Download sample (first 100 lines) as CSV |

#### Enhanced Endpoints

##### `fileReceiver` (POST `/`)
- Added file read timing using `process.hrtime.bigint()`
- Added per-file statistics logging
- Added error tracking via MonitorTool

##### `viewClones` (GET `/`)
- Added monitoring statistics summary
- Added links to monitoring and progress pages
- Enhanced styling for statistics display

##### `getStatistics()`
- Added MonitorTool summary statistics
- Displays total chunks, candidates, expanded, avg clone size, avg chunks/file

#### Processing Enhancements

##### `processFile()`
- Added global timing using MonitorTool stages
- Added per-stage timing logging
- Added periodic CSV export (every 100 files)
- Added comprehensive error tracking

#### Shutdown Handlers
- Added SIGINT handler for graceful shutdown
- Added SIGTERM handler for graceful shutdown
- Both export statistics before exiting

#### Timer Endpoint (GET `/timers`)
- Added error level tracking for monitoring
- Enhanced display with statistics

### 4. Dockerfile (MODIFIED)
**Changes Made**:

#### Added Environment Variables
```dockerfile
ENV PORT=3000
ENV CHUNKSIZE=5
ENV MAX_FILE_SIZE=10485760      # 10MB max file size
ENV MAX_TOKENS=100000           # 100K tokens max per file
ENV BATCH_SIZE=100              # Batch size for processing
ENV URL='http://localhost:3000/'
```

#### Added Volume Mount Point
```dockerfile
RUN mkdir -p /app/data
```

## Performance Improvements

### Memory Management
1. **Batch Processing**: Chunks are indexed and processed in batches of 100
2. **Aggressive Pruning**: File contents are freed after processing
3. **Chunk Metadata Only**: Only store chunk line numbers, not full content
4. **Size Limits**: Skip files >10MB or >100K tokens

### Statistics Collection
1. **High-Precision Timing**: Using `process.hrtime.bigint()` for nanosecond accuracy
2. **Comprehensive Logging**: Every stage logged with context
3. **CSV Export**: Full data export for external analysis
4. **Progress Tracking**: Cumulative counters for prediction

## Data Collection for Analysis

### File Statistics
- File name, size, timestamp
- Chunks generated, total chunks
- Read time, chunkify time, total time
- Running average chunks per file

### Batch Statistics
- Batch sequence number
- Candidates generated this batch
- Total candidates, running average
- Processing time, candidates per file

### Expansion Statistics
- Expansion sequence number
- Clones expanded this round
- Total expanded, remaining candidates
- Average expansion time

### Stage Statistics
- Stage name, duration
- Cumulative totals (chunks, candidates, expanded, files)

### Summary Statistics
- Total files processed
- Total chunks, candidates, expanded
- Average clone size (for expansion prediction)
- Average chunks per file (for processing prediction)

## Analysis Questions Addressed

### Chunk Generation Time
- Tracked via `chunkify_time_ms` in file stats
- Can analyze `chunkify_time_ms` vs `total_chunks` to determine if constant

### Clone Candidate Generation Time
- Tracked via `batch_time_ms` in batch stats
- Can analyze `batch_time_ms` vs `total_candidates` to determine if constant

### Clone Expansion Time
- Tracked via `expand_time_ms` in expansion stats
- Can analyze `expand_time_ms` vs `total_expanded` or `remaining_candidates`

### Average Clone Size
- Tracked via `logCloneSize()` in consolidation phase
- Used to predict remaining expansion work

### Average Chunks per File
- Calculated as `total_chunks / total_files`
- Used to predict total work for N files

## Files Modified

| File | Type | Changes |
|------|------|---------|
| `src/MonitorTool.js` | NEW | Statistics collection and export |
| `src/CloneDetector.js` | MODIFIED | Added monitoring, batch processing, memory management |
| `src/index.js` | MODIFIED | Added endpoints, monitoring integration, graceful shutdown |
| `Dockerfile` | MODIFIED | Added environment variables for corpus processing |

## Usage

### Running with Docker
```bash
docker build -t codestream-consumer .
docker run -p 3000:3000 -v $(pwd)/data:/app/data codestream-consumer
```

### Viewing Statistics
- Main page: http://localhost:3000/
- Monitoring page: http://localhost:3000/monitor
- Progress API: http://localhost:3000/progress?expected_total=1000
- CSV Export: http://localhost:3000/export-csv
- Sample Export: http://localhost:3000/export-sample

### Expected Output Files
- `data/statistics.csv` - Full statistics (updated periodically)
- `data/statistics_sample_100.csv` - First 100 file entries

## Limitations and Future Improvements

### Current Limitations
1. In-memory CloneStorage (not persistent across restarts)
2. Single-node processing (no distributed mode)
3. Exact matching only (no fuzzy matching)
4. Java files only

### Potential Improvements
1. Add database persistence (MongoDB/PostgreSQL)
2. Implement distributed processing with message queue
3. Add fuzzy matching with tokenization
4. Support multiple programming languages
5. Add caching for previously processed files

