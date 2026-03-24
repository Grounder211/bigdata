# TODO List for Big Data Analytics Assignment

## Completed Tasks
- [x] Modify cljDetector.core/ts-println to log status updates to database
- [x] Add storage/addUpdate! function to insert into 'statusUpdates' collection
- [x] Create MonitorTool container with Node.js, Express, MongoDB driver
- [x] Implement monitoring logic: poll database every 10 seconds for counts and status updates
- [x] Calculate processing statistics: rates and time per unit for files, chunks, candidates, clones
- [x] Create web visualization with current stats, status updates, and processing time trends
- [x] Update all-at-once.yaml to include MonitorTool service
- [x] Build MonitorTool Docker image

## Pending Tasks
- [x] Run the full system on Qualitas Corpus (test run completed)
- [x] Monitor the processing via web interface at http://localhost:3001
- [x] Collect statistics over time (counts, processing times, trends)
- [x] Analyze trends: determine if processing times are constant, linear, or exponential
- [x] Prepare report with:
  - Summary of modifications
  - Raw data sample from monitoring
  - Analysis of processing time trends
  - Answers to assignment questions
- [x] Zip modified code and YAML for submission
