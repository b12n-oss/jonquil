(ns jonquil.gtk.parts.namespaces
  "UF8's Namespaces panel as a window part (the command and the label are
  jonquil.parts.namespaces/spec). It asks the window's runtime what is
  loaded, quietly (jonquil.describe-run's namespaces and vars snippets,
  no console entry), keeps where the person is in one state atom, and has
  jonquil.gtk.namespaces-pane show jonquil.namespaces-view's rows. Return
  steps into a namespace or jumps to a var, Shift+Return inspects a var's
  value, Backspace steps back. While the panel shows, it refreshes after
  each evaluation."
  (:require [jonquil.describe-run :as describe-run]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.namespaces-pane :as pane]
            [jonquil.gtk.parts.find-var :as find-var]
            [jonquil.namespaces-view :as view]
            [jonquil.test-run :as test-run]
            [jolt.ffi :as ffi]))

(def ^:private TITLE "; Namespaces")

(def ^:private AFTER-EVAL-MS
  "How long after an evaluation ends the panel refreshes: the after-eval
  hook runs before the evaluator says it's idle."
  150)

(defn initial-state
  "Where a new panel starts: the top level, nothing fetched yet."
  []
  {:level :top :top nil :vars nil :expanded? false :filter "" :selected nil :gen 0 :problem nil})

(defn- ns-level
  "The namespace stepped into, or nil at the top level."
  [state]
  (let [l (:level state)] (when (vector? l) (second l))))

(defn- top-view [{:keys [top expanded? filter selected problem]} own?]
  (let [rows (view/top-rows (or top {:namespaces []}) {:own? own? :expanded? expanded? :filter filter})]
    {:rows rows :crumb nil :selected (view/reselect rows selected)
     :footer (or problem (if top (view/top-footer top own?) "Loading…"))}))

(defn- ns-view [{:keys [vars filter selected problem] :as state}]
  (let [n (ns-level state)
        rows (view/var-rows (or vars {:vars []}) {:filter filter})
        i (view/reselect rows selected)
        row (some-> i rows)]
    {:rows rows :selected i
     :crumb (view/crumb n (+ (count (:vars vars)) (or (:dropped vars) 0)))
     :footer (or problem (cond (nil? vars) "Loading…"
                               (= :var (:kind row)) (view/var-footer n row)
                               :else "No vars."))}))

(defn- render!
  "Shows the state's level on the pane."
  [{:keys [state* own? show!]}]
  (let [st @state*]
    (show! (if (ns-level st) (ns-view st) (top-view st (own?))))))

(defn- apply-answer!
  "On the GTK thread: a query's answer [k x], asked under generation gen.
  An answer for a level the person has left is dropped. A namespace that
  is gone steps back to the top level and says so."
  [{:keys [state*] :as ctx} gen [k x]]
  (when (= gen (:gen @state*))
    (let [n (ns-level @state*)]
      (cond
        (= :problem k) (swap! state* assoc :problem x)
        (nil? n) (swap! state* assoc :top x :problem nil)
        (:found? x) (swap! state* assoc :vars x :problem nil)
        :else (swap! state* assoc :level :top :vars nil :selected n
                     :problem (str n " is no longer loaded."))))
    (render! ctx)))

(defn- refresh!
  "Asks the runtime for the level shown, fresh. A dialect the snippets
  can't ask says so in the footer."
  [{:keys [sel* state* query!] :as ctx}]
  (let [sel @sel*]
    (if-not (describe-run/supported? (:id (:profile sel)))
      (do (swap! state* assoc :problem (str TITLE " works with Jolt and JVM Clojure. This project is "
                                            (:name (:profile sel)) "."))
          (render! ctx))
      (let [st (swap! state* update :gen inc)
            gen (:gen st)
            n (ns-level st)]
        (query! (if n (describe-run/vars-snippet {:ns n}) (describe-run/namespaces-snippet))
                (fn [m]
                  (let [a (describe-run/query-answer m TITLE)]
                    (on-gui! (fn [] (apply-answer! ctx gen a))))))))))

(defn- go-to!
  "Moves to level, selecting selected there, with the filter cleared, then
  shows it and asks the runtime for it."
  [{:keys [state* set-filter! focus-list!] :as ctx} level selected]
  (swap! state* assoc :level level :vars nil :filter "" :selected selected :problem nil)
  (set-filter! "")
  (render! ctx)
  (focus-list!)
  (refresh! ctx))

(defn- step-back!
  "Backspace: from a namespace back to the top level, on that namespace."
  [{:keys [state*] :as ctx}]
  (when-let [n (ns-level @state*)]
    (go-to! ctx :top n)))

