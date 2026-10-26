(ns jonquil.plugin-loader-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.plugin-loader :as loader]
            [jonquil.plugin-registry :as reg]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- source
  "A plugin file for id: one :edit command bound to Cmd+Alt+key, plus extra
  forms inside the defplugin map (more commands or bindings)."
  [id & {:keys [key extra] :or {key "j" extra ""}}]
  (str "(ns jonquil.plugins." id "\n  (:require [jonquil.plugin :as p]))\n\n"
       "(p/defplugin\n"
       "  {:id :" id "\n"
       "   :doc \"A test plugin.\"\n"
       "   :effects #{:console}\n"
       "   :commands [{:id :" id "/wrap :doc \"Wrap it.\" :scope :editor\n"
       "               :edit (fn [s] (p/wrap-form s \"(when \" \")\"))}" extra "]\n"
       "   :bindings [{:key \"" key "\" :mods #{:cmd :alt} :command :" id "/wrap}]})\n"))

(defn- dir-with [files]
  (let [d (temp-dirs/temp-dir! "jonquil-plugins-test-")]
    (doseq [[n text] files] (spit (io/file d n) text))
    (str d)))

;; Other.clj, not Wrap.clj: macOS file names ignore case, so Wrap.clj would
;; overwrite wrap.clj
(deftest plugin-files-test
  (let [d (dir-with [["wrap.clj" "x"] ["b-two.clj" "x"] ["Other.clj" "x"] [".hidden.clj" "x"] ["notes.txt" "x"]])
        r (loader/plugin-files d)]
    (is (= ["b-two" "wrap"] (map first (:files r))))
    (is (= #{"Other.clj" ".hidden.clj"} (set (:skipped r)))))
  (is (= {:files [] :skipped []} (loader/plugin-files "/no/such/jonquil/plugins/dir"))))

(defn- load-dir [d] (loader/load-all {:dir d :os "Linux"}))

(deftest load-all-isolates-a-broken-file-test
  (let [d (dir-with [["p3l-good.clj" (source "p3l-good")]
                     ["p3l-bad.clj" "(ns jonquil.plugins.p3l-bad\n  (:require [jonquil.plugin :as p]))\n(p/defplugin {:id :p3l-bad\n"]])
        {:keys [registry lines]} (load-dir d)]
    (is (= #{:p3l-good} (set (keys (:plugins registry)))))
    (is (some #(= "plugin p3l-good loaded (1 command)" %) lines) (pr-str lines))
    (is (some #(str/starts-with? % "plugin p3l-bad skipped: line ") lines) (pr-str lines))
    (is (= :user (get-in registry [:plugins :p3l-good :layer])))))

(deftest load-all-later-names-earlier-test
  (let [d (dir-with [["p3l-a.clj" (source "p3l-a")]
                     ["p3l-b.clj" (str/replace (source "p3l-b" :key "k")
                                               ":bindings [{:key \"k\" :mods #{:cmd :alt} :command :p3l-b/wrap}]"
                                               ":bindings [{:key \"k\" :mods #{:cmd :alt} :command :p3l-a/wrap}]")]])
        {:keys [registry lines]} (load-dir d)]
    (is (= #{:p3l-a :p3l-b} (set (keys (:plugins registry)))) (pr-str lines))
    (is (some #(= :p3l-a/wrap (:command %)) (reg/bindings registry :editor)))))

(deftest load-all-run-commands-installed-test
  (let [d (dir-with [["p3l-run.clj" (source "p3l-run" :extra "\n              {:id :p3l-run/say :doc \"Say.\" :scope :window :run (fn [_] nil)}")]])
        {:keys [registry lines]} (load-dir d)]
    (is (= ["plugin p3l-run loaded (2 commands)"] lines))
    (is (contains? (reg/commands registry) :p3l-run/say))))

(deftest skipped-names-are-lines-test
  (let [{:keys [lines]} (load-dir (dir-with [["Nope.clj" "x"]]))]
    (is (= ["plugins/Nope.clj: not a plugin id, skipped"] lines))))

(deftest plugin-flags-test
  (is (= {:args ["a.clj"] :plugins? false} (loader/plugin-flags ["--no-plugins" "a.clj"] {})))
  (is (false? (:plugins? (loader/plugin-flags ["a.clj"] {"JONQUIL_NO_PLUGINS" "1"}))))
  (is (= {:args ["a.clj"] :plugins? true} (loader/plugin-flags ["a.clj"] {}))))

(deftest plugins-dir-test
  (is (= "/x/jonquil/plugins" (loader/plugins-dir "/x/jonquil/settings.edn"))))

(deftest same-chord-warns-test
  ;; review I2: a later plugin on an earlier one's chord is warned about
  (let [d (dir-with [["p3l-x.clj" (source "p3l-x")] ["p3l-y.clj" (source "p3l-y")]])
        {:keys [lines]} (load-dir d)]
    (is (some #(and (str/starts-with? % "plugin p3l-y: ") (str/includes? % "never fires")
                    (str/includes? % "plugin p3l-x")) lines)
        (pr-str lines))))

(deftest one-command-is-singular-test
  (let [{:keys [lines]} (load-dir (dir-with [["p3l-one.clj" (source "p3l-one")]]))]
    (is (some #(= "plugin p3l-one loaded (1 command)" %) lines) (pr-str lines))))

;; --- plugins P4: reload ----------------------------------------------------

(defn- reload [d reg & {:as more}]
  (loader/reload (merge {:dir d :os "Linux" :registry reg :disabled #{}} more)))

(defn- write! [d n text] (spit (io/file d n) text))

(deftest unchanged-is-left-alone-test
  (let [d (dir-with [["p4l-a.clj" (source "p4l-a")]])
        {reg :registry} (load-dir d)
        r (reload d reg)]
    (is (= [] (:lines r)))
    (is (identical? (get-in reg [:plugins :p4l-a]) (get-in (:registry r) [:plugins :p4l-a])))))

(deftest changed-reload-swaps-test
  (let [d (dir-with [["p4l-b.clj" (source "p4l-b")]])
        {reg :registry} (load-dir d)
        _ (write! d "p4l-b.clj" (source "p4l-b" :key "k"))
        r (reload d reg)
        keys-bound (set (map :key (reg/bindings (:registry r) :editor)))]
    (is (= #{"k"} keys-bound) (pr-str (:lines r)))))

(def ^:private v1
  (str "(ns jonquil.plugins.p4l-m\n  (:require [jonquil.plugin :as p]))\n(def marker :v1)\n"
       "(p/defplugin {:id :p4l-m :doc \"d\" :effects #{} :commands [] :bindings []})\n"))

(def ^:private v2-broken
  (str "(ns jonquil.plugins.p4l-m\n  (:require [jonquil.plugin :as p]))\n(def marker :v2)\n"
       "(p/defplugin {:id :p4l-m\n"))

(deftest failed-reload-restores-the-old-version-test
  (let [d (dir-with [["p4l-m.clj" v1]])
        {reg :registry} (load-dir d)
        _ (write! d "p4l-m.clj" v2-broken)
        r (reload d reg)]
    (is (= v1 (get-in (:registry r) [:plugins :p4l-m :source])))
    (is (= :v1 @(ns-resolve 'jonquil.plugins.p4l-m 'marker)))
    (is (some #(str/includes? % "skipped: line") (:lines r)) (pr-str (:lines r)))
    (is (some #(str/includes? % "the previous version stays live") (:lines r)))))

(deftest reload-does-not-shadow-itself-test
  (let [d (dir-with [["p4l-s.clj" (source "p4l-s")]])
        {reg :registry} (load-dir d)
        _ (write! d "p4l-s.clj" (str/replace (source "p4l-s") "Wrap it." "Wrap it, again."))
        r (reload d reg)]
    (is (not (some #(str/includes? % "never fires") (:lines r))) (pr-str (:lines r)))
    (is (= "Wrap it, again." (:doc (get (reg/commands (:registry r)) :p4l-s/wrap))))))

(deftest later-plugins-are-not-earlier-on-reload-test
  ;; a reloaded p4l-a is checked against the plugins before it, not p4l-z
  (let [d (dir-with [["p4l-a.clj" (source "p4l-a")] ["p4l-z.clj" (source "p4l-z" :key "j")]])
        {reg :registry} (load-dir d)
        _ (write! d "p4l-a.clj" (str/replace (source "p4l-a") "Wrap it." "Wrap it, two."))
        r (reload d reg)]
    (is (not (some #(and (str/starts-with? % "plugin p4l-a") (str/includes? % "never fires")) (:lines r)))
        (pr-str (:lines r)))))

(deftest removed-and-disabled-test
  (let [d (dir-with [["p4l-r.clj" (source "p4l-r")] ["p4l-d.clj" (source "p4l-d" :key "k")]])
        {reg :registry} (load-dir d)
        _ (io/delete-file (io/file d "p4l-r.clj"))
        r (reload d reg :disabled #{:p4l-d :p4l-never})]
    (is (= #{} (set (keys (:plugins (:registry r))))))
    (is (some #(= "plugin p4l-r removed: its file is gone" %) (:lines r)) (pr-str (:lines r)))
    (is (some #(= "plugin p4l-d is disabled (settings.edn :plugins :disabled)" %) (:lines r)))
    (is (not (some #(str/includes? % "p4l-never") (:lines r))))))

(deftest only-reloads-one-test
  (let [d (dir-with [["p4l-x.clj" (source "p4l-x")] ["p4l-y.clj" (source "p4l-y" :key "k")]])
        {reg :registry} (load-dir d)
        _ (write! d "p4l-x.clj" (source "p4l-x" :key "l"))
        _ (write! d "p4l-y.clj" (source "p4l-y" :key "m"))
        r (reload d reg :only (str (io/file d "p4l-x.clj")))
        ks (set (map :key (reg/bindings (:registry r) :editor)))]
    (is (= #{"l" "k"} ks) (pr-str (:lines r)))))

(deftest plugin-file?-test
  (let [d (temp-dirs/temp-dir! "jonquil-plugins-pf-")
        link (str (temp-dirs/temp-dir! "jonquil-plugins-link-") "/plugins")]
    (java.nio.file.Files/createSymbolicLink (.toPath (io/file link)) (.toPath (io/file d))
                                            (make-array java.nio.file.attribute.FileAttribute 0))
    (is (true? (loader/plugin-file? d (str d "/wrap.clj"))))
    (is (false? (loader/plugin-file? d (str d "/sub/wrap.clj"))))
    (is (false? (loader/plugin-file? d (str d "/Wrap.clj"))))
    (is (false? (loader/plugin-file? d "/tmp/elsewhere/wrap.clj")))
    (is (true? (loader/plugin-file? d (str link "/wrap.clj"))))))

(deftest history-folder-is-not-a-plugin-test
  (let [d (dir-with [["wrap.clj" (source "wrap")]])]
    (io/make-parents (io/file d ".history" "wrap" "1.clj"))
    (spit (io/file d ".history" "wrap" "1.clj") (source "wrap"))
    (is (= {:files [["wrap" (str (io/file d "wrap.clj"))]] :skipped []} (loader/plugin-files d)))))

(deftest history-file-is-not-a-plugin-file-test
  (let [d (temp-dirs/temp-dir! "jonquil-plugins-pf-")]
    (is (false? (loader/plugin-file? d (str d "/.history/wrap/1.clj"))))))

(deftest loads-are-serial-test
  ;; two load steps on one agent: the second reads the registry the first
  ;; left, so an unchanged file gives it nothing to do (review focus 5)
  (let [slow (str "(ns jonquil.plugins.p4l-slow\n  (:require [jonquil.plugin :as p]))\n(Thread/sleep 300)\n"
                  "(p/defplugin {:id :p4l-slow :doc \"d\" :effects #{} :commands [] :bindings []})\n")
        d (dir-with [["p4l-slow.clj" slow]])
        live (atom (reg/empty-registry))
        a (agent nil)
        step (fn [_] (loader/load-step! live {:dir d :os "Linux" :disabled #{}}))]
    (send-off a step)
    (send-off a step)
    (is (true? (await-for 10000 a)))
    (is (= [] @a) "the second step found the file unchanged")
    (is (contains? (:plugins @live) :p4l-slow))))

(deftest load-step-never-throws-test
  (let [live (atom (reg/empty-registry))
        lines (with-redefs [loader/reload (fn [_] (throw (ex-info "kaboom" {})))]
                (loader/load-step! live {:dir "/nowhere" :os "Linux" :disabled #{}}))]
    (is (= ["loading plugins failed: kaboom"] lines))
    (is (= (reg/empty-registry) @live))))

;; --- review fixes: later plugins re-checked; the agent; settings warning ---

(deftest reload-rechecks-later-plugins-test
  ;; I1: p4l-a moves onto p4l-z's chord; p4l-z is told its binding now
  ;; never fires, as a fresh start would say
  (let [d (dir-with [["p4l-a.clj" (source "p4l-a")] ["p4l-z.clj" (source "p4l-z" :key "k")]])
        {reg :registry} (load-dir d)
        _ (write! d "p4l-a.clj" (source "p4l-a" :key "k"))
        r (reload d reg)]
    (is (some #(and (str/starts-with? % "plugin p4l-z") (str/includes? % "never fires")) (:lines r))
        (pr-str (:lines r)))))

(deftest reload-removes-a-dependent-that-now-fails-test
  ;; I2: p4l-c binds p4l-b's command; disabling p4l-b leaves p4l-c naming
  ;; an unregistered command, so p4l-c is removed and named, not kept dead
  (let [d (dir-with [["p4l-b.clj" (source "p4l-b")]
                     ["p4l-c.clj" (str/replace (source "p4l-c" :key "k")
                                               ":command :p4l-c/wrap}]})" ":command :p4l-b/wrap}]})")]])
        {reg :registry lines :lines} (load-dir d)
        _ (is (contains? (:plugins reg) :p4l-c) (pr-str lines))
        r (reload d reg :disabled #{:p4l-b})]
    (is (not (contains? (:plugins (:registry r)) :p4l-c)) (pr-str (:lines r)))
    (is (some #(and (str/starts-with? % "plugin p4l-c removed") (str/includes? % ":p4l-b/wrap")) (:lines r))
        (pr-str (:lines r)))))

(deftest load-agent-keeps-going-after-a-throw-test
  ;; I3: a throw in a step is reported, and later steps still run
  (let [errors (atom [])
        a (loader/load-agent #(swap! errors conj (ex-message %)))]
    (send-off a (fn [_] (throw (ex-info "kaboom" {}))))
    (send-off a (fn [_] [:ran]))
    (is (true? (await-for 5000 a)))
    (is (= [:ran] @a))
    (is (= ["kaboom"] @errors))))

(deftest load-step-prints-a-settings-warning-test
  ;; I4: a malformed :plugins :disabled is named, not ignored silently
  (let [d (temp-dirs/temp-dir! "jonquil-settings-pl-")
        sp (str d "/settings.edn")
        _ (spit sp "{:plugins {:disabled [:x]}}")
        lines (loader/load-step! (atom (reg/empty-registry)) {:settings-path sp :os "Linux"})]
    (is (some #(str/includes? % ":plugins :disabled") lines) (pr-str lines))))

;; --- review of the recheck --------------------------------------------------

(defn- binds [id target key]
  (str/replace (source id :key key) (str ":command :" id "/wrap}]})") (str ":command :" target "/wrap}]})")))

(deftest changed-dependent-is-rechecked-test
  ;; I-1: q-b binds q-a's command and q-c binds q-b's; disable q-a AND edit
  ;; q-c in one reload: q-c is removed too, and not reported loaded
  (let [d (dir-with [["q-a.clj" (source "q-a")] ["q-b.clj" (binds "q-b" "q-a" "k")]
                     ["q-c.clj" (binds "q-c" "q-b" "l")]])
        {reg :registry} (load-dir d)
        _ (write! d "q-c.clj" (str (binds "q-c" "q-b" "l") " "))
        r (reload d reg :disabled #{:q-a})]
    (is (= #{} (set (keys (:plugins (:registry r))))) (pr-str (:lines r)))
    (is (not (some #(str/starts-with? % "plugin q-c loaded") (:lines r))) (pr-str (:lines r)))))

(deftest a-fixed-dependency-brings-dependents-back-on-save-test
  ;; I-2: r-b binds r-a's command; save r-a broken (r-b removed), then save
  ;; it fixed: r-b comes back on that save, not only on Reload Plugins
  (let [d (dir-with [["r-a.clj" (source "r-a")] ["r-b.clj" (binds "r-b" "r-a" "k")]])
        {reg :registry} (load-dir d)
        a (str (io/file d "r-a.clj"))
        _ (write! d "r-a.clj" (str/replace (source "r-a") ":r-a/wrap" ":r-a/wrap2"))
        r1 (reload d reg :only a)
        _ (is (not (contains? (:plugins (:registry r1)) :r-b)) (pr-str (:lines r1)))
        _ (write! d "r-a.clj" (source "r-a"))
        r2 (reload d (:registry r1) :only a)]
    (is (contains? (:plugins (:registry r2)) :r-b) (pr-str (:lines r2)))))

(deftest unreadable-settings-are-named-test
  ;; I-3: settings.edn that doesn't parse: the disabled ids are lost, so say why
  (let [d (temp-dirs/temp-dir! "jonquil-settings-bad-")
        sp (str d "/settings.edn")
        _ (spit sp "{:plugins {:disabled #{:x}")
        lines (loader/load-step! (atom (reg/empty-registry)) {:settings-path sp :os "Linux"})]
    (is (some #(str/starts-with? % "settings: ") lines) (pr-str lines))))
