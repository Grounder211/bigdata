(ns cljdetector.core
  (:require [clojure.string :as string]
            [cljdetector.process.source-processor :as source-processor]
            [cljdetector.process.expander :as expander]
            [cljdetector.storage.storage :as storage]))

(def DEFAULT-CHUNKSIZE 5)
(def source-dir (or (System/getenv "SOURCEDIR") "/tmp"))
(def source-type #".*\.java")
(def chunk-limit-env (System/getenv "CHUNK_LIMIT"))

(defn ts-println [& args]
  (let [timestamp (.toString (java.time.LocalDateTime/now))
        message (clojure.string/join " " args)]
    (println timestamp message)
    (storage/addUpdate! timestamp message)))

(defn maybe-clear-db [args]
  (when (some #{"CLEAR"} (map string/upper-case args))
      (ts-println "Clearing database...")
      (storage/clear-db!)))

(defn maybe-read-files [args]
  (when-not (some #{"NOREAD"} (map string/upper-case args))
    (ts-println "Reading and Processing files...")
  (let [chunk-param (System/getenv "CHUNKSIZE")
      chunk-size (if chunk-param (Integer/parseInt chunk-param) DEFAULT-CHUNKSIZE)
      file-handles (source-processor/traverse-directory source-dir source-type)
      start-ts (System/nanoTime)]
    (ts-println "Dropping chunk indexes to speed inserts...")
    (storage/drop-chunk-indexes!)
    (let [chunks (source-processor/chunkify chunk-size file-handles)]
    (ts-println "Storing files...")
    (storage/store-files! file-handles)
    (ts-println "Storing chunks of size" chunk-size "...")
    (storage/store-chunks! chunks)
    (ts-println "Recreating chunk indexes after inserts...")
    (storage/create-chunk-indexes!)
  (ts-println "Chunk indexes recreated; recording chunkify stats...")
    (let [duration-ms (long (/ (- (System/nanoTime) start-ts) 1000000))
        chunk-count (storage/count-items "chunks")
        file-count (storage/count-items "files")]
      (storage/add-stat! "chunkify" {:durationMs duration-ms
                      :chunkSize chunk-size
                      :chunks chunk-count
                      :files file-count}))
    (ts-println "Finished storing chunks" (storage/count-items "chunks") "chunks total")))))

(defn maybe-detect-clones [args]
  (when-not (some #{"NOCLONEID"} (map string/upper-case args))
    (ts-println "Identifying Clone Candidates...")
    (let [start-id (System/nanoTime)]
      (storage/identify-candidates!)
      (let [duration-ms (long (/ (- (System/nanoTime) start-id) 1000000))
            cand-count (storage/count-items "candidates")]
  (ts-println "Recording identify stats..." cand-count)
        (storage/add-stat! "identify" {:durationMs duration-ms :candidates cand-count})
        (ts-println "Found" cand-count "candidates")))
    (ts-println "Expanding Candidates...")
    (let [start-exp (System/nanoTime)]
      (expander/expand-clones)
      (let [duration-ms (long (/ (- (System/nanoTime) start-exp) 1000000))
            clone-count (storage/count-items "clones")]
  (ts-println "Recording expansion stats..." clone-count)
  (storage/add-stat! "expand" {:durationMs duration-ms :clones clone-count})))))

(defn pretty-print [clones]
  (doseq [clone clones]
    (println "====================\n" "Clone with" (count (:instances clone)) "instances:")
    (doseq [inst (:instances clone)]
      (println "  -" (:fileName inst) "startLine:" (:startLine inst) "endLine:" (:endLine inst)))
    (println "\nContents:\n----------\n" (:contents clone) "\n----------")))

(defn maybe-list-clones [args]
  (when (some #{"LIST"} (map string/upper-case args))
    (ts-println "Consolidating and listing clones...")
    (pretty-print (storage/consolidate-clones-and-source))))



(defn -main
  "Starting Point for All-At-Once Clone Detection
  Arguments:
   - Clear clears the database
   - NoRead do not read the files again
   - NoCloneID do not detect clones
   - List print a list of all clones"
  [& args]

  (maybe-clear-db args)
  (maybe-read-files args)
  (maybe-detect-clones args)
  (maybe-list-clones args)
  (ts-println "Summary")
  (storage/print-statistics)
  (let [avg-clone (storage/avg-clone-size)
        avg-chunks (storage/avg-chunks-per-file)]
    (ts-println "Average clone size:" avg-clone)
    (ts-println "Average chunks per file:" avg-chunks)))