(defn- inspect-var!
  "Shift+Return on a var: why it can't be inspected on the status bar, or
  its value in the Inspector."
  [{:keys [set-status! inspect-text!]} n row]
  (if-let [why (view/inspect-refusal n row)]
    (set-status! why)
    (inspect-text! (str "(deref (var " n "/" (:name row) "))"))))

(defn- activate!
  "Return (shift? false) or Shift+Return on row."
  [{:keys [state* open-var!] :as ctx} {:keys [kind name file line] :as row} shift?]
  (let [n (ns-level @state*)]
    (case kind
      :ns (go-to! ctx [:ns name] nil)
      :group (do (swap! state* update :expanded? not) (render! ctx))
      :var (if shift?
             (inspect-var! ctx n row)
             (open-var! {:ns n :name name :file file :line line}))
      nil)))

(defn- select-row!
  "The selection moved to row: remembered, and a var's footer shown."
  [{:keys [state* report!]} row]
  (when row
    (swap! state* assoc :selected (:key row))
    (when-let [n (and (= :var (:kind row)) (nil? (:problem @state*)) (ns-level @state*))]
      (report! (view/var-footer n row)))))

(defn- filter!
  "The filter's text changed: the rows shown are narrowed."
  [{:keys [state*] :as ctx} text]
  (swap! state* assoc :filter text)
  (render! ctx))

(defn- after-eval!
  "The after-eval listener: on the GTK thread, a moment after the eval
  ends, refreshes while the panel shows and the runtime is idle."
  [{:keys [schedule! showing? evaluating?] :as ctx}]
  (on-gui! (fn [] (schedule! AFTER-EVAL-MS
                             (fn [] (when (and (showing?) (not (evaluating?))) (refresh! ctx)))))))

(defn- schedule!
  "Runs f once on the GTK loop after ms."
  [ms f]
  (g/g-timeout-add ms (callable (fn [_data] (f) 0) [:pointer] :int :on-throw 0) ffi/null))

(defn- own-fn
  "own? for the project now selected: the file found among its files."
  [sel* paths*]
  (fn []
    (let [root (:project-dir @sel*)
          paths (delay (find-var/project-paths {:files test-run/project-files :paths* paths*} root))]
      (fn [f] (some? (describe-run/project-hit @paths f))))))

(defn- window-ctx
  "The part's ctx over the pane p and the window's wctx."
  [p {:keys [ev* sel* right showing-panel set-status! mcp-query! inspect-text!] :as wctx}]
  (let [paths* (atom nil)
        fv (assoc (select-keys wctx [:sel* :set-status! :active-tab :source-ctx :open-file-line! :ask-lookup! :jump-to!])
                  :files test-run/project-files :paths* paths*)]
    {:sel* sel* :state* (atom (initial-state)) :set-status! set-status! :inspect-text! inspect-text!
     :query! (fn [code notify] (mcp-query! {:ev* ev*} code notify))
     :show! (fn [v] (pane/show! p v)) :report! (fn [s] (pane/report! p s))
     :set-filter! (fn [s] (pane/set-filter-text! p s)) :focus-list! (fn [] (pane/focus-list! p))
     :showing? (fn [] (= :namespaces (showing-panel right)))
     :evaluating? (fn [] (ev/evaluating? @ev*))
     :schedule! schedule! :own? (own-fn sel* paths*)
     :open-var! (fn [m] (find-var/open-var! fv m))}))

(defn- install
  "The Namespaces part for one window: builds the pane, wires its
  handlers and adds its after-eval listener."
  [{:keys [cv right show-right-panel! hide-right-panel! active-tab] :as wctx}]
  (let [p (pane/make-pane)
        ctx (window-ctx p wctx)
        show-fn (fn [] (show-right-panel! right :namespaces) (render! ctx) (pane/focus-list! p) (refresh! ctx))]
    (pane/set-handlers! p {:on-activate (fn [row shift?] (activate! ctx row shift?))
                           :on-back (fn [] (step-back! ctx))
                           :on-filter (fn [s] (filter! ctx s))
                           :on-refresh (fn [] (refresh! ctx))
                           :on-select (fn [row] (select-row! ctx row))
                           :on-close (fn [] (hide-right-panel! right :namespaces)
                                       (editor/focus! (:ed (active-tab))))})
    (swap! (:after-eval* cv) assoc :namespaces (fn [] (after-eval! ctx)))
    {:fns {:namespaces-show-fn show-fn}
     :ui {:namespaces-pane p}
     :panels {:namespaces {:root (:root p)
                           :focus! (fn [] (pane/focus-list! p))
                           :open! (fn [] (render! ctx) (refresh! ctx))}}}))

(def part
  "The Namespaces part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :namespaces}))
