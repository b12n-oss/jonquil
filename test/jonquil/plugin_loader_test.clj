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

(deftest load-all-run-commands-named-test
  (let [d (dir-with [["p3l-run.clj" (source "p3l-run" :extra "\n              {:id :p3l-run/say :doc \"Say.\" :scope :window :run (fn [_ _] nil)}")]])
        {:keys [lines]} (load-dir d)]
    (is (some #(str/includes? % "p3l-run: its :run commands wait for effects") lines) (pr-str lines))))

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
