(ns cljdetector.storage.storage
  (:require [monger.core :as mg]
            [monger.collection :as mc]
            [monger.operators :refer :all]
            [monger.conversion :refer [from-db-object]]))

;; =========================
;; CONFIGURATION
;; =========================
(def DEFAULT-DBHOST "mongo-db")
(def dbname "cloneDetector")
(def partition-size (or (some-> (System/getenv "BATCH_SIZE") Integer/parseInt) 1000))
(def hostname (or (System/getenv "DBHOST") DEFAULT-DBHOST))
(def collnames ["files" "chunks" "candidates" "clones" "statusUpdates" "statistics"])

;; =========================
;; DB CONNECTION
;; =========================
(def conn (mg/connect {:host hostname}))
(def db (mg/get-db conn dbname))

;; =========================
;; INDEX MANAGEMENT
;; =========================
(defn drop-chunk-indexes! []
  (try
    (mc/drop-indexes db "chunks")
    (catch Exception _ nil)))

(defn create-chunk-indexes! []
  (try
    (mc/ensure-index db "chunks" {:chunkHash 1})
    (mc/ensure-index db "chunks" {:fileName 1 :startLine 1 :endLine 1})
    (catch Exception _ nil)))

;; =========================
;; BASIC HELPERS
;; =========================
(defn count-items [collname]
  (mc/count db collname))

(defn clear-db! []
  (doseq [coll collnames]
    (try
      (mc/remove db coll)
      (catch Exception _ nil))))

(defn print-statistics []
  (println "===== DATABASE STATISTICS =====")
  (doseq [coll collnames]
    (println coll ":" (mc/count db coll))))

;; =========================
;; FILE STORAGE
;; =========================
(defn store-files! [files]
  (doseq [file-group (partition-all partition-size files)]
    (let [docs (keep (fn [f]
                       (try
                         {:fileName (.getPath f)
                          :contents (slurp f)}
                         (catch Exception _ nil)))
                     file-group)]
      (when (seq docs)
        (mc/insert-batch db "files" docs)))))

;; =========================
;; CHUNK STORAGE
;; =========================
(defn store-chunks! [chunks]
  (doseq [chunk-group (partition-all partition-size chunks)]
    (when (seq chunk-group)
      (mc/insert-batch db "chunks" chunk-group))))

;; =========================
;; CLONE STORAGE
;; =========================
(defn store-clones! [clones]
  (doseq [clone-group (partition-all partition-size clones)]
    (when (seq clone-group)
      (mc/insert-batch db "clones" clone-group))))

(defn store-clone! [conn clone]
  (mc/insert (mg/get-db conn dbname)
             "clones"
             (select-keys clone [:numberOfInstances :instances])))

;; =========================
;; STATUS LOGGING
;; =========================
(defn addUpdate! [timestamp message]
  (mc/insert db "statusUpdates"
             {:timestamp timestamp
              :message message}))

;; =========================
;; STATISTICS
;; =========================
(defn add-stat! [phase payload]
  (let [record (merge {:timestamp (.toString (java.time.LocalDateTime/now))
                       :phase phase}
                      payload)]
    (mc/insert db "statistics" record)))

(defn time-phase [phase f]
  (let [start (System/currentTimeMillis)
        result (f)
        duration (- (System/currentTimeMillis) start)]
    (add-stat! phase {:durationMs duration})
    result))

;; =========================
;; CANDIDATE GENERATION (SCALABLE)
;; =========================
(defn identify-candidates! []

  (println "Clearing old candidates...")
  (mc/remove db "candidates")

  (println "Ensuring indexes...")
  (mc/ensure-index db "chunks" {:chunkHash 1})
  (mc/ensure-index db "candidates" {:processed 1})

  (let [batch-size 500000]

    (loop [last-id nil]

      (let [query (if last-id
                    {:_id {$gt last-id}}
                    {})

            batch (mc/find-maps db "chunks"
                                query
                                {:limit batch-size
                                 :sort {:_id 1}})

            last-doc (last batch)]

        (when (seq batch)

          (println "Processing batch...")

          (mc/aggregate db "chunks"
            [{$match query}
             {$limit batch-size}
             {$group {:_id "$chunkHash"
                      :instances {$push {:fileName "$fileName"
                                         :startLine "$startLine"
                                         :endLine "$endLine"}}}}
             {$match {$expr {$gt [{$size "$instances"} 1]}}}
             {$merge {:into "candidates"
                      :on "_id"
                      :whenMatched "merge"
                      :whenNotMatched "insert"}}]
            {:allowDiskUse true})

          (recur (:_id last-doc)))))))

;; =========================
;; ANALYTICS
;; =========================
(defn avg-clone-size []
  (let [result (mc/aggregate db "clones"
                 [{$project {:size {$size "$instances"}}}
                  {$group {:_id nil :avgSize {$avg "$size"}}}])]
    (if (seq result) (:avgSize (first result)) 0)))

(defn avg-chunks-per-file []
  (let [result (mc/aggregate db "chunks"
                 [{$group {:_id "$fileName" :chunks {$sum 1}}}
                  {$group {:_id nil :avgChunks {$avg "$chunks"}}}])]
    (if (seq result) (:avgChunks (first result)) 0)))



(defn consolidate-clones-and-source []
  (mc/aggregate db "clones"
    [{$project {:_id 0
                :instances "$instances"
                :sourcePosition {$first "$instances"}}}

     {"$addFields"
      {:cloneLength
       {"$subtract" ["$sourcePosition.endLine"
                     "$sourcePosition.startLine"]}}}

     {$lookup
      {:from "files"
       :let {:sourceName "$sourcePosition.fileName"
             :sourceStart {"$subtract" ["$sourcePosition.startLine" 1]}
             :sourceLength "$cloneLength"}
       :pipeline
       [{$match {$expr {$eq ["$fileName" "$$sourceName"]}}}
        {$project {:contents {"$split" ["$contents" "\n"]}}}
        {$project {:contents {"$slice" ["$contents" "$$sourceStart" "$$sourceLength"]}}}
        {$project {:_id 0
                   :contents
                   {"$reduce"
                    {:input "$contents"
                     :initialValue ""
                     :in {"$concat"
                          ["$$value"
                           {"$cond" [{"$eq" ["$$value" ""]} "" "\n"]}
                           "$$this"]}}}}}]
       :as "sourceContents"}}

     {$project {:_id 0
                :instances 1
                :contents "$sourceContents.contents"}}]))


;; =========================
;; EXPANSION SUPPORT (FIXED)
;; =========================
(defn get-dbconnection []
  (mg/connect {:host hostname}))

(defn get-one-candidate [conn]
  (let [db (mg/get-db conn dbname)]
    (from-db-object
     (mc/find-and-modify db "candidates"
       {:processed {$ne true}}
       {$set {:processed true}}
       {:return-new true})
     true)))

(defn get-overlapping-candidates [conn candidate]
  (let [db (mg/get-db conn dbname)
        clj-cand (from-db-object candidate true)]
    (mc/aggregate db "candidates"
      [{$match {"instances.fileName"
                {$all (map #(:fileName %) (:instances clj-cand))}}}
       {$addFields {:candidate candidate}}
       {$unwind "$instances"}
       {$project {:matches
                  {$filter
                   {:input "$candidate.instances"
                    :cond {$and [{$eq ["$$this.fileName" "$instances.fileName"]}
                                 {$or [{$and [{$gt ["$$this.startLine" "$instances.startLine"]}
                                              {$lte ["$$this.startLine" "$instances.endLine"]}]}
                                       {$and [{$gt ["$instances.startLine" "$$this.startLine"]}
                                              {$lte ["$instances.startLine" "$$this.endLine"]}]}]}]}}}
                  :instances 1
                  :numberOfInstances 1
                  :candidate 1}}
       {$match {$expr {$gt [{$size "$matches"} 0]}}}
       {$group {:_id "$_id"
                :candidate {$first "$candidate"}
                :numberOfInstances {$max "$numberOfInstances"}
                :instances {$push "$instances"}}}
       {$match {$expr {$eq [{$size "$candidate.instances"} "$numberOfInstances"]}}}
       {$project {:_id 1
                  :numberOfInstances 1
                  :instances 1}}])))

;; 🔥 IMPORTANT: no deletion anymore
(defn remove-overlapping-candidates! [conn candidates]
  nil)