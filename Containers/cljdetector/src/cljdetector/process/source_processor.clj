(ns cljdetector.process.source-processor
  (:use [clojure.java.io])
  (:require [clojure.string :as string]
            [clj-commons.digest :as digest]
            [cljdetector.storage.storage :as storage]))

;; =========================
;; FILE VALIDATION
;; =========================
(defn valid-source-file? [file pattern]
  (and (.isFile file)
       (.canRead file)
       (re-matches pattern (.getName file))))

;; =========================
;; SAFE FILE READ
;; =========================
(defn safe-slurp [file]
  (try
    (slurp file)
    (catch Exception _
      (println "Skipping unreadable file:" (.getPath file))
      nil)))

;; =========================
;; PATTERNS
;; =========================
(def emptyLine (re-pattern "^\\s*$"))
(def oneLineComment (re-pattern "//.*"))
(def oneLineMultiLineComment (re-pattern "/\\*.*?\\*/"))
(def openMultiLineComment (re-pattern "/\\*+[^*/]*$"))
(def closeMultiLineComment (re-pattern "^[^*/]*\\*+/"))

;; =========================
;; LINE PROCESSING
;; =========================
(defn process-lines [lines]
  (drop 1
        (reduce (fn [collection item]
                  (conj collection
                        (let [index (+ 1 (:lineNumber (last collection)))]
                          (cond
                            (and (= (:lineType (last collection)) "multiLineComment")
                                 (re-matches closeMultiLineComment item))
                            {:lineNumber index :contents (string/trim (string/replace item closeMultiLineComment "")) :lineType "lastMultiLineComment"}

                            (= (:lineType (last collection)) "multiLineComment")
                            {:lineNumber index :contents "" :lineType "multiLineComment"}

                            (re-matches emptyLine item)
                            {:lineNumber index :contents "" :lineType "emptyLine"}

                            (re-matches oneLineComment item)
                            {:lineNumber index :contents (string/trim (string/replace item oneLineComment "")) :lineType "oneLineComment"}

                            (re-matches oneLineMultiLineComment item)
                            {:lineNumber index :contents (string/trim (string/replace item oneLineMultiLineComment "")) :lineType "oneLineMultiLineComment"}

                            (re-matches openMultiLineComment item)
                            {:lineNumber index :contents (string/trim (string/replace item openMultiLineComment "")) :lineType "multiLineComment"}

                            :else
                            {:lineNumber index :contents (string/trim item) :lineType "normal"}))))
                [{:lineNumber 0 :contents "" :lineType "startLine"}]
                lines)))

;; =========================
;; CHUNK FILTERING (NEW)
;; =========================
(defn meaningful-chunk? [chunk]
  (> (count (filter #(not= "" %) (map :contents chunk))) 3))

;; =========================
;; CHUNKIFY SINGLE FILE (FIXED)
;; =========================
(defn chunkify-file [chunkSize file]
  (try
    (let [start (System/nanoTime)
          fileName (.getPath file)
          content (safe-slurp file)]

      (if (nil? content)
        []

        (let [chunk-limit (or (some-> (System/getenv "CHUNK_LIMIT") Integer/parseInt) 0)

              filteredLines (filter #(not= "" (:contents %))
                                    (-> content
                                        (string/split #"\n")
                                        process-lines))

              total-chunks (max 0 (- (count filteredLines) chunkSize))

              max-iter (if (and chunk-limit (> chunk-limit 0))
                         (min total-chunks chunk-limit)
                         total-chunks)

              ;; FIXED: filtering applied here
              chunks (->> (range max-iter)
                          (map (fn [i]
                                 (let [chunk (take chunkSize (nthrest filteredLines i))]
                                   (when (meaningful-chunk? chunk)
                                     {:fileName fileName
                                      :startLine (:lineNumber (first chunk))
                                      :endLine (:lineNumber (last chunk))
                                      :chunkHash (digest/md5
                                                   (string/join "\n" (map :contents chunk)))}))))
                          (remove nil?))

              duration (/ (- (System/nanoTime) start) 1000000.0)]

          (println (str "Chunkified " fileName " in " duration " ms, chunks: " (count chunks)))

          chunks)))

    (catch Exception e
      (println "Error processing file:" (.getPath file))
      [])))

;; =========================
;; STREAMING CHUNKIFY (PARALLELIZED)
;; =========================
(def GC-INTERVAL 500)

(defn chunkify-all [chunkSize files]

  (println "Starting chunkification...")

  ;; parallel processing
  (doseq [[idx file] (map-indexed vector (pmap identity files))]

    (let [chunks (chunkify-file chunkSize file)]

      (when (seq chunks)
        (storage/store-chunks! chunks)))

    (when (= 0 (mod idx 100))
      (println "Processed files:" idx))

    (when (= 0 (mod idx GC-INTERVAL))
      (System/gc)))

  (println "Chunkification complete"))

;; =========================
;; DIRECTORY TRAVERSAL
;; =========================
(defn traverse-directory [path pattern]
  (->> (file-seq (file path))
       (filter #(valid-source-file? % pattern))))