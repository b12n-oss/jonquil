(ns jonquil.gtk.parts.find-var
  "Find a Var by Name as a window part: it asks the window's runtime for
  every loaded var (jonquil.describe-run's apropos snippet, quietly and
  with no console entry) and lists them in the active tab's palette,
  filtered by name. Return opens the var's definition, Shift+Return shows
  its doc. The command is jonquil.parts.find-var/spec."
  (:require [jonquil.describe-run :as describe-run]
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

(defn project-paths
  "The files of the project at root, remembered in paths* for the Return
  that follows, so a list's Return doesn't walk the disk again."
  [{:keys [files paths*]} root]
  (let [{r :root ps :paths} (some-> paths* deref)]
    (if (and ps (= r root))
      ps
      (let [ps (if root (files root) [])]
        (when paths* (reset! paths* {:root root :paths ps}))
        ps))))

(defn- listing
  "What the query's answer lists, worked out off the GTK thread (reading a
  big answer and sorting it takes a while): [:problem sentence] or
  [:entries entries dropped]."
  [{:keys [sel*] :as ctx} m]
  (try
    (let [[k x] (describe-run/query-answer m TITLE)]
      (cond
        (= :problem k) [:problem x]
        (empty? (:vars x)) [:problem "No vars are loaded."]
        :else (let [root (:project-dir @sel*)
                    paths (delay (project-paths ctx root))]
                [:entries (describe-run/var-entries
                           x {:own? (fn [f] (some? (describe-run/project-hit @paths f)))})
                 (or (:dropped x) 0)])))
    (catch :default e [:problem (str TITLE ": " (ex-message e))])))

(defn- show-listing!
  "A listing, on the GTK thread: the vars in the palette of the tab active
  now (none open: nothing happens), or a status line. A throw is said on
  the status line too."
  [{:keys [set-status! active-tab open-palette!]} [k x dropped]]
  (try
    (if (= :problem k)
      (set-status! x)
      (when-let [tab (active-tab)]
        (open-palette! tab x)
        (set-status! (if (pos? dropped)
                       (str "Listing the first " FIND-CAP " vars: " dropped
                            " more are loaded. Own vars past the cut may be missing.")
                       "Ready"))))
    (catch :default e (set-status! (str TITLE ": " (ex-message e))))))

(defn- find-var!
  "Find a Var by Name: asks the runtime for its loaded vars, fresh each
  time, then lists them. A dialect the snippet can't ask says so."
  [{:keys [sel* set-status! query!] :as ctx}]
  (let [sel @sel*]
    (if-not (describe-run/supported? (:id (:profile sel)))
      (set-status! (str TITLE " works with Jolt and JVM Clojure. This project is "
                        (:name (:profile sel)) "."))
      (query! (describe-run/apropos-snippet {:query "" :cap FIND-CAP})
              (fn [m] (let [l (listing ctx m)] (on-gui! (fn [] (show-listing! ctx l)))))))))

(defn open-var!
  "Return on a var: its file in the project opens at its line; any other
  var is looked up and jumped to as Jump to Definition would (its place
  when the lookup gives one it can open, else its doc). A late lookup
  answer acts only while the tab is still the active one and open."
  [{:keys [sel* set-status! active-tab source-ctx open-file-line! ask-lookup! jump-to!] :as ctx}
   {:keys [ns name file line]}]
  (when-let [tab (active-tab)]
    (let [sym (str ns "/" name)
          sc (source-ctx tab)
          root (:project-dir @sel*)
          path (when (and file line root) (test-run/locate (project-paths ctx root) file))]
      (if path
        (open-file-line! sc sym path line)
        (ask-lookup! sc {:sym sym :text "" :pos 0 :label "jump"
                         :still? (fn [] (boolean (and ((:active? sc)) (not @(:destroyed? (:ed sc))))))
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
             :files test-run/project-files :paths* (atom nil)
             :open-file-line! open-file-line! :ask-lookup! ask-lookup! :jump-to! jump-to!
             :show-doc-for! show-doc-for!}]
    {:fns {:find-var-fn (fn [] (find-var! ctx))
           :find-var-open-fn (fn [m] (open-var! ctx m))
           :find-var-doc-fn (fn [sym] (show-var-doc! ctx sym))}}))

(def part
  "The find-var part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :find-var}))
