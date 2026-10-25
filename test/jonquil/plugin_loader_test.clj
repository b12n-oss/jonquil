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
