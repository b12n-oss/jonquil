(ns temp-dirs
  "Temp directories for tests and self-test drivers that clean up after
  themselves. temp-dir! makes a fresh directory under java.io.tmpdir and
  records it; delete-made! (or the :once fixture, which calls it) deletes
  every recorded directory. Nothing outside java.io.tmpdir is ever made
  or deleted here."
  (:require [clojure.java.io :as io]))

(defonce ^:private made (atom #{}))

(defn temp-dir!
  "A fresh directory java.io.tmpdir/<prefix><nanos>-<uuid>, created and
  recorded for delete-made!. Returns its path under java.io.tmpdir, not
  the canonical one (on macOS that resolves /var to /private/var): a
  caller that compares canonical paths canonicalizes its own."
  [prefix]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str prefix (System/nanoTime) "-" (random-uuid)))]
    (.mkdirs d)
    (let [path (.getPath d)]
      (swap! made conj path)
      path)))

(defn delete-tree!
  "Deletes dir and everything under it. A missing dir is fine. Goes
  through Files/deleteIfExists, which removes a symlink itself: Jolt's
  File.delete returns false for a dangling link and leaves it (measured
  on v0.8.15), where the JVM's deletes it."
  [dir]
  (doseq [^java.io.File f (reverse (file-seq (io/file dir)))]
    (try (java.nio.file.Files/deleteIfExists (.toPath f))
         (catch Exception _ nil))))

(defn delete-made!
  "Deletes every directory temp-dir! has made and not yet deleted."
  []
  (let [[old _] (reset-vals! made #{})]
    (doseq [d old] (delete-tree! d))))

(defn fixture
  "A clojure.test fixture: runs the tests, then deletes what they made."
  [f]
  (try (f) (finally (delete-made!))))
