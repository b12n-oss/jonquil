(ns gtk-selftest-cljs.rows
  "Rows for the ClojureScript window: a real window on a throwaway shadow-cljs
  project whose .shadow-cljs/nrepl.port names a fake shadow server running in
  this process (dev/fake_shadow_nrepl.clj). A second throwaway project, picked
  by .jonquil.edn and served by a fake with no JS runtime, takes the last
  rows. Nothing here reads or connects to a real shadow-cljs server."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fake-shadow-nrepl :as fake]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.cljs :as cljs]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(def fixture-run1
  "(ns probe.run1)\n(def a 1)\n(defn f [] a)\n(f)\n")

(def fixture-run2
  "(ns probe.run2)\n(def a 1)\n(throw (js/Error. \"boom\"))\n(def b 2)\n")

(def ^:private attached-label "ClojureScript (shadow-cljs) (attached)")

(def ^:private not-running-label "ClojureScript (shadow-cljs) (not running)")

;; Both projects and their fakes, stashed so rows and after-close-checks!
;; (which runs after main/start! returns) reach them.
(defonce ^:private fixture* (atom nil))

(defn- temp-project-dir [tag]
  (io/file (System/getProperty "java.io.tmpdir")
           (str "jonquil-selftest-cljs-" tag "-" (System/currentTimeMillis) "-" (random-uuid))))

(defn- write-project!
  "A project directory holding shadow-cljs.edn (builds-text), the port file
  for srv and src/demo/core.cljs; {:dir :file :port-text}, paths canonical."
  [tag builds-text srv source]
  (let [d (temp-project-dir tag)
        f (io/file d "src/demo/core.cljs")
        port-text (str (:port srv) "\n")]
    (.mkdirs (.getParentFile f))
    (.mkdirs (io/file d ".shadow-cljs"))
    (spit (io/file d "shadow-cljs.edn") builds-text)
    (spit (io/file d ".shadow-cljs/nrepl.port") port-text)
    (spit f source)
    {:dir (.getCanonicalPath d) :file (.getCanonicalPath f) :port-text port-text}))

(defn fixture!
  "Starts both fakes and writes both projects. The first has one build, so
  the evaluator derives it from shadow-cljs.edn alone. The second has two,
  so only its .jonquil.edn {:cljs {:shadow-build :app}} names the one the
  fake accepts, and its fake answers every eval with the no-runtime error.
  {:main {:dir :file :server ..} :other {...}}."
  []
  (let [srv-a (fake/start! {:build :app})
        srv-b (fake/start! {:build :app :no-runtime? true})
        a (write-project! "a" "{:builds {:app {:target :node-script :main demo.core/main}}}\n" srv-a fixture-run1)
        b (write-project! "b" "{:builds {:app {:target :node-script :main demo.core/main}\n          :other {:target :browser}}}\n"
                          srv-b "(ns demo.other)\n")]
    (spit (io/file (:dir b) ".jonquil.edn") "{:cljs {:shadow-build :app}}\n")
    (reset! fixture* {:main (assoc a :server srv-a) :other (assoc b :server srv-b)})))

(defn evaluator [ui] ((:evaluator* ui)))

(defn idle? [ui] (not (ev/evaluating? (evaluator ui))))

(defn- srv [k] (:server (get @fixture* k)))

(defn run-and-await
  "support/run-and-await's contract, idle read through the evaluator."
  [ui code deadline-ms expect-fn]
  (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                       (editor/set-text! (:editor ui) code)
                       ((:run!* ui))
                       m))
        ok (await-until deadline-ms 20 #(and (idle? ui) (expect-fn (support/since ui mark))))]
    [ok mark]))

