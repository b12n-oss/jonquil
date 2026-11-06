(ns jonquil.replay-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [jonquil.replay :as replay]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- dir! [] (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-replay-test-"))))

(defn- perms [path]
  (java.nio.file.attribute.PosixFilePermissions/toString
   (java.nio.file.Files/getPosixFilePermissions
    (.toPath (io/file path)) (make-array java.nio.file.LinkOption 0))))

(def ^:private file-entry {:kind :file :path "/a.clj" :at 1 :dialect "clj"})
(def ^:private form-entry {:kind :form :text "(+ 1 2)" :ns "user" :source-file nil :at 2 :dialect "clj"})

(deftest record-path-test
  (is (= (str "/c/jonquil/replays/my-app-" (Integer/toHexString (.hashCode "/p/my app")) ".edn")
         (replay/record-path "/c/jonquil/settings.edn" "/p/my app")))
  (is (= "/c/jonquil/replays/no-folder.edn" (replay/record-path "/c/jonquil/settings.edn" nil))))

(deftest load-missing-record-test
  (let [r (replay/load-record (str (dir!) "/nope.edn"))]
    (is (= {:record (replay/empty-record)} r))
    (is (not (contains? r :warning)))))

(deftest load-bad-record-test
  (doseq [text ["{:previous [" "[1 2]" "{:version 1 :previous 5}"]]
    (let [p (str (dir!) "/rec.edn")]
      (spit p text)
      (let [r (replay/load-record p)]
        (is (= (replay/empty-record) (:record r)) text)
        (is (= "Couldn't read rec.edn, so the last session starts empty." (:warning r)) text)))))

(deftest save-load-round-trip-test
  (let [p (str (dir!) "/replays/rec.edn")
        rec {:version 1 :previous [file-entry form-entry] :current [] :history ["(+ 1 2)"]}]
    (is (nil? (replay/save-record! p rec)))
    (is (= {:record rec} (replay/load-record p)))))

(deftest save-through-symlink-test
  (let [d (dir!)
        target (str d "/elsewhere/real.edn")
        link (str d "/rec.edn")
        rec (assoc (replay/empty-record) :previous [file-entry])]
    (io/make-parents target)
    (spit target "{}")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file link)) (.toPath (io/file target))
     (make-array java.nio.file.attribute.FileAttribute 0))
    (is (nil? (replay/save-record! link rec)))
    (is (java.nio.file.Files/isSymbolicLink (.toPath (io/file link))))
    (is (= rec (edn/read-string (slurp target))))))

(deftest save-record-owner-only-test
  (let [d (dir!)
        p (str d "/replays/rec.edn")]
    (is (nil? (replay/save-record! p (replay/empty-record))))
    (is (= "rw-------" (perms p)))
    (is (= "rwx------" (perms (str d "/replays"))))
    (is (nil? (replay/save-record! p (replay/empty-record))))
    (is (= "rw-------" (perms p)))))

(deftest load-drops-bad-entries-test
  (let [p (str (dir!) "/rec.edn")]
    (spit p (pr-str {:version 1 :current [{:kind :file :path "/a.clj"} "junk" {:kind :zap}]}))
    (let [r (replay/load-record p)]
      (is (not (contains? r :warning)))
      (is (= [{:kind :file :path "/a.clj"}] (:current (:record r)))))))

(deftest save-record-preexisting-open-dir-test
  (let [d (dir!)
        rd (str d "/replays")
        p (str rd "/rec.edn")]
    (.mkdirs (io/file rd))
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (io/file rd))
     (java.nio.file.attribute.PosixFilePermissions/fromString "rwxr-xr-x"))
    (is (nil? (replay/save-record! p (replay/empty-record))))
    (is (= "rw-------" (perms p)))
    (is (= "rwxr-xr-x" (perms rd)))))

(deftest save-record-nil-path-test
  (is (string? (replay/save-record! nil (replay/empty-record)))))

;; --- entries, slots, compaction, script ------------------------------------

(defn- ents [m] (replay/entries (merge {:at 1 :dialect "Jolt"} m)))

(defn- form [text ns & {:as more}]
  (merge {:kind :form :text text :ns ns :source-file nil :at 1 :dialect "Jolt"} more))

(defn- tfile [path] {:kind :file :path path :at 1 :dialect "Jolt"})

(deftest entries-saved-run-test
  (is (= [{:kind :file :path "/p/a.clj" :at 1 :dialect "Jolt"}]
         (ents {:source :run :code "(ns a)" :path "/p/a.clj"}))))

(deftest entries-untitled-run-test
  (let [es (ents {:source :run :path nil
                  :code "(ns a.b)\n(defn f [] 1)\n(load-file \"/p/x.clj\")\n#_(skip)"})]
    (is (= [(form "(ns a.b)" "user") (form "(defn f [] 1)" "a.b") (tfile "/p/x.clj")] es))))

(deftest entries-track-in-ns-test
  (let [es (ents {:source :repl :ns "user" :code "(in-ns 'foo) (def x 1)"})]
    (is (= [(form "(in-ns 'foo)" "user") (form "(def x 1)" "foo")] es))))

(deftest entries-form-in-file-test
  (is (= [(form "(defn g [] 2)" "a" :source-file "/p/a.clj")]
         (ents {:source :form :path "/p/a.clj" :ns "a" :code "(defn g [] 2)"}))))

(deftest entries-relative-load-file-is-a-form-test
  (is (= [(form "(load-file \"rel.clj\")" "user")]
         (ents {:source :repl :ns "user" :code "(load-file \"rel.clj\")"}))))

(deftest open-slot-test
  (let [r {:previous [1] :current []}]
    (is (= r (replay/open-slot r))))
  (is (= {:previous [1 2 3] :current []}
         (replay/open-slot {:previous [1] :current [2 3]})))
  (let [r (replay/open-slot {:previous [] :current (vec (range 2100))})]
    (is (= (vec (range 100 2100)) (:previous r)))
    (is (= [] (:current r)))))

(deftest add-forget-history-test
  (is (= (vec (range 100 2100))
         (:current (replay/add-entries {:current (vec (range 1000))} (range 1000 2100)))))
  (is (= [] (:previous (replay/forget {:previous [1 2]}))))
  (is (= (vec (range 100 600)) (:history (replay/with-history {} (range 600))))))

(deftest compact-groups-test
  (let [es [(tfile "/p/a.clj") (tfile "/p/b.clj") (tfile "/p/a.clj")
            (form "(def x 1)" "user") (form "(def x 2)" "user")
            (form "(def y 1)" "a") (form "(def y 1)" "b")
            (form "(defmethod area :sq [s] 1)" "user")
            (form "(defmethod area :circle [s] 2)" "user")
            (form "(require '[a.b])" "user") (form "(swap! db inc)" "user")
            (form "(require '[a.b])" "user") (form "(swap! db inc)" "user")
            (form "(+ 1 2)" "user")]
        c (replay/compact es)]
    (is (= ["/p/b.clj" "/p/a.clj"] (:files c)))
    (is (= ["(def x 2)" "(def y 1)" "(def y 1)" "(defmethod area :sq [s] 1)"
            "(defmethod area :circle [s] 2)" "(require '[a.b])"]
           (map :text (:defs c))))
    (is (= ["a" "b"] (map :ns (subvec (:defs c) 1 3))))
    (is (= ["(swap! db inc)" "(+ 1 2)"] (map :text (:others c))))))

(deftest compact-drops-forms-of-loaded-files-test
  (let [f #(form "(defn g [] 2)" "a" :source-file "/p/a.clj")]
    (is (= [] (:defs (replay/compact [(f) (tfile "/p/a.clj")]))))
    (is (= [] (:defs (replay/compact [(tfile "/p/a.clj") (f)]))))
    (is (= 1 (count (:defs (replay/compact [(tfile "/p/b.clj") (f)])))))))

(def ^:private hour 3600000)

(def ^:private script-es
  [(assoc (tfile "/p/src/app/core.clj") :at 1000)
   (form "(def conn 1)" "app.core" :at 2000)
   (form "(start-server)" "app.core" :at 3000)])

(deftest script-text-test
  (is (= (str ";; Last session in /p (Jolt, 14 hours ago), 1 file,\n"
              ";; 1 definition, 1 other form. Nothing has run. Edit, then Run (Cmd/Ctrl+R).\n"
              ";; A replay runs the code again, so results can differ from last time.\n"
              "\n"
              ";; Files you ran, loaded as they are on disk now\n"
              "(load-file \"/p/src/app/core.clj\")\n"
              "\n"
              ";; Definitions from the console\n"
              "(in-ns 'app.core)\n"
              "(def conn 1)\n"
              "\n"
              ";; Other forms, skipped: remove the #_ to run one\n"
              "(in-ns 'app.core)\n"
              "#_(start-server)")
         (replay/script script-es {:root "/p" :now (+ 3000 (* 14 hour)) :dialect "Jolt"}))))

(deftest script-shape-test
  (let [now (+ 3000 (* 14 hour))
        s (replay/script [(form "(a)" "x" :at 1) (form "(b)" "y" :at 2) (form "(c)" "y" :at 3)
                          (tfile "/p/a.clj") (tfile "/p/b.clj")]
                         {:root nil :now now :dialect "Jolt"})]
    (is (str/starts-with? s ";; Last session with no folder (Jolt, 14 hours ago), 2 files,\n;; 0 definitions, 3 other forms."))
    (is (not (str/includes? s "Definitions from the console")))
    (is (str/includes? s "(in-ns 'x)\n#_(a)\n(in-ns 'y)\n#_(b)\n#_(c)"))))

(deftest script-dialect-differs-test
  (let [lines (str/split-lines (replay/script script-es {:root "/p" :now 5000 :dialect "Clojure (JVM)"}))]
    (is (= ";; Recorded in Jolt; this window runs Clojure (JVM)." (nth lines 3)))
    (is (= "" (nth lines 4)))))

(deftest summary-span-test
  (let [es [(assoc (tfile "/a.clj") :at 0) (assoc (form "(+ 1 2)" "user") :at (* 3 24 hour))]
        now (+ (* 3 24 hour) (* 14 hour))
        near [(assoc (tfile "/a.clj") :at (+ (* 3 24 hour) hour)) (assoc (form "(+ 1 2)" "user") :at (* 3 24 hour))]]
    (is (= "from 3 days ago to 14 hours ago" (:when (replay/summary es now))))
    (is (= "13 hours ago" (:when (replay/summary near now))))
    (is (= {:files 1 :defs 0 :others 1 :dialect "Jolt"} (dissoc (replay/summary es now) :when)))
    (is (nil? (replay/summary [] now)))))
