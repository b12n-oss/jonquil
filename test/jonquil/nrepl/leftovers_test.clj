(ns jonquil.nrepl.leftovers-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.nrepl.leftovers :as leftovers]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def ^:private now 1800000000000)
(def ^:private old (- now leftovers/prune-age-ms 60000))
(def ^:private fresh (- now 60000))
(def ^:private ancient (- now leftovers/pidless-age-ms 60000))

;; well past any pid a test machine hands out
(def ^:private dead-pid 2147483646)

(defn- stamp! [^java.io.File f t] (.setLastModified f t) f)

(defn- run-dir!
  "A folder under base named the way the children name theirs (the same
  createTempDirectory call), with a pid file unless pid is nil, and every
  file in it stamped t."
  [base prefix t pid & files]
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    (.toPath (io/file base)) prefix
                    (make-array java.nio.file.attribute.FileAttribute 0)))]
    (when pid (leftovers/write-pid! d pid))
    (doseq [[n text] files] (spit (io/file d n) text))
    (doseq [f (.listFiles d)] (stamp! f t))
    (stamp! d t)))

(defn- folder! [base n t]
  (let [d (io/file base n)] (.mkdirs d) (stamp! d t)))

(defn- file! [base n t]
  (let [f (io/file base n)] (spit f "log") (stamp! f t)))

(defn- names [base] (set (.list (io/file base))))

(deftest the-names-the-children-make-match
  (let [base (temp-dirs/temp-dir! "jonquil-leftovers-")
        made (mapv #(.getName ^java.io.File (run-dir! base % old dead-pid))
                   ["jonquil-cljs-run-" "jonquil-jank-run-" "jonquil-cljs-"])]
    (is (= 3 (leftovers/prune! base now)) (str "pruned " made))
    (is (empty? (names base)))))

(deftest old-leftovers-go-and-fresh-ones-stay
  (let [base (temp-dirs/temp-dir! "jonquil-leftovers-")
        keep-dir (run-dir! base "jonquil-cljs-run-" fresh dead-pid ["server.log" "x"])]
    (run-dir! base "jonquil-cljs-run-" old dead-pid ["server.log" "x"])
    ;; the launcher's own folder, named on the JVM
    (folder! base "jonquil-cljs-8094523351234567890" old)
    (spit (io/file base "jonquil-cljs-8094523351234567890" "pid") (str dead-pid))
    (stamp! (io/file base "jonquil-cljs-8094523351234567890" "pid") old)
    (file! base "jonquil-nrepl-444.log" old)
    (file! base "jonquil-nrepl-666.log" fresh)
    (is (= 3 (leftovers/prune! base now)) "the count it deleted")
    (is (= #{(.getName keep-dir) "jonquil-nrepl-666.log"} (names base)))))

(deftest only-jonquils-own-names-are-touched
  (let [base (temp-dirs/temp-dir! "jonquil-leftovers-")]
    (folder! base "jonquil-nrepl-test-1-abc" ancient)
    (folder! base "jonquil-cljs-run-abc" ancient)
    (folder! base "jonquil-smoke-session" ancient)
    (file! base "jonquil-cljs-run-7" ancient)
    (folder! base "jonquil-nrepl-8.log" ancient)
    (file! base "other-9.log" ancient)
    (is (= 0 (leftovers/prune! base now)))
    (is (= 6 (count (names base))) "a name off the pattern, or a file where a folder belongs, stays")))

(deftest a-fresh-file-inside-keeps-an-old-folder
  (let [base (temp-dirs/temp-dir! "jonquil-leftovers-")
        d (run-dir! base "jonquil-cljs-run-" old dead-pid ["server.log" "still writing"])]
    (stamp! (io/file d "server.log") fresh)
    (is (= 0 (leftovers/prune! base now)))
    (is (= #{(.getName d)} (names base)))))

(deftest a-running-pid-keeps-its-folder-at-any-age
  (let [base (temp-dirs/temp-dir! "jonquil-leftovers-")
        live (run-dir! base "jonquil-cljs-run-" ancient (.pid (java.lang.ProcessHandle/current)))]
    (run-dir! base "jonquil-jank-run-" ancient dead-pid)
    (is (= 1 (leftovers/prune! base now)))
    (is (= #{(.getName live)} (names base)))))

(deftest a-folder-with-no-pid-waits-a-week
  (let [base (temp-dirs/temp-dir! "jonquil-leftovers-")
        recent (run-dir! base "jonquil-cljs-run-" old nil)]
    (run-dir! base "jonquil-cljs-run-" ancient nil)
    (is (= 1 (leftovers/prune! base now)))
    (is (= #{(.getName recent)} (names base)) "a day old with no pid stays")))

(deftest a-symlink-and-its-target-are-left-alone
  (let [base (temp-dirs/temp-dir! "jonquil-leftovers-")
        target (folder! (temp-dirs/temp-dir! "jonquil-leftovers-target-") "keep" ancient)
        link (io/file base "jonquil-cljs-run-1-1")]
    (spit (io/file target "a") "b")
    (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath target)
                                            (make-array java.nio.file.attribute.FileAttribute 0))
    (is (= 0 (leftovers/prune! base now)))
    (is (.exists link))
    (is (.isFile (io/file target "a")))))

(deftest a-missing-base-is-zero
  (is (= 0 (leftovers/prune! "/nonexistent-jonquil-leftovers" now))))
