(ns leiningen.uberjar
  "Package up all the project's files and dependencies into a jar file."
  (:require [clojure.xml :as xml])
  (:use [clojure.zip :only [xml-zip children]]
        [clojure.java.io :only [file copy]]
        [leiningen.core :only [abort]]
        [leiningen.clean :only [clean]]
        [leiningen.jar :only [get-jar-filename get-default-uberjar-name jar]]
        [leiningen.deps :only [deps]])
  (:import (java.util.zip ZipFile ZipOutputStream ZipEntry)
           (java.io File FileOutputStream PrintWriter)))

(defn read-components [zipfile]
  (when-let [entry (.getEntry zipfile "META-INF/plexus/components.xml")]
    (->> (xml-zip (xml/parse (.getInputStream zipfile entry)))
         children
         (filter #(= (:tag %) :components))
         first
         :content)))

(defn- skip? [entry skip-set]
  (reduce (fn [skip matcher]
            (or skip (if (string? matcher)
                       (= matcher (.getName entry))
                       (re-find matcher (.getName entry)))))
          false skip-set))

(defn- copy-entries
  "Copies the entries of ZipFile in to the ZipOutputStream out, skipping
  the entries which satisfy skip-pred. Returns the names of the
  entries copied."
  [in out skip-set]
  (for [file (enumeration-seq (.entries in))
        :when (not (skip? file skip-set))]
    (do
      (.setCompressedSize file -1) ; some jars report size incorrectly
      (.putNextEntry out file)
      (copy (.getInputStream in file) out)
      (.closeEntry out)
      (.getName file))))

;; we have to keep track of every entry we've copied so that we can
;; skip duplicates.  We also collect together all the plexus components so
;; that we can merge them.
(defn include-dep [out [skip-set components] dep]
  (println "Including" (.getName dep))
  (with-open [zipfile (ZipFile. dep)]
    [(into skip-set (copy-entries zipfile out skip-set))
     (concat components (read-components zipfile))]))

(defn write-components
  "Given a list of jarfiles, writes contents to a stream"
  [project deps out]
  (let [[_ components] (reduce (partial include-dep out)
                               [(into #{"META-INF/plexus/components.xml"}
                                      (:uberjar-exclusions project)) nil]
                               deps)]
    (when-not (empty? components)
      (.putNextEntry out (ZipEntry. "META-INF/plexus/components.xml"))
      (binding [*out* (PrintWriter. out)]
        (xml/emit {:tag :component-set
                   :content
                   [{:tag :components
                     :content
                     components}]})
        (.flush *out*))
      (.closeEntry out))))

(defn uberjar
  "Create a jar like the jar task, but including the contents of each of
the dependency jars. Suitable for standalone distribution."
  ([project uberjar-name]
     (doto project
       clean deps)
     (if (jar project)
       (let [standalone-filename (get-jar-filename project uberjar-name)]
         (with-open [out (-> standalone-filename
                             (FileOutputStream.)
                             (ZipOutputStream.))]
           (let [deps (->> (.listFiles (file (:library-path project)))
                           (filter #(.endsWith (.getName %) ".jar"))
                           (cons (file (get-jar-filename project))))]
             (write-components project deps out)))
         (println "Created" standalone-filename))
       (abort "Uberjar aborting because jar/compilation failed.")))
  ([project] (uberjar project (get-default-uberjar-name project))))