(defn run-selection-and-await
  "Sets the editor to text, puts the caret at the start of needle, runs
  Cmd/Ctrl+E's own fn, then parks like run-and-await. [ok mark]."
  [ui text needle deadline-ms expect-fn]
  (let [mark (on-gtk (let [m (support/out-end-offset ui)
                           ed (:editor ui)]
                       (editor/set-text! ed text)
                       (support/place-cursor! (:buf ed) (str/index-of text needle))
                       ((:run-selection!* ui))
                       m))
        ok (await-until deadline-ms 20 #(and (idle? ui) (expect-fn (support/since ui mark))))]
    [ok mark]))

(defn- label [ui] (g/label-text (:dialect-label ui)))

(defn- evals
  "The code strings of every eval request srv has seen, in order."
  [server]
  (mapv #(get % "code") (filter #(= "eval" (get % "op")) (fake/requests server))))

;; --- rows --------------------------------------------------------------------

(defn row-cljs-starts-not-running
  "The window opened a .cljs file in a shadow-cljs project: an attach
  evaluator, not connected, the label says so, and the console says it's
  experimental. Nothing has talked to the server yet."
  [ui]
  (go-row :cljs-starts-not-running support/check
    (let [said? (await-until 2000 20 #(str/includes? (support/since ui 0) cljs/experimental-message))]
      (on-gtk (support/check :cljs-starts-not-running
                             (and said?
                                  (instance? jonquil.nrepl.shadow.Shadow (evaluator ui))
                                  (= not-running-label (label ui))
                                  (empty? (fake/ops-seen (srv :main))))
                             {:said? said? :label (label ui) :ops (fake/ops-seen (srv :main))})))))

(defn row-cljs-run-connects-and-selects
  "The first Run connects, selects the build the one-build shadow-cljs.edn
  names, evaluates the whole buffer, and prints the summary; the label turns
  to attached."
  [ui]
  (go-row :cljs-run-connects-and-selects support/check
    (let [[ok? mark] (run-and-await ui fixture-run1 10000 #(str/includes? % "; Ran probe.run1 (4 forms)"))
          attached? (await-until 2000 20 #(= attached-label (label ui)))
          codes (evals (srv :main))]
      (on-gtk (let [t (support/since ui mark)]
                (support/check :cljs-run-connects-and-selects
                               (and ok? attached?
                                    (str/includes? t "; Connecting to the shadow-cljs nREPL server on port ")
                                    (= [(cljs/select-code :app) fixture-run1] codes))
                               {:ok? ok? :attached? attached? :label (label ui) :codes codes :text t}))))))

(defn row-cljs-run-error-summary
  "A Run whose third form throws: the error text streams to the console, the
  later form still runs, and the summary counts the one error."
  [ui]
  (go-row :cljs-run-error-summary support/check
    (let [[ok? mark] (run-and-await ui fixture-run2 10000
                                    #(str/includes? % "; Ran probe.run2 (4 forms, 1 error; an error doesn't stop the rest of the file)"))]
      (on-gtk (let [t (support/since ui mark)]
                (support/check :cljs-run-error-summary
                               (and ok? (str/includes? t "boom"))
                               {:ok? ok? :text t}))))))

(defn row-cljs-selection-tracks-buffer-ns
  "Run Selection sends the caret's line in the namespace of the buffer's own
  ns form, and a different ns form changes it."
  [ui]
  (go-row :cljs-selection-tracks-buffer-ns support/check
    (let [n (count (fake/requests (srv :main)))
          [ok1 m1] (run-selection-and-await ui "(ns probe.sel)\n\n(inc 1)\n" "(inc 1)" 5000 #(str/includes? % "=> 42"))
          [ok2 m2] (run-selection-and-await ui "(ns probe.other)\n\n(inc 1)\n" "(inc 1)" 5000 #(str/includes? % "=> 42"))
          sent (mapv (fn [m] [(str/trim (get m "code")) (get m "ns")])
                     (filter #(= "eval" (get % "op")) (drop n (fake/requests (srv :main)))))]
      (on-gtk (support/check :cljs-selection-tracks-buffer-ns
                             (and ok1 ok2 (= [["(inc 1)" "probe.sel"] ["(inc 1)" "probe.other"]] sent))
                             {:ok1 ok1 :ok2 ok2 :sent sent :first (support/since ui m1) :second (support/since ui m2)})))))

(defn row-cljs-no-runtime-notice
  "Opening a file of the second project switches the evaluator. Its
  .jonquil.edn names the build among two, and its server has no JS runtime:
  Run says so in the console's :system style, never the error style."
  [ui]
  (go-row :cljs-no-runtime-notice support/check
    (let [file (:file (:other @fixture*))
          _ (on-gtk ((:open-path! ui) file))
          [ok? mark] (run-and-await ui "(ns demo.other)\n(+ 1 2)\n" 10000 #(str/includes? % cljs/no-runtime-message))
          codes (evals (srv :other))]
      (on-gtk (let [cv (:console ui)
                    t (support/since ui mark)
                    at (+ mark (or (str/index-of t cljs/no-runtime-message) 0))
                    system? (g/has-tag-at? (:buf cv) at (get (:tags cv) :system))
                    error? (g/has-tag-at? (:buf cv) at (get (:tags cv) :error))]
                (support/check :cljs-no-runtime-notice
                               (and ok? system? (not error?)
                                    (= (cljs/select-code :app) (first codes)))
                               {:ok? ok? :system system? :error error? :codes codes :text t}))))))

(defn row-cljs-idle-stop-detaches
  "Stop pressed while connected but idle detaches and prints the detached
  line exactly once; the label goes back to not running, and the server
  never got interrupt or close."
  [ui]
  (go-row :cljs-idle-stop-detaches support/check
    (let [attached? (await-until 2000 20 #(= attached-label (label ui)))
          mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk ((:stop!* ui)))
          not-running? (await-until 2000 20 #(= not-running-label (label ui)))
          text (on-gtk (support/since ui mark))
          detached-count (count (re-seq (re-pattern (java.util.regex.Pattern/quote cljs/detached-message)) text))
          ops (fake/ops-seen (srv :other))]
      (on-gtk (support/check :cljs-idle-stop-detaches
                             (and attached? not-running? (= 1 detached-count)
                                  (not-any? #{"interrupt" "close"} ops))
                             {:attached? attached? :not-running? not-running?
                              :count detached-count :ops ops :text text})))))

(defn row-cljs-close
  "The deliberately LAST row: fires the app's quit action. after-close-checks!
  records the result once the GTK loop is gone."
  [ui]
  (go-row :cljs-close support/check
    (on-gtk (editor/set-text! (:editor ui) "")
            ((:activate-quit-action!* ui)))))

(defn after-close-checks!
  "Called by -main once main/start! returns. Neither server got interrupt or
  close, and both port files are byte for byte what fixture! wrote. Then
  stops both fakes and deletes both projects."
  []
  (doseq [k [:main :other]]
    (let [{:keys [dir server port-text]} (get @fixture* k)
          port-file (io/file dir ".shadow-cljs/nrepl.port")
          ops (fake/ops-seen server)]
      (support/check (keyword (str "cljs-" (name k) "-never-interrupt-or-close"))
                     (not-any? #{"interrupt" "close"} ops) {:ops ops})
      (support/check (keyword (str "cljs-" (name k) "-port-file-untouched"))
                     (and (.exists port-file) (= port-text (slurp port-file)))
                     {:port-file (when (.exists port-file) (slurp port-file))})
      (fake/stop! server)
      (doseq [x (reverse (file-seq (io/file dir)))] (.delete ^java.io.File x)))))
