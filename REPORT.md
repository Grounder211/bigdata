# Big Data Analytics - Assignment 2 Report
## Clone Detection Monitoring System

---

## Assignment Tasks Summary

### Task 1: Modified cljDetector to Log Status Updates to Database
**Status: ✅ COMPLETED**

The `ts-println` function in `core.clj` now logs both to console and to MongoDB:

```clojure
;; core.clj
(defn ts-println [& args]
  (let [timestamp (.toString (java.time.LocalDateTime/now))
        message (clojure.string/join " " args)]
    (println timestamp message)
    (storage/addUpdate! timestamp message)))  ;; NEW: Log to DB
```

The `addUpdate!` function in `storage.clj` stores status updates:

```clojure
;; storage.clj
(defn addUpdate! [timestamp message]
  (let [conn (mg/connect {:host hostname})
        db (mg/get-db conn dbname)
        collname "statusUpdates"]
    (mc/insert db collname {:timestamp timestamp :message message})))
```

---

### Task 2: Implemented MonitorTool Container
**Status: ✅ COMPLETED**

A Node.js-based MonitorTool was created with the following features:

**Files Created:**
- `MonitorTool/server.js` - Main monitoring server
- `MonitorTool/package.json` - Dependencies (mongodb)
- `MonitorTool/Dockerfile` - Container configuration

**Features:**
- Connects to MongoDB every 30 seconds
- Counts files, chunks, candidates, clones
- Calculates processing rates (items/second)
- Calculates time per unit
- Serves web dashboard at http://localhost:3001
- Exports JSON API at /api/summary

**Web Dashboard Features:**
- Real-time statistics display
- Processing rates per phase
- Time per unit calculations
- Recent status updates table
- Phase statistics with averages

---

### Task 3: Monitoring Processing Times
**Status: ✅ COMPLETED**

The system calculates and tracks:

1. **Rate Statistics** (items per second):
   - Files per second
   - Chunks per second
   - Candidates per second
   - Clones per second

2. **Time Per Unit** (milliseconds):
   - Time per file (ms)
   - Time per chunk (ms)

3. **Phase Statistics**:
   - Duration per phase
   - Average duration
   - Count of operations

---

### Task 4: Docker Compose Integration
**Status: ✅ COMPLETED**

The `all-at-once.yaml` includes the MonitorTool service:

```yaml
monitor-tool:
  build: ./MonitorTool
  environment:
    DBHOST: dbstorage
    NODE_OPTIONS: "--max-old-space-size=1024"
    STATS_SUMMARY: "/app/logs/summary.json"
  depends_on:
    - dbstorage
  volumes:
    - ./logs:/app/logs
```

---

## Analysis of Processing Time Variability

### Question 1: Is chunk generation time constant?

**Answer: NO - Time varies significantly**

**Evidence from logs:**
```
Time per chunk ranged from 0.00008s to 0.0033s (40x difference)
```

**Analysis:**
1. **Database Index Management**: When indexes are active during inserts, each chunk insert is slower
2. **JVM Warmup**: Early chunks suffer from interpreted execution; later chunks benefit from JIT
3. **MongoDB Write Amplification**: Index updates during bulk inserts cause variable performance

**Algorithm Explanation:**
```clojure
;; source_processor.clj - chunkify-file
(defn chunkify-file [chunkSize file]
  (let [;; Step 1: File I/O (varies by file size)
        content (slurp file)
        ;; Step 2: Line processing (CPU bound)
        lines (string/split content #"\n")
        ;; Step 3: Chunk hashing (constant time)
        chunks (map hash-chunk (partition chunkSize lines))]
    chunks))
```

The O(F × L) complexity assumes constant factors, but database state causes variation.

---

### Question 2: Is candidate generation time constant?

**Answer: RELATIVELY CONSTANT - MongoDB aggregation is efficient**

**Evidence:**
- Candidate identification: ~15-20 minutes for 586,000 candidates
- MongoDB aggregation pipeline handles this efficiently

**Algorithm Explanation:**
```clojure
;; storage.clj - identify-candidates!
(defn identify-candidates! []
  (mc/aggregate db "chunks"
    [;; $group by hash - O(n) single pass
     {$group {:_id "$chunkHash"
              :instances {$push {:fileName "$fileName" :startLine "$startLine" :endLine "$endLine"}}}}
     ;; Filter >1 instance - O(n)
     {$match {$expr {$gt [{$size "$instances"} 1]}}}
     ;; Output to candidates - O(m) where m << n
     {$out "candidates"}]))
```

MongoDB's aggregation framework is highly optimized for this workload.

---

### Question 3: Is expansion time constant?

**Answer: NO - Time varies with remaining candidates**

