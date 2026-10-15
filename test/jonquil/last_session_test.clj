(ns jonquil.last-session-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.last-session :as ls]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def ^:private warning "Couldn't read sessions.edn, so no tabs were reopened.")

(defn- dir! [] (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-last-session-test-"))))

(defn- entry [root] {:root root :tabs [] :active nil})

(deftest sessions-path-test
  (is (= "/x/jonquil/sessions.edn" (ls/sessions-path "/x/jonquil/settings.edn"))))

(deftest load-missing-test
  (let [r (ls/load-sessions (str (dir!) "/nope.edn"))]
    (is (= {:sessions []} r))
    (is (not (contains? r :warning)))))

(deftest load-bad-edn-test
  (let [p (str (dir!) "/sessions.edn")]
    (spit p "{:sessions [")
    (is (= {:sessions [] :warning warning} (ls/load-sessions p)))))

(deftest load-wrong-shape-test
  (doseq [text ["[1 2]" "{:sessions 5}"]]
    (let [p (str (dir!) "/sessions.edn")]
      (spit p text)
      (is (= warning (:warning (ls/load-sessions p))) text))))

(deftest load-drops-bad-entries-test
  (let [p (str (dir!) "/sessions.edn")]
    (spit p (pr-str {:version 1
                     :sessions [{:root "/a" :tabs ["/a/x.clj" "rel.clj" 7] :active "/a/x.clj"}
                                {:root 5 :tabs []}
                                "junk"
                                {:root nil :tabs [] :active nil}]}))
    (let [r (ls/load-sessions p)]
      (is (not (contains? r :warning)))
      (is (= 2 (count (:sessions r))))
      (is (= ["/a/x.clj"] (:tabs (first (:sessions r)))))
      (is (nil? (:root (second (:sessions r))))))))

(deftest remember-front-and-replace-test
  (let [r (ls/remember [(entry "/a") (entry "/b")] {:root "/b" :tabs ["/b/t.clj"] :active "/b/t.clj"})]
    (is (= ["/b" "/a"] (mapv :root r)))
    (is (= ["/b/t.clj"] (:tabs (first r))))))

(deftest remember-trims-test
  (let [old (mapv #(entry (str "/r" %)) (range 20))
        r (ls/remember old (entry "/new"))]
    (is (= 20 (count r)))
    (is (= "/new" (:root (first r))))
    (is (not (some #(= "/r19" (:root %)) r)))))

(deftest remember-nil-root-test
  (let [r (ls/remember [(assoc (entry nil) :tabs ["/x.clj"]) (entry "/a")] (entry nil))]
    (is (= [nil "/a"] (mapv :root r)))
    (is (= [] (:tabs (first r))))))

(deftest remember-empty-tabs-test
  (let [r (ls/remember [{:root "/a" :tabs ["/a/x.clj"] :active "/a/x.clj"}]
                       {:root "/a" :tabs [] :active nil})]
    (is (= [{:root "/a" :tabs [] :active nil}] r))))

(deftest entry-for-test
  (let [ss [(entry "/a") (entry "/b")]]
    (is (= "/b" (:root (ls/entry-for ss "/b"))))
    (is (= "/a" (:root (ls/entry-for ss nil))))
    (is (nil? (ls/entry-for ss "/zzz")))
    (is (nil? (ls/entry-for [] "/a")))
    (is (nil? (ls/entry-for [] nil)))))

(deftest round-trip-test
  (let [p (str (dir!) "/sessions.edn")
        ss [{:root "/a" :tabs ["/a/x.clj" "/a/y.clj"] :active "/a/y.clj"}
            {:root nil :tabs [] :active nil}]]
    (is (nil? (ls/save-sessions! p ss)))
    (is (= ss (:sessions (ls/load-sessions p))))
    (let [m (edn/read-string (slurp p))]
      (is (map? m))
      (is (= 1 (:version m))))))

(deftest save-through-symlink-test
  (let [d (dir!)
        target (str d "/elsewhere/real.edn")
        link (str d "/sessions.edn")
        ss [{:root "/a" :tabs [] :active nil}]]
    (io/make-parents target)
    (spit target "{}")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file link)) (.toPath (io/file target))
     (make-array java.nio.file.attribute.FileAttribute 0))
    (is (nil? (ls/save-sessions! link ss)))
    (is (java.nio.file.Files/isSymbolicLink (.toPath (io/file link))))
    (is (= ss (:sessions (edn/read-string (slurp target)))))))

(deftest save-failure-returns-string-test
  (let [d (dir!)
        blocker (str d "/file")]
    (spit blocker "x")
    (let [r (ls/save-sessions! (str blocker "/sessions.edn") [])]
      (is (string? r))
      (is (.startsWith ^String r "couldn't save ")))))

(deftest missing-line-test
  (is (= "; Couldn't reopen a.clj from last time.\n" (ls/missing-line ["a.clj"])))
  (is (= "; Couldn't reopen 2 files from last time: a.clj, b.clj\n"
         (ls/missing-line ["a.clj" "b.clj"])))
  (is (nil? (ls/missing-line []))))
