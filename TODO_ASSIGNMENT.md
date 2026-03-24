# Big Data Analytics Assignment 2 - ✅ COMPLETE

## Processing Results

| Phase | Status | Count | Notes |
|-------|--------|-------|-------|
| Chunk Generation | ✅ COMPLETE | 368,000 files, 8.46M chunks | ~45 min |
| Candidate Identification | ✅ COMPLETE | 1,545 candidates | ~1 min |
| Clone Expansion | ✅ COMPLETE | ~900 clones | ~30 min |
| **Total** | **✅ SUCCESS** | **Full corpus** | **~76 min** |

## Deliverables Created

| File | Purpose |
|------|---------|
| `REPORT.md` | Complete assignment report with all answers |
| `Containers/cljdetector/CHANGES.md` | Code modification documentation |
| `logs/statistics_sample_100.csv` | Raw statistics sample (100 lines) |
| `TODO_ASSIGNMENT.md` | This progress tracker |

## Issues Fixed

1. **Memory Exhaustion (OOM)** - Fixed with:
   - Instance limiting (MAX-INSTANCES=100)
   - Periodic garbage collection (System.gc every 500)
   - Candidate cleanup after processing
   - Streaming mode option for large datasets

## Analysis Summary

| Question | Answer |
|----------|--------|
| Process entire corpus? | **YES** - All phases complete |
| Main issues? | Memory during expansion - **FIXED** |
| Chunk time constant? | **NO** - Varies 40x (DB state, JVM warmup) |
| Candidate time constant? | **YES** - MongoDB aggregation efficient |
| Expansion time constant? | **NO** - O(n²) merge complexity |
| Average clone size? | ~3-5 instances (estimated) |
| Average chunks/file? | **22.99** (calculated) |

## Key Metrics

- Average chunks per file: **22.99**
- Candidates found: **1,545**
- Clones generated: **~900**
- Processing rate: **3,133 chunks/sec** (average)

## Status: ASSIGNMENT COMPLETE ✅
