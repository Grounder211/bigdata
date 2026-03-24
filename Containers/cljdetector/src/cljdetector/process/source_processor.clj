(ns cljdetector.process.source-processor
  (:use [clojure.java.io])
  (:require [clojure.string :as string]
            [clj-commons.digest :as digest]
            [cljdetector.storage.storage :as storage]))

;; =========================
;; FILE VALIDATION (CRITICAL FIX)
;; =========================
(defn valid-source-file? [file pattern]
  (and (.isFile file)                     ;; MUST be a file (fixes your crash)
       (.canRead file)                    ;; avoid permission issues
       (re-matches pattern (.getName file))))

;; =========================
;; SAFE FILE READ (ROBUSTNESS)
;; =========================
(defn safe-slurp [file]
  (try
    (slurp file)
    (catch Exception e
      (println "Skipping unreadable file:" (.getPath file))
      nil)))

;; =========================
;; PATTERNS (UNCHANGED)
;; =========================
(def emptyLine (re-pattern "^\\s*$"))
(def oneLineComment (re-pattern "//.*"))
(def oneLineMultiLineComment (re-pattern "/\\*.*?\\*/"))
(def openMultiLineComment (re-pattern "/\\*+[^*/]*$"))
(def closeMultiLineComment (re-pattern "^[^*/]*\\*+/"))

;; =========================
;; LINE PROCESSING (UNCHANGED)
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
;; CHUNKIFY SINGLE FILE (FIXED + SAFE)
;; =========================
(defn chunkify-file [chunkSize file]
  (try
    (let [start (System/nanoTime)
          fileName (.getPath file)
          content (safe-slurp file)]

      (if (nil? content)
        [] ;; skip bad files

        (let [chunk-limit (or (some-> (System/getenv "CHUNK_LIMIT") Integer/parseInt) 0)

              filteredLines (filter #(not (= "" (:contents %)))
                                    (-> content
                                        (string/split #"\n")
                                        process-lines))

              total-chunks (max 0 (- (count filteredLines) chunkSize))

              max-iter (if (and chunk-limit (> chunk-limit 0))
                         (min total-chunks chunk-limit)
                         total-chunks)

              chunks (map (fn [i]
                            (let [chunk (take chunkSize (nthrest filteredLines i))
                                  startLine (:lineNumber (first chunk))
                                  endLine (:lineNumber (last chunk))
                                  hash (digest/md5 (string/join "\n" (map :contents chunk)))]
                              {:fileName fileName
                               :startLine startLine
                               :endLine endLine
                               :chunkHash hash}))
                          (range max-iter))

              duration (/ (- (System/nanoTime) start) 1000000.0)]

          (println (str "Chunkified " fileName " in " duration " ms, chunks: " (count chunks)))

          chunks)))

    (catch Exception e
      (println "Error processing file:" (.getPath file))
      [])))

;; =========================
;; STREAMING CHUNKIFY (GOOD DESIGN)
;; =========================
(def GC-INTERVAL 500)

(defn chunkify-all [chunkSize files]

  (println "Starting chunkification...")

  (doseq [[idx file] (map-indexed vector files)]

    (let [chunks (chunkify-file chunkSize file)]

      ;; streaming write (scalable)
      (when (seq chunks)
        (storage/store-chunks! chunks)))

    ;; progress logging
    (when (= 0 (mod idx 100))
      (println "Processed files:" idx))

    ;; GC (helps large corpus)
    (when (= 0 (mod idx GC-INTERVAL))
      (System/gc)))

  (println "Chunkification complete"))

;; =========================
;; FIXED DIRECTORY TRAVERSAL (CRITICAL)
;; =========================
(defn traverse-directory [path pattern]
  (->> (file-seq (file path))
       (filter #(valid-source-file? % pattern))))