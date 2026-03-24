(ns cljdetector.process.expander
  (:require [clojure.string :as string]
            [cljdetector.storage.storage :as storage])
  (:import [java.lang System]))

;; =========================
;; CONFIG
;; =========================
(def MAX-INSTANCES 100)
(def GC-INTERVAL 500)
(def LOG-INTERVAL 100)

;; =========================
;; HELPERS
;; =========================
(defn sort-instances [instances]
  (sort-by (juxt :fileName :startLine) instances))

(defn merge-clones [cand-a cand-b]
  (let [inst-a (sort-instances (:instances cand-a))
        inst-b (sort-instances (:instances cand-b))
        merged (map (fn [ia ib]
                      {:fileName (:fileName ia)
                       :startLine (min (:startLine ia) (:startLine ib))
                       :endLine (max (:endLine ia) (:endLine ib))})
                    inst-a inst-b)]
    {:_id (:_id cand-a)
     :numberOfInstances (count merged)
     :instances (take MAX-INSTANCES merged)}))

;; =========================
;; EXPAND ONE CANDIDATE
;; =========================
(defn maybe-expand [dbconnection candidate]
  (loop [current candidate]
    (let [overlapping (storage/get-overlapping-candidates dbconnection current)]
      (if (empty? overlapping)
        (do
          (storage/remove-overlapping-candidates! dbconnection (list current))
          current)
        (let [merged (reduce merge-clones current overlapping)
              limited (update merged :instances #(take MAX-INSTANCES %))]
          (storage/remove-overlapping-candidates! dbconnection overlapping)
          (recur limited))))))

;; =========================
;; MAIN EXPANSION (FIXED)
;; =========================
(defn expand-clones []
  (let [dbconnection (storage/get-dbconnection)
        start-time (System/nanoTime)
        total (storage/count-items "candidates")]

    (println "Starting clone expansion with" total "candidates")

    (loop [processed 0
           cloned 0]

      (let [candidate (storage/get-one-candidate dbconnection)]

        (if (nil? candidate)

          ;; ===== DONE =====
          (let [duration (/ (- (System/nanoTime) start-time) 1000000.0)]
            (println "===== EXPANSION COMPLETE =====")
            (println "Time (ms):" duration)
            (println "Processed:" processed)
            (println "Clones:" (storage/count-items "clones")))

          ;; ===== PROCESS SAFELY =====
          (let [[success? clone]
                (try
                  [true (maybe-expand dbconnection candidate)]
                  (catch Exception e
                    (println "Error at candidate" processed ":" (.getMessage e))
                    [false nil]))]

            ;; HANDLE RESULT OUTSIDE TRY
            (if success?

              (do
                (storage/store-clone! dbconnection clone)

                (when (= 0 (mod processed LOG-INTERVAL))
                  (println "Processed:" processed
                           "| Remaining:" (storage/count-items "candidates")
                           "| Clones:" (storage/count-items "clones")))

                (when (= 0 (mod processed GC-INTERVAL))
                  (System/gc))

                (recur (inc processed) (inc cloned)))

              ;; ERROR CASE
              (do
                (storage/remove-overlapping-candidates! dbconnection (list candidate))
                (recur (inc processed) cloned)))))))))