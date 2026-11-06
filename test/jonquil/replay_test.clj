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
    (spit p (pr-str {:version 1 :current [{:kind :file :path "/a.clj" :at 1} "junk" {:kind :zap}]}))
    (let [r (replay/load-record p)]
      (is (not (contains? r :warning)))
      (is (= [{:kind :file :path "/a.clj" :at 1}] (:current (:record r)))))))

(deftest load-drops-entries-without-a-time-test
  (let [p (str (dir!) "/rec.edn")]
    (spit p (pr-str {:version 1
                     :previous [{:kind :form :text "(+ 1 2)" :ns "user"}
                                {:kind :file :path "/a.clj" :at "yesterday"}
                                {:kind :file :path "/b.clj" :at 5}]}))
    (is (= [{:kind :file :path "/b.clj" :at 5}] (:previous (:record (replay/load-record p)))))))

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
    (is (= "rwx------" (perms rd)))))

(deftest save-record-nil-path-test
  (is (string? (replay/save-record! nil (replay/empty-record)))))

;; --- entries, slots, compaction, script ------------------------------------

(defn- ents [m] (mapv #(dissoc % :key) (replay/entries (merge {:at 1 :dialect "Jolt"} m))))

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
    (is (= [(form "(def x 1)" "foo")] es))))

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
              "#_(in-ns 'app.core)\n"
              "#_(start-server)")
         (replay/script script-es {:root "/p" :now (+ 3000 (* 14 hour)) :dialect "Jolt"}))))

(deftest script-shape-test
  (let [now (+ 3000 (* 14 hour))
        s (replay/script [(form "(a)" "x" :at 1) (form "(b)" "y" :at 2) (form "(c)" "y" :at 3)
                          (tfile "/p/a.clj") (tfile "/p/b.clj")]
                         {:root nil :now now :dialect "Jolt"})]
    (is (str/starts-with? s ";; Last session with no folder (Jolt, 14 hours ago), 2 files,\n;; 0 definitions, 3 other forms."))
    (is (not (str/includes? s "Definitions from the console")))
    (is (str/includes? s "#_(in-ns 'x)\n#_(a)\n#_(in-ns 'y)\n#_(b)\n#_(c)"))))

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

(deftest entries-selection-in-file-keeps-source-file-test
  (is (= [(form "(def a 1)" "a" :source-file "/p/a.clj")]
         (ents {:source :selection :path "/p/a.clj" :ns "a" :code "(def a 1)"}))))

(deftest compact-ignores-name-metadata-test
  (doseq [meta ["^:private" "^:dynamic" "^{:doc \"x} {y\"}" "^String ^:private"]]
    (let [c (replay/compact [(form "(def x 1)" "user")
                             (form (str "(def " meta " x 2)") "user")])]
      (is (= [(str "(def " meta " x 2)")] (map :text (:defs c))) meta))))

(deftest entries-ns-metadata-test
  (let [es (ents {:source :repl :ns "user" :code "(ns ^:foo a) (def x 1) (in-ns 'b) (def y 1)"})]
    (is (= ["user" "a" "b"] (map :ns es)))
    (is (= ["(ns ^:foo a)" "(def x 1)" "(def y 1)"] (map :text es)))))

(deftest entries-non-symbol-in-ns-keeps-ns-test
  (let [es (ents {:source :repl :ns "user" :code "(in-ns (symbol x)) (in-ns b) (def y 1)"})]
    (is (= ["user" "user" "user"] (map :ns es)))))

(deftest load-file-escaped-path-test
  (let [es (ents {:source :repl :ns "user" :code "(load-file \"/p/a\\\"b\\\\c.clj\")"})]
    (is (= [(tfile "/p/a\"b\\c.clj")] es))
    (is (str/includes? (replay/script es {:root "/p" :now 5000 :dialect "Jolt"})
                       "(load-file \"/p/a\\\"b\\\\c.clj\")"))))

;; --- final review fixes ------------------------------------------------------

(deftest compact-setup-per-ns-test
  (let [c (replay/compact [(form "(require '[clojure.string :as str])" "a")
                           (form "(def x (str/join [1]))" "a")
                           (form "(require '[clojure.string :as str])" "b")
                           (form "(def y (str/join [2]))" "b")])]
    (is (= [["a" "(require '[clojure.string :as str])"] ["a" "(def x (str/join [1]))"]
            ["b" "(require '[clojure.string :as str])"] ["b" "(def y (str/join [2]))"]]
           (map (juxt :ns :text) (:defs c))))))

(deftest compact-setup-keeps-first-position-test
  (let [c (replay/compact [(form "(require '[clojure.set :as set])" "user")
                           (form "(def z (set/union #{1} #{2}))" "user")
                           (form "(require '[clojure.set :as set])" "user")])]
    (is (= ["(require '[clojure.set :as set])" "(def z (set/union #{1} #{2}))"]
           (map :text (:defs c))))))