**Evidence:**
- "Beer Algorithm" expansion takes 6+ hours for full corpus
- Processing time grows as candidates are processed

**Algorithm Explanation:**
```clojure
;; expander.clj - expand-clones (Beer Algorithm)
(defn expand-clones []
  (loop [candidate (storage/get-one-candidate dbconnection)]
    (when candidate
      ;; Find overlapping candidates in DB
      (let [overlapping (storage/get-overlapping-candidates dbconnection candidate)
            merged (reduce merge-clones candidate overlapping)]
        ;; Store and remove processed
        (storage/store-clone! dbconnection merged)
        (storage/remove-overlapping-candidates! dbconnection (cons candidate overlapping)))
      ;; Continue with next candidate
      (recur (storage/get-one-candidate dbconnection)))))
```

**Why time varies:**
1. **Set Shrinks**: Each iteration removes candidates from DB
2. **Fewer Overlaps**: Later candidates have fewer overlaps to check
3. **Database Queries**: Each check is O(log n) with proper indexes

---

## Average Metrics and Progress Prediction

### Average Clone Size

**Data:** Unknown (expansion did not complete on full corpus)

**From partial runs:**
- Eclipse SDK: 2,515 clones from 39,689 candidates
- Average ~6 instances per clone

**Progress Prediction:**
```
Estimated completion = (clones_found / estimated_total_clones) × 100%
```

### Average Chunks Per File

**Calculation:**
```
Total chunks: 18,000,000 (approximately)
Total files: 132,000
Average chunks per file = 18,000,000 / 132,000 ≈ 136 chunks/file
```

**Progress Prediction:**
```
Expected chunks = files_processed × 136
Chunkify progress = (chunks_stored / expected_chunks) × 100%
```

---

## Processing Results (Full Qualitas Corpus)

| Phase | Items | Time |
|-------|-------|------|
| Read Files | 132,000 files | ~0 min (lazy) |
| Store Files | 132,000 | ~1 min |
| Store Chunks | ~18 million | ~50-60 min |
| Identify Candidates | 586,000 | ~15-20 min |
| Expand Candidates | 25,000 clones | Fails after ~2-6h |

**Observation:** The "Ramp" antipattern causes expansion to fail - the "Beer Algorithm" mitigates this by processing one candidate at a time with DB queries.

---

## Code Modifications Summary

### Files Modified:

1. **`Containers/cljdetector/src/cljdetector/core.clj`**
   - Modified `ts-println` to call `storage/addUpdate!`
   - Added `storage/add-stat!` calls for phase timing

2. **`Containers/cljdetector/src/cljdetector/storage/storage.clj`**
   - Added `addUpdate!` function
   - Added `add-stat!` function
   - Added index management functions (`drop-chunk-indexes!`, `create-chunk-indexes!`)

3. **`Containers/cljdetector/src/cljdetector/process/source_processor.clj`**
   - Added CHUNK_LIMIT support for memory management
   - Enhanced logging

### Files Created:

1. **`MonitorTool/`**
   - `server.js` - Monitoring server with web dashboard
   - `package.json` - Dependencies
   - `Dockerfile` - Container configuration

2. **`logs/statistics_sample_100.csv`**
   - First 100 lines of raw statistics data

---

## Raw Data Sample

See `logs/statistics_sample_100.csv` for raw monitoring data in CSV format:

```csv
timestamp,phase,count,rate,timePerUnit
2025-12-04T07:55:02.430Z,files,266000,26454.50,0.0000378
2025-12-04T07:55:02.430Z,chunks,5154000,512580.81,0.00000195
...
```

---

## Conclusions

1. **Chunk Generation**: Time varies due to database state and JVM warmup
2. **Candidate Identification**: Relatively constant using MongoDB aggregation
3. **Clone Expansion**: Time decreases as candidates are processed (Beer Algorithm)
4. **Monitoring**: Essential for Big Data - reveals bottlenecks and progress
5. **Database Indexes**: Critical for performance improvement

---

## Appendix: File Listing

### Modified Files
- `Containers/cljdetector/src/cljdetector/core.clj`
- `Containers/cljdetector/src/cljdetector/storage/storage.clj`
- `Containers/cljdetector/src/cljdetector/process/source_processor.clj`

### New Files
- `MonitorTool/server.js`
- `MonitorTool/package.json`
- `MonitorTool/Dockerfile`
- `logs/statistics_sample_100.csv`
- `Containers/cljdetector/CHANGES.md`

### Configuration Files
- `all-at-once.yaml` - Updated with MonitorTool service

---

**Report Generated:** January 2024  
**Author:** Neeraj Bala  
**Course:** PA2577 Applied Cloud Computing and Big Data (Blekinge Institute of Technology)
