# cljDetector Assignment Fixes - TODO Steps

## Current Progress: 0/8 ✅

### Step 1: [✅] Add `time-phase` function to storage.clj
   - Wraps function execution with duration/count stats

### Step 2: [✅] Update core.clj 
   - Use `time-phase` for all 4 phases (files/chunks/candidates/expansion)
   - Ensure calls `expander/expand-clones` (streaming version)

### Step 3: [✅] Enhance source_processor.clj
   - Add `GC-INTERVAL 500` + batch limits during chunkify-all

### Step 4: [✅] Create read-stats.js
   - Export first 100 statistics lines as CSV for assignment

### Step 5: [ ] Update CHANGES.md
    - Document all modifications [PENDING - precise append]

### Step 6: [✅] Tune all-at-once.yaml
    - 16g heap, G1GC, depends_on, CHUNK_LIMIT

### Step 7: [✅] Create run-detection.sh
    - docker-compose up + tail logs + open monitor

### Step 8: [✅] Test run ready (Docker daemon needed)
    - Fixed build in yaml + run-detection.sh with docker build
    - Start Docker Desktop → `run-detection.sh` → generates stats

---

**Next: Complete Step 1 → mark [✅] → Step 2...**

**Success Criteria:** Streaming processes full test corpus without OOM → stats/logs generated → ready for REPORT.md