(deftest script-ns-then-defs-in-another-ns-test
  (let [es (concat (replay/entries {:source :repl :ns "user" :at 1000 :dialect "Jolt"
                                    :code "(ns a) (require '[clojure.string :as str]) (def x (str/join [1]))"})
                   (replay/entries {:source :repl :ns "a" :at 2000 :dialect "Jolt"
                                    :code "(ns b) (require '[clojure.string :as str]) (def y (str/join [2]))"}))
        s (replay/script es {:root "/p" :now 5000 :dialect "Jolt"})]
    (is (str/ends-with? s (str ";; Definitions from the console\n"
                               "(in-ns 'user)\n(ns a)\n"
                               "(in-ns 'a)\n(require '[clojure.string :as str])\n(def x (str/join [1]))\n(ns b)\n"
                               "(in-ns 'b)\n(require '[clojure.string :as str])\n(def y (str/join [2]))")))))

(defn- section-of
  "The script s without its Other forms section."
  [s]
  (first (str/split s #"\n\n;; Other forms, skipped")))

(defn- counts-line [s] (second (str/split-lines s)))

(deftest script-round-trip-test
  (let [es [(assoc (tfile "/p/src/app/core.clj") :at 1000)
            (form "(ns a)" "user" :at 1100)
            (form "(require '[clojure.string :as str])" "a" :at 1200)
            (form "(def x (str/join [1]))" "a" :at 1300)
            (form "(ns b)" "a" :at 1400)
            (form "(require '[clojure.string :as str])" "b" :at 1500)
            (form "(defn y [] (str/join [2]))" "b" :at 1600)
            (form "(swap! db inc)" "a" :at 1700)
            (form "(y)" "b" :at 1800)]
        opts {:root "/p" :now 5000 :dialect "Jolt"}
        s1 (replay/script es opts)
        again (fn [s] (replay/script (replay/entries {:source :run :path nil :code s :ns "user"
                                                      :at 1800 :dialect "Jolt"})
                                     opts))
        s2 (again s1)
        s3 (again s2)
        c1 (replay/compact es)
        c2 (replay/compact (replay/entries {:source :run :path nil :code s1 :ns "user"
                                            :at 1800 :dialect "Jolt"}))]
    (is (str/includes? s1 ";; Other forms, skipped"))
    ;; the skipped forms stay skipped, so they don't come back; the rest is the same
    (is (= (section-of s1) (str/replace-first (section-of s2) (counts-line s2) (counts-line s1))))
    (is (= ";; 6 definitions, 0 other forms. Nothing has run. Edit, then Run (Cmd/Ctrl+R)." (counts-line s2)))
    (is (= s2 s3))
    (is (= (:files c1) (:files c2)))
    (is (= (map (juxt :ns :text) (:defs c1)) (map (juxt :ns :text) (:defs c2))))
    (is (empty? (:others c2)))))

(deftest entries-carry-key-test
  (is (= [["defn" "f"] ["defmethod" "area" ":sq"] :setup :setup :other]
         (map :key (replay/entries {:source :repl :ns "user" :at 1 :dialect "Jolt"
                                    :code "(defn ^:private f [] 1) (defmethod area :sq [s] 1) (ns a) (require 'x) (+ 1 2)"})))))

(deftest compact-same-with-or-without-key-test
  (let [es (replay/entries {:source :repl :ns "user" :at 1 :dialect "Jolt"
                            :code (str "(def x 1) (def x 2) (require 'a) (defn f [] 1) (require 'a) "
                                       "(defmethod m :a [_] 1) (defmethod m :b [_] 2) (+ 1 2) (+ 1 2)")})
        strip (fn [c] (-> c (update :defs #(mapv (fn [e] (dissoc e :key)) %))
                          (update :others #(mapv (fn [e] (dissoc e :key)) %))))]
    (is (every? :key es))
    (is (= (strip (replay/compact es)) (replay/compact (map #(dissoc % :key) es))))))

(deftest summary-takes-compact-test
  (let [es [(tfile "/a.clj") (form "(+ 1 2)" "user")]
        c {:files ["/x" "/y"] :defs [] :others []}]
    (is (= 2 (:files (replay/summary es 5000 c))))))

;; --- carry-skipped -------------------------------------------------------

(def ^:private carry-prev
  [(form "(def x 1)" "app.core" :at 100)
   (form "(start-server)" "app.core" :at 200)
   (form "(swap! db inc)" "user" :at 300)])

(def ^:private carry-record {:version 1 :previous carry-prev :current [] :history []})

(deftest carry-skipped-keeps-skipped-form-test
  (let [text "(def x 1)\n#_(start-server)\n#_(swap! db inc)\n"
        r (replay/carry-skipped carry-record text)]
    (is (= ["(start-server)" "(swap! db inc)"] (map :text (:current r))))
    (is (= [["app.core" 200] ["user" 300]] (map (juxt :ns :at) (:current r))))
    (is (= carry-prev (:previous r)))))

(deftest carry-skipped-drops-deleted-form-test
  (let [r (replay/carry-skipped carry-record "(def x 1)\n#_(swap! db inc)\n")]
    (is (= ["(swap! db inc)"] (map :text (:current r))))))

(deftest carry-skipped-drops-unskipped-form-test
  (let [r (replay/carry-skipped carry-record "(def x 1)\n(start-server)\n#_(swap! db inc)\n")]
    (is (= ["(swap! db inc)"] (map :text (:current r))))))

(deftest carry-skipped-blank-text-test
  (doseq [t [nil "" "  \n"]]
    (is (= carry-record (replay/carry-skipped carry-record t)) (pr-str t))))

(deftest carry-skipped-extends-current-test
  (let [mine (form "(def z 1)" "user" :at 50)
        r (replay/carry-skipped (assoc carry-record :current [mine]) "#_(start-server)")]
    (is (= ["(def z 1)" "(start-server)"] (map :text (:current r))))))

(deftest carry-skipped-round-trip-test
  (let [opts {:root "/p" :now 5000 :dialect "Jolt"}
        s1 (replay/script carry-prev opts)
        run-es (replay/entries {:source :run :path nil :code s1 :ns "user" :at 900 :dialect "Jolt"})
        r (-> carry-record
              (replay/carry-skipped s1)
              (replay/add-entries run-es)
              replay/forget)
        s2 (replay/script (:current r) opts)
        others (fn [s] (last (str/split s #"\n\n;; Other forms, skipped")))]
    (is (empty? (:previous r)))
    (is (= (others s1) (others s2)))
    (is (str/includes? s2 "#_(start-server)"))))

(defn- carried-texts [others text]
  (map :text (:current (replay/carry-skipped
                        {:version 1 :previous (mapv #(form % "user" :at 1) others) :current [] :history []}
                        text))))

(deftest carry-skipped-token-boundary-test
  (is (empty? (carried-texts ["x"] "#_xs")))
  (is (empty? (carried-texts ["42"] "#_421")))
  (is (= ["xs"] (carried-texts ["xs"] "#_xs")))
  (is (= ["42"] (carried-texts ["42"] "(a)\n#_42\n(b)"))))

(deftest carry-skipped-top-level-only-test
  (is (empty? (carried-texts ["(f)"] "(foo #_(f))")))
  (is (empty? (carried-texts ["(f)"] ";; #_(f)\n(a)")))
  (is (empty? (carried-texts ["(f)"] "(a) ; #_(f)")))
  (is (empty? (carried-texts ["(f)"] "(def s \"#_(f)\")")))
  (is (= ["(f)"] (carried-texts ["(f)"] "(a) ; note\n#_(f)\n")))
  (is (= ["(f)"] (carried-texts ["(f)"] "(foo #_(f))\n#_(f)"))))

(deftest carry-skipped-nested-in-discard-test
  (doseq [t ["#_(foo #_(f))" "(a) #_(foo #_(f)) (b)" "#_\"#_(f)\"" "#_[1 #_(f)]" "(a) #_(g\n  #_(f))"
             "(comment #_(f))"]]
    (is (empty? (carried-texts ["(f)"] t)) (pr-str t))))

(deftest carry-skipped-positive-shapes-test
  (doseq [t ["(a)\n#_(f)\n(b)" "(def s \";\") #_(f)" "(def s \"line one\n#_(g)\nline two\")\n#_(f)"
             "#_(f)\n#_(f)" "#_ (f)" "#_\n(f)" "#_ #_ (g) (f)" "(a) ; c\n#_(f) ; #_(g)"]]
    (is (= ["(f)"] (carried-texts ["(f)"] t)) (pr-str t)))
  (is (= ["(g)" "(f)"] (carried-texts ["(g)" "(f)"] "#_ #_ (g) (f)"))))

(deftest carry-skipped-real-script-test
  (let [others (map #(str "(swap! db conj " % ")") (range 5))
        es (concat [(form "(def a 1)" "user" :at 1) (form "(ns b)" "user" :at 2) (form "(def c 2)" "b" :at 3)]
                   (map-indexed (fn [i t] (form t (if (even? i) "user" "b") :at (+ 10 i))) others))
        script (replay/script es {:root "/p" :now 5000 :dialect "Jolt"})
        r (replay/carry-skipped {:version 1 :previous (vec es) :current [] :history []} script)]
    (is (= others (map :text (:current r))))))

(deftest carry-skipped-perf-test
  (let [es (mapv #(form (str "(swap! db conj " % ")") "user" :at %) (range 2000))
        script (replay/script es {:root "/p" :now 5000 :dialect "Jolt"})
        rec {:version 1 :previous es :current [] :history []}
        t0 (System/nanoTime)
        r (replay/carry-skipped rec script)
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (is (= 2000 (count (:current r))))
    (is (< ms 1500))))
