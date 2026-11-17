(ns jonquil.gtk.parts.find-var
  "Find a Var by Name as a window part: it asks the window's runtime for
  every loaded var (jonquil.describe-run's apropos snippet, quietly and
  with no console entry) and lists them in the active tab's palette,
  filtered by name. Return opens the var's definition, Shift+Return shows
  its doc. The command is jonquil.parts.find-var/spec."
  (:require [clojure.string :as str]
            [jonquil.console :as console]
            [jonquil.describe-run :as describe-run]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.palette :as palette]
            [jonquil.palette :as pal-data]
            [jonquil.test-run :as test-run]))

(def ^:private TITLE "; Find a Var by Name")

(def ^:private FIND-CAP
  "How many vars the command asks the runtime for. Higher than the MCP
  tools' describe-run/runtime-cap, because the runtime cuts the list
  sorted by namespace before the program's own vars are put first: a JVM
  project with a few libraries loads well over 5000, and own namespaces
  that sort late would fall off."
  50000)

(defn- answer-value
  "The query's notify map as [:value v] or [:problem sentence]."
  [m]
  (let [r (get-in m [:done :result])]
    (cond
      (:busy m) [:problem (str TITLE ": the runtime is busy. Try again when it's done.")]
      (not= :ok (:status r))
      [:problem (str TITLE ": " (first (str/split-lines (str (console/error-text r) "\n"))))]
      :else (if-let [v (test-run/read-value (:printed r))]
              [:value v]
              [:problem (str TITLE ": the runtime's answer couldn't be read.")]))))

(defn- answered!
  "The query's answer, on the GTK thread: the vars in the palette of the
  tab active now (none open: nothing happens), or a status line."
  [{:keys [sel* set-status! active-tab open-palette! files]} m]
  (let [[k x] (answer-value m)
        root (:project-dir @sel*)]
    (cond
      (= :problem k) (set-status! x)
      (empty? (:vars x)) (set-status! "No vars are loaded.")
      :else (when-let [tab (active-tab)]
              (let [paths (delay (if root (files root) []))]
                (open-palette! tab (describe-run/var-entries
                                    x {:own? (fn [f] (some? (describe-run/project-hit @paths f)))}))
                (set-status! (if (pos? (or (:dropped x) 0))
                               (str "Listing the first " FIND-CAP " vars: " (:dropped x)
                                    " more are loaded. Own vars past the cut may be missing.")
                               "Ready")))))))

(defn- find-var!
  "Find a Var by Name: asks the runtime for its loaded vars, fresh each
  time, then lists them. A dialect the snippet can't ask says so."
  [{:keys [sel* set-status! query!] :as ctx}]
  (let [sel @sel*]
    (if-not (describe-run/supported? (:id (:profile sel)))
      (set-status! (str TITLE " works with Jolt and JVM Clojure. This project is "
                        (:name (:profile sel)) "."))
      (query! (describe-run/apropos-snippet {:query "" :cap FIND-CAP})
              (fn [m] (on-gui! (fn [] (answered! ctx m))))))))

(defn- open-var!
  "Return on a var: its file in the project opens at its line; any other
  var is looked up and jumped to as Jump to Definition would (its place
  when the lookup gives one it can open, else its doc)."
  [{:keys [sel* set-status! active-tab source-ctx files open-file-line! ask-lookup! jump-to!]}
   {:keys [ns name file line]}]
  (when-let [tab (active-tab)]
    (let [sym (str ns "/" name)
          sc (source-ctx tab)
          root (:project-dir @sel*)
          path (when (and file line root) (test-run/locate (files root) file))]
      (if path
        (open-file-line! sc sym path line)
        (ask-lookup! sc {:sym sym :text "" :pos 0 :still? (constantly true) :label "jump"
                         :on-doc (fn [doc]
                                   (if doc
                                     (jump-to! sc sym {:kind :external :doc doc})
                                     (set-status! (str "No definition found for " sym "."))))})))))

(defn- show-var-doc!
  "Shift+Return on a var: its doc, as Docs for the Symbol shows it."
  [{:keys [active-tab source-ctx show-doc-for!]} sym]
  (when-let [tab (active-tab)]
    (show-doc-for! (source-ctx tab) sym)))

(defn- install
  "The find-var part for one window. Answers :find-var-fn,
  :find-var-open-fn and :find-var-doc-fn."
  [{:keys [ev* sel* active-tab set-status! source-ctx mcp-query! open-file-line! ask-lookup! jump-to! show-doc-for!]}]
  (let [ctx {:sel* sel* :set-status! set-status! :active-tab active-tab :source-ctx source-ctx
             :query! (fn [code notify] (mcp-query! {:ev* ev*} code notify))
             :open-palette! (fn [tab entries] (palette/open! (:palette tab) entries {:filter pal-data/filter-names}))
             :files test-run/project-files
             :open-file-line! open-file-line! :ask-lookup! ask-lookup! :jump-to! jump-to!
             :show-doc-for! show-doc-for!}]
    {:fns {:find-var-fn (fn [] (find-var! ctx))
           :find-var-open-fn (fn [m] (open-var! ctx m))
           :find-var-doc-fn (fn [sym] (show-var-doc! ctx sym))}}))

(def part
  "The find-var part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :find-var}))
