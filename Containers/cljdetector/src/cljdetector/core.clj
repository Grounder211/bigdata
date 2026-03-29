(ns cljdetector.core
  (:require [clojure.string :as string]
            [cljdetector.process.source-processor :as source-processor]
            [cljdetector.process.expander :as expander]
            [cljdetector.storage.storage :as storage]))

(def DEFAULT-CHUNKSIZE 10)
(def source-dir (or (System/getenv "SOURCEDIR") "/tmp"))
(def source-type #".*\.java")

;; =========================
;; LOGGING (UPDATED)
;; =========================
(defn ts-println [& args]
  (let [timestamp (.toString (java.time.LocalDateTime/now))
        message (clojure.string/join " " args)]
    (println timestamp message)
    (storage/addUpdate! timestamp message))) ;; NEW (DB logging)

;; =========================
;; CLEAR DATABASE
;; =========================
(defn maybe-clear-db [args]
  (when (some #{"CLEAR"} (map string/upper-case args))
    (ts-println "Clearing database...")
    (storage/clear-db!)))

;; =========================
;; READ + CHUNKIFY (FIXED)
;; =========================
(defn maybe-read-files [args]
  (when-not (some #{"NOREAD"} (map string/upper-case args))
    (ts-println "Reading and Processing files...")

    (let [chunk-param (System/getenv "CHUNKSIZE")
          chunk-size (if chunk-param (Integer/parseInt chunk-param) DEFAULT-CHUNKSIZE)
          file-handles (source-processor/traverse-directory source-dir source-type)]

      ;; drop indexes for faster inserts
      (ts-println "Dropping chunk indexes...")
      (storage/drop-chunk-indexes!)

      ;; store files
      (storage/time-phase "files"
        #(storage/store-files! file-handles))

      ;; chunkify (STREAMING — no chunks variable)
      (storage/time-phase "chunkify"
        #(source-processor/chunkify-all chunk-size file-handles))

      ;; recreate indexes
      (ts-println "Recreating chunk indexes...")
      (storage/create-chunk-indexes!)

      ;; stats
      (storage/add-stat! "chunkify"
        {:chunks (storage/count-items "chunks")
         :files  (storage/count-items "files")})

      (ts-println "Finished chunking:"
                  (storage/count-items "chunks") "chunks"))))
;; =========================
;; CLONE DETECTION (FIXED)
;; =========================
(defn maybe-detect-clones [args]
  (when-not (some #{"NOCLONEID"} (map string/upper-case args))

    ;; Candidate generation
    (ts-println "Identifying clone candidates...")
    (storage/time-phase "candidates"
      #(storage/identify-candidates!))

    (ts-println "Candidates found:" (storage/count-items "candidates"))

    ;; Expansion (FIXED)
    (ts-println "Expanding candidates...")
    (storage/time-phase "clones"
      #(expander/expand-clones))

    (ts-println "Clones generated:" (storage/count-items "clones"))))

;; =========================
;; PRINT CLONES
;; =========================
(defn pretty-print [clones]
  (doseq [clone clones]
    (println "====================")
    (println "Clone with" (count (:instances clone)) "instances:")
    (doseq [inst (:instances clone)]
      (println " -" (:fileName inst)
               "startLine:" (:startLine inst)
               "endLine:" (:endLine inst)))
    (println "\nContents:\n----------\n" (:contents clone) "\n----------")))

(defn maybe-list-clones [args]
  (when (some #{"LIST"} (map string/upper-case args))
    (ts-println "Listing clones...")
    (pretty-print (storage/consolidate-clones-and-source))))

;; =========================
;; MAIN ENTRY POINT
;; =========================
(defn -main [& args]

  (ts-println "=== Clone Detection Started ===")

  (maybe-clear-db args)
  (maybe-read-files args)
  (maybe-detect-clones args)
  (maybe-list-clones args)

  ;; Final statistics
  (ts-println "=== Summary ===")
  (storage/print-statistics)

  (let [avg-clone (storage/avg-clone-size)
        avg-chunks (storage/avg-chunks-per-file)]
    (ts-println "Average clone size:" avg-clone)
    (ts-println "Average chunks per file:" avg-chunks))

  (ts-println "=== Finished ==="))