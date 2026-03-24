(ns cljdetector.storage.storage
  (:require [monger.core :as mg]
            [monger.collection :as mc]
            [monger.operators :refer :all]
            [monger.conversion :refer [from-db-object]]))

(def DEFAULT-DBHOST "localhost")
(def dbname "cloneDetector")
(def partition-size (or (some-> (System/getenv "BATCH_SIZE") Integer/parseInt) 1000))
(def hostname (or (System/getenv "DBHOST") DEFAULT-DBHOST))
(def collnames ["files"  "chunks" "candidates" "clones" "statusUpdates" "statistics"])

;; MODIFIED: Index management helpers to reduce write amplification during chunking
(defn drop-chunk-indexes! []
  (let [conn (mg/connect {:host hostname})
        db (mg/get-db conn dbname)
        collname "chunks"]
    (try
      ;; Drop all indexes on chunks to speed up bulk inserts
      (mc/drop-indexes db collname)
      (catch Exception _ nil))))

(defn create-chunk-indexes! []
  (let [conn (mg/connect {:host hostname})
        db (mg/get-db conn dbname)
        collname "chunks"]
    (try
      ;; Recreate useful indexes post-insert
      (mc/ensure-index db collname {:chunkHash 1})
      (mc/ensure-index db collname {:fileName 1 :startLine 1 :endLine 1})
      (catch Exception _ nil))))

(defn print-statistics []
  (let [conn (mg/connect {:host hostname})        
        db (mg/get-db conn dbname)]
    (doseq [coll collnames]
      (println "db contains" (mc/count db coll) coll))))

(defn clear-db! []
  (let [conn (mg/connect {:host hostname})        
        db (mg/get-db conn dbname)]
    (doseq [coll collnames]
      (mc/drop db coll))))

(defn count-items [collname]
  (let [conn (mg/connect {:host hostname})        
        db (mg/get-db conn dbname)]
    (mc/count db collname)))

(defn store-files! [files]
  (let [conn (mg/connect {:host hostname})
        db (mg/get-db conn dbname)
        collname "files"
        file-parted (partition-all partition-size files)]
    (try
      (doseq [file-group file-parted]
        (mc/insert-batch db collname (map (fn [f] {:fileName (.getPath f) :contents (slurp f)}) file-group)))
      (catch Exception _ nil))))

;; MODIFIED: Batch inserts using insert-batch with larger partitions; assumes indexes disabled beforehand.
(defn store-chunks! [chunks]
  (let [conn (mg/connect {:host hostname})
        db (mg/get-db conn dbname)
        collname "chunks"
        chunk-parted (partition-all partition-size (flatten chunks))]
    (doseq [chunk-group chunk-parted]
      (mc/insert-batch db collname chunk-group))))

(defn store-clones! [clones]
  (let [conn (mg/connect {:host hostname})        
        db (mg/get-db conn dbname)
        collname "clones"
        clones-parted (partition-all partition-size clones)]
    (doseq [clone-group clones-parted]
      (mc/insert-batch db collname (map identity clone-group)))))

;; NEW: statistics helpers
(defn add-stat! [phase payload]
  (let [conn (mg/connect {:host hostname})
        db (mg/get-db conn dbname)]
    (let [record (merge {:timestamp (.toString (java.time.LocalDateTime/now))
                         :phase phase}
                        payload)
          stats-file (or (System/getenv "STATS_LOG") "logs/stats.ndjson")]
      (mc/insert db "statistics" record)
      (try
        ;; ensure directory exists
        (let [f (java.io.File. stats-file)
              dir (.getParentFile f)]
          (when (and dir (not (.exists dir)))
            (.mkdirs dir)))
        ;; write as EDN per line (portable without extra deps)
        (spit stats-file (str (pr-str record) "\n") :append true)
        (catch Exception _
          ;; ignore file write errors silently to not block pipeline
          nil))))))

(defn avg-clone-size []
  (let [conn (mg/connect {:host hostname})
        db (mg/get-db conn dbname)]
    (first
     (mc/aggregate db "clones"
                   [{$project {:size {:$sum [{$subtract ["$instances.endLine" "$instances.startLine"]}]}}}
                    {$group {:_id nil :avgSize {:$avg "$size"}}}]))))

(defn avg-chunks-per-file []
  (let [conn (mg/connect {:host hostname})
        db (mg/get-db conn dbname)]
    (first
     (mc/aggregate db "chunks"
                   [{$group {:_id "$fileName" :chunks {:$sum 1}}}
                    {$group {:_id nil :avgChunks {:$avg "$chunks"}}}]))))

(defn identify-candidates! []
  (let [conn (mg/connect {:host hostname})
        db (mg/get-db conn dbname)
        collname "chunks"]
    (mc/aggregate db collname
                  [{$group {:_id "$chunkHash"
                            :instances {$push {:fileName "$fileName"
                                               :startLine "$startLine"
                                               :endLine "$endLine"}}}}
                   {$match {$expr {$gt [{$size "$instances"} 1]}}}
                   {$out "candidates"}])))


(defn consolidate-clones-and-source []
  (let [conn (mg/connect {:host hostname})        
        db (mg/get-db conn dbname)
        collname "clones"]
    (mc/aggregate db collname
                  [{$project {:_id 0 :instances "$instances" :sourcePosition {$first "$instances"}}}
                   {"$addFields" {:cloneLength {"$subtract" ["$sourcePosition.endLine" "$sourcePosition.startLine"]}}}
                   {$lookup
                    {:from "files"
                     :let {:sourceName "$sourcePosition.fileName"
                           :sourceStart {"$subtract" ["$sourcePosition.startLine" 1]}
                           :sourceLength "$cloneLength"}
                     :pipeline
                     [{$match {$expr {$eq ["$fileName" "$$sourceName"]}}}
                      {$project {:contents {"$split" ["$contents" "\n"]}}}
                      {$project {:contents {"$slice" ["$contents" "$$sourceStart" "$$sourceLength"]}}}
                      {$project
                       {:_id 0
                        :contents 
                        {"$reduce"
                         {:input "$contents"
                          :initialValue ""
                          :in {"$concat"
                               ["$$value"
                                {"$cond" [{"$eq" ["$$value", ""]}, "", "\n"]}
                                "$$this"]
                               }}}}}]
                     :as "sourceContents"}}
                   {$project {:_id 0 :instances 1 :contents "$sourceContents.contents"}}])))


(defn get-dbconnection []
  (mg/connect {:host hostname}))

(defn get-one-candidate [conn]
  (let [db (mg/get-db conn dbname)
        collname "candidates"]
    (from-db-object (mc/find-one db collname {}) true)))

(defn get-overlapping-candidates [conn candidate]
  (let [db (mg/get-db conn dbname)
        collname "candidates"
        clj-cand (from-db-object candidate true)]
    (mc/aggregate db collname
                  [{$match {"instances.fileName" {$all (map #(:fileName %) (:instances clj-cand))}}}
                   {$addFields {:candidate candidate}}
                   {$unwind "$instances"}
                   {$project 
                    {:matches
                     {$filter
                      {:input "$candidate.instances"
                       :cond {$and [{$eq ["$$this.fileName" "$instances.fileName"]}
                                    {$or [{$and [{$gt  ["$$this.startLine" "$instances.startLine"]}
                                                 {$lte ["$$this.startLine" "$instances.endLine"]}]}
                                          {$and [{$gt  ["$instances.startLine" "$$this.startLine"]}
                                                 {$lte ["$instances.startLine" "$$this.endLine"]}]}]}]}}}
                     :instances 1
                     :numberOfInstances 1
                     :candidate 1
                     }}
                   {$match {$expr {$gt [{$size "$matches"} 0]}}}
                   {$group {:_id "$_id"
                            :candidate {$first "$candidate"}
                            :numberOfInstances {$max "$numberOfInstances"}
                            :instances {$push "$instances"}}}
                   {$match {$expr {$eq [{$size "$candidate.instances"} "$numberOfInstances"]}}}
                   {$project {:_id 1 :numberOfInstances 1 :instances 1}}])))

(defn remove-overlapping-candidates! [conn candidates]
  (let [db (mg/get-db conn dbname)
        collname "candidates"]
      (mc/remove db collname {:_id {$in (map #(:_id %) candidates)}})))

(defn store-clone! [conn clone]
  (let [db (mg/get-db conn dbname)
        collname "clones"
        anonymous-clone (select-keys clone [:numberOfInstances :instances])]
    (mc/insert db collname anonymous-clone)))

(defn addUpdate! [timestamp message]
  (let [conn (mg/connect {:host hostname})
        db (mg/get-db conn dbname)
        collname "statusUpdates"]
    (mc/insert db collname {:timestamp timestamp :message message})))
