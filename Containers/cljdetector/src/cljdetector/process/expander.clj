(ns cljdetector.process.expander
  (:require [clojure.string :as string]
            [cljdetector.storage.storage :as storage])
  (:import [java.lang System]))

;; Common to both solutions
;; ----------------------------------------

(defn sort-instances [instances]
  (sort-by (juxt :fileName :startLine) instances))

(defn merge-clones [cand-a cand-b]
  (let [inst-a (sort-instances (:instances cand-a))
        inst-b (sort-instances (:instances cand-b))]
    {:_id (:_id cand-a)
     :numberOfInstances (:numberOfInstances cand-a)
     :instances (map (fn [ia ib]
                       {:fileName (:fileName ia)
                        :startLine (min (:startLine ia) (:startLine ib))
                        :endLine (max (:endLine ia) (:endLine ib))}
                       ) inst-a inst-b)
     }))


;; Aleph-null BoBoTW solution - Optimized for memory
;; ----------------------------------------

;; Maximum instances to store per clone (prevents memory exhaustion)
(def MAX-INSTANCES 100)

;; Batch size for processing
(def BATCH-SIZE 50)

(defn maybe-expand [dbconnection candidate]
  (loop [overlapping (storage/get-overlapping-candidates dbconnection candidate)
         clone candidate]
    (if (empty? overlapping)
      (do
        (storage/remove-overlapping-candidates! dbconnection (list candidate))
        clone)
      (let [merged-clone (merge-clones clone (first overlapping))
            ;; Limit instances to prevent memory exhaustion
            limited-clone (update merged-clone :instances #(take MAX-INSTANCES %))]
        (storage/remove-overlapping-candidates! dbconnection overlapping)
        (recur (storage/get-overlapping-candidates dbconnection limited-clone)
               limited-clone)))))

(defn expand-clones []
  (let [dbconnection (storage/get-dbconnection)
        start-time (System/nanoTime)
        total-candidates (storage/count-items "candidates")]
    (println (str "Starting expansion phase with " total-candidates " candidates..."))
    (loop [candidate (storage/get-one-candidate dbconnection)
           processed 0
           cloned 0]
      (if candidate
        (try
          (let [clone (maybe-expand dbconnection candidate)]
            ;; Store clone and force immediate cleanup
            (storage/store-clone! dbconnection clone)
            (when (= (mod processed 100) 0)
              (let [remaining (storage/count-items "candidates")]
                (println (str "EXPAND_STATS|" (inc processed) "|"
                             (int (/ (- (System/nanoTime) start-time) 1000000.0)) "|"
                             remaining "|"
                             (storage/count-items "clones")))))
            ;; Force garbage collection every 500 candidates to prevent OOM
            (when (= (mod processed 500) 0)
              (System/gc))
            (recur (storage/get-one-candidate dbconnection) (inc processed) (inc cloned)))
          (catch Exception e
            (println (str "Error expanding candidate " processed ": " (.getMessage e)))
            ;; Skip problematic candidate and continue
            (storage/remove-overlapping-candidates! dbconnection (list candidate))
            (recur (storage/get-one-candidate dbconnection) (inc processed) cloned)))
        ;; Final summary
        (let [duration (/ (- (System/nanoTime) start-time) 1000000.0)
              clone-count (storage/count-items "clones")]
          (println (str "EXPAND_STATS|FINAL|" (int duration) "|0|" clone-count))
          (println (str "Expansion completed in " duration " ms, total clones: " clone-count))
          (println (str "Candidates processed: " processed))
          (println (str "Clones stored: " clone-count))))))

;; Alternative: Streaming batch expansion for very large datasets
(defn expand-clones-streaming []
  (let [dbconnection (storage/get-dbconnection)
        start-time (System/nanoTime)
        total-candidates (storage/count-items "candidates")]
    (println (str "Starting STREAMING expansion with " total-candidates " candidates..."))
    (loop [processed 0
           cloned 0]
      (let [candidate (storage/get-one-candidate dbconnection)]
        (if (nil? candidate)
          ;; Final summary
          (let [duration (/ (- (System/nanoTime) start-time) 1000000.0)
                clone-count (storage/count-items "clones")]
            (println (str "STREAMING Expansion completed in " duration " ms"))
            (println (str "Total clones: " clone-count))
            clone-count)
          (try
            (let [clone (maybe-expand dbconnection candidate)]
              (storage/store-clone! dbconnection clone)
              ;; Progress logging
              (when (= (mod processed 100) 0)
                (println (str "Progress: " processed "/" total-candidates 
                             " candidates, " (storage/count-items "clones") " clones")))
              ;; Aggressive GC every batch
              (when (= (mod processed BATCH-SIZE) 0)
                (System/gc)
                (Thread/sleep 10))  ;; Brief pause for GC
              (recur (inc processed) (inc cloned)))
            (catch Exception e
              (println (str "Error at candidate " processed ": " (.getMessage e)))
              (storage/remove-overlapping-candidates! dbconnection (list candidate))
              (recur (inc processed) cloned))))))))

