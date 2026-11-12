(ns jonquil.gtk.parts.locations
  "The Locations list and Compare as a window part (the commands and the
  label are jonquil.parts.locations/spec). Moved out of jonquil.gtk.window:
  the list's state and marks, the three ways to fill it, stepping, Find Usages
  in Project, Compare with disk, HEAD and another tab, and the hook that clears the list
  when its tab closes."
  (:require [clojure.string :as str]
            [jolt.process]
            [jonquil.compare :as compare]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.locations-pane :as locations-pane]
            [jonquil.help :as help]
            [jonquil.locations :as loc]
            [jonquil.resolve :as resolve]
            [jonquil.source :as source]
            [jonquil.usages :as usages]))

(defn- create-location-marks
  "Two anonymous marks per entry in buf, so the place follows edits: the
  start's mark has right gravity and the end's left, so text typed at
  either edge stays outside the place."
  [buf entries]
  (mapv (fn [{:keys [start end]}]
          (g/with-iters* buf start end
            (fn [a b] [(g/gtk-text-buffer-create-mark buf nil a 0)
                       (g/gtk-text-buffer-create-mark buf nil b 1)])))
        entries))

(defn- delete-location-marks! [buf marks]
  (doseq [[a b] marks]
    (g/gtk-text-buffer-delete-mark buf a)
    (g/gtk-text-buffer-delete-mark buf b)))

(defn- clear-locations!
  [{:keys [state tabs tab-by-id right]}]
  (when-let [{:keys [tab-id marks anchors]} @state]
    (when-let [t (and tab-id (tab-by-id tabs tab-id))]
      (delete-location-marks! (:buf (:ed t)) marks))
    (doseq [[tid as] (group-by :tab-id (vals anchors))]
      (when-let [t (tab-by-id tabs tid)]
        (delete-location-marks! (:buf (:ed t)) (map :marks as)))))
  (reset! state nil)
  ;; an empty list is nothing to switch back to
  (swap! (:recent-panels right) (fn [v] (vec (remove #{:locations} v)))))

(defn- tab-for-path
  [{:keys [tabs]} path]
  (some (fn [t] (when (and @(:path t) (source/same-path? @(:path t) path)) t)) @tabs))

(defn- anchor-file!
  [{:keys [state]} t path]
  ;; every entry of path's file, filtered out or not, gets
  ;; its marks in t's buffer, but only while the buffer
  ;; holds the text the search read: otherwise the
  ;; offsets would land anywhere, so the file is stale
  (let [lst (:list @state)]
    (if (= (get (:hashes lst) path) (hash (editor/editor-text (:ed t))))
      (let [es (filterv #(= path (:path %)) (:all lst))
            ms (create-location-marks (:buf (:ed t)) es)]
        (swap! state update :anchors into
               (map (fn [e m] [(:id e) {:tab-id (:id t) :marks m}]) es ms)))
      (swap! state update :stale (fnil conj #{}) path))))

(defn- start-list!
  "Makes lst the list: a project list's anchors for the files already open, a
  buffer list's marks in the active tab."
  [{:keys [state active-tab] :as ctx} lst]
  (if (= :project (:scope lst))
    (do (reset! state {:list lst :anchors {}})
        (doseq [path (distinct (map :path (:all lst)))]
          (when-let [t (tab-for-path ctx path)] (anchor-file! ctx t path))))
    (let [t (active-tab)]
      (reset! state {:tab-id (:id t) :list lst
                     :marks (create-location-marks (:buf (:ed t)) (:entries lst))}))))

(defn- make-locations!
  [{:keys [gen locp right show-right-panel! set-status!] :as ctx} lst]
  (swap! gen inc)
  (clear-locations! ctx)
  (start-list! ctx lst)
  (locations-pane/show! locp lst)
  (show-right-panel! right :locations)
  (set-status! (str "; " (:title lst) ": " (loc/position-text lst))))

(defn- project-place
  "A project entry's place, its file opened and anchored first when it isn't
  yet; {:stale path} when that file's text has changed since the search; nil
  when it can't be opened."
  [{:keys [state tab-by-id tabs source-ctx active-tab] :as ctx} lst]
  (let [e (nth (:entries lst) (:index lst))]
    (when-not (get (:anchors @state) (:id e))
      (let [t (or (tab-for-path ctx (:path e))
                  (when-not (:error ((:open-file! (source-ctx (active-tab))) (:path e)))
                    (tab-for-path ctx (:path e))))]
        (when t (anchor-file! ctx t (:path e)))))
    (if-let [a (get (:anchors @state) (:id e))]
      {:tab (tab-by-id tabs (:tab-id a)) :marks (:marks a)
       :buffer-marks (keep (fn [[_ b]] (when (= (:tab-id a) (:tab-id b)) (:marks b)))
                           (:anchors @state))}
      (when (contains? (:stale @state) (:path e))
        {:stale (:path e)}))))

(defn- place-of
  [{:keys [state tab-by-id tabs] :as ctx} lst]
  ;; {:tab :marks :buffer-marks} for lst's current entry, a
  ;; project entry's file opened and anchored first when it
  ;; isn't yet; {:stale path} when that file's text has
  ;; changed since the search; nil when it can't be opened
  (let [{:keys [tab-id marks]} @state
        i (:index lst)]
    (if (= :project (:scope lst))
      (project-place ctx lst)
      {:tab (tab-by-id tabs tab-id) :marks (nth marks i) :buffer-marks marks})))

(defn- select-place!
  "Selects the place [s e] in t's buffer and says where in the list it is."
  [{:keys [tab-ctx* select-tab! place-caret-and-scroll! set-status!]} lst t [s e]]
  (let [ed (:ed t)
        buf (:buf ed)]
    (select-tab! @tab-ctx* (:id t))
    (place-caret-and-scroll! ed s)
    (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-select-range buf a b)))
    (set-status! (str "; " (loc/position-text lst)))))

(defn- goto-marked-place!
  [{:keys [set-status!] :as ctx} lst {t :tab [ma mb] :marks bm :buffer-marks}]
  (let [buf (:buf (:ed t))
        s (g/mark-offset buf ma)
        e (g/mark-offset buf mb)]
    (if (>= s e)
      ;; Every place collapsed at once is a whole-buffer
      ;; replace (Format All's one edit, a reload's
      ;; set-text!), not one deleted place
      (set-status!
       (if (every? (fn [[a b]] (>= (g/mark-offset buf a) (g/mark-offset buf b))) bm)
         "; The buffer was replaced (Format All or a reload), so the list is out of date: list again."
         "; That place is gone."))
      (select-place! ctx lst t [s e]))))

(defn- goto-location!
  [{:keys [state locp set-status! file-name] :as ctx} lst]
  (swap! state assoc :list lst)
  (locations-pane/select! locp lst)
  (if-let [place (place-of ctx lst)]
    (if-let [stale (:stale place)]
      (set-status!
       (str "; " (file-name stale) " changed since the search, so its places may be off: search again."))
      (goto-marked-place! ctx lst place))
    (set-status! "; Couldn't open that file.")))

(defn- locations-step!
  [{:keys [state set-status!] :as ctx} delta]
  (if-let [lst (:list @state)]
    (let [{l :list moved? :moved?} (loc/step lst delta)]
      (cond moved? (goto-location! ctx l)
            (empty? (:entries l)) (set-status! "; No places.")
            :else (set-status! (if (pos? delta) "; End of the list." "; Start of the list."))))
    (set-status! "; No locations list.")))

(defn- symbol-at-caret
  [{:keys [active-tab]}]
  (let [snap (editor/snapshot (:ed (active-tab)))]
    [(:text snap) (:symbol (help/target snap))]))

(defn- list-for-symbol!
  "Lists what list-fn finds for the symbol at the caret, or says there is none."
  [{:keys [set-status!] :as ctx} list-fn]
  (let [[text sym] (symbol-at-caret ctx)]
    (if sym
      (make-locations! ctx (list-fn text sym))
      (set-status! "; No symbol at the caret."))))

(defn- open-texts
  "{canonical path text} of every open tab with a file."
  [{:keys [tabs]}]
  (into {} (keep (fn [x] (when-let [p @(:path x)]
                           [(.getCanonicalPath (java.io.File. ^String p))
                            (editor/editor-text (:ed x))])))
        @tabs))

(defn- land-usages!
  "On the GUI thread: makes the list of the scan's answer r, unless a newer
  list has replaced the one this search was asked for. scan is {:gen-at :r
  :root :name}."
  [{:keys [gen set-status!] :as ctx} {:keys [gen-at r root name]}]
  (when (= gen-at @gen)
    (try
      (if-let [err (:error r)]
        (set-status! (str "; Find usages: " err))
        (do (make-locations! ctx (assoc (loc/project-uses r name) :root root))
            (when (pos? (:unreadable r 0))
              (set-status! (str "; " (:unreadable r) " file(s) couldn't be read.")))))
      (catch :default e
        (set-status! (str "; Find usages: " (or (ex-message e) (str e))))))))

(defn- scan-usages!
  "Scans on a future, landing the answer on the GUI thread. opts is {:root
  :name :target :features}, as usages/scan takes them."
  [{:keys [gen set-status!] :as ctx} {:keys [root name] :as opts}]
  (let [gen-at (swap! gen inc)
        texts (open-texts ctx)]
    (set-status! (str "; Finding usages of " name "..."))
    (future
      (let [r (try (usages/scan (assoc opts :texts texts))
                   (catch :default e {:error (or (ex-message e) (str e))}))]
        (on-gui! (fn [] (land-usages! ctx {:gen-at gen-at :r r :root root :name name})))))))

(defn- caret-target
  "The resolution of the symbol at the active tab's caret (resolve/resolve-at,
  read with features), or nil when there's no symbol, or resolving it threw
  (the search then lists by name, as it did before LM2 resolved names)."
  [{:keys [active-tab]} features]
  (let [t (active-tab)
        snap (editor/snapshot (:ed t))]
    (when-let [{:keys [symbol start]} (help/target snap)]
      (let [fs (resolve/features-for (or @(:path t) "") features)]
        (try (resolve/resolve-at (resolve/file-context (:text snap) fs) start symbol)
             (catch :default _ nil))))))

;; Find Usages in Project (LM2): jonquil.usages/scan on a future,
;; open tabs' text in place of their files'; a newer search
;; supersedes one still running. The symbol at the caret is resolved
;; first, so the list starts at that var's own uses
(defn- project-usages!
  [{:keys [tree-pane active-tab set-status! sel*] :as ctx}]
  (let [[_ sym] (symbol-at-caret ctx)
        t (active-tab)
        root (or (:root @(:state tree-pane))
                 (some-> @(:path t) (java.io.File.) .getCanonicalFile .getParent))
        features (or (some-> sel* deref :profile :reader-features) #{:clj})]
    (cond
      (nil? sym) (set-status! "; No symbol at the caret.")
      (nil? root) (set-status! "; No project folder: open a folder, or save this file first.")
      :else (scan-usages! ctx {:root root :name (usages/search-name sym)
                               :target (caret-target ctx features) :features features}))))

(defn- locations-matches!
  [{:keys [active-tab set-status!] :as ctx}]
  (let [{:keys [ed fb]} (active-tab)
        snap (editor/snapshot ed)
        find-q (when (pos? (g/gtk-widget-get-visible (:box fb)))
                 (g/editable-text (:query fb)))
        ;; the find query when the bar has one, else the selection
        q (if (str/blank? find-q)
            (subs (:text snap) (:sel-start snap) (:sel-end snap))
            find-q)]
    (if (str/blank? q)
      (set-status! "; Nothing to list: open Find, or select some text.")
      (make-locations! ctx (loc/matches (:text snap) q)))))

;; LM3: the tab against another version of its file, another tab or its
;; last Run, its differences in the Locations list. compare-state is {:tab-id
;; :label :other}, plus :other-tab-id when the other version is a tab or
;; :last-run? when it is the last Run, for
;; Take, which works out the difference at the caret afresh.
(defn- compare-list!
  "Lists the differences between tab's text and other in the Locations panel,
  or says there are none. extra is what compare-state keeps beside the
  label (nil for the disk or HEAD)."
  [{:keys [compare-state state locp right hide-right-panel! set-status!] :as ctx} {:keys [tab label other extra]}]
  (let [l (compare/location-list label other (editor/editor-text (:ed tab)))]
    (if (seq (:entries l))
      (do (reset! compare-state (merge {:tab-id (:id tab) :label label :other other} extra))
          (make-locations! ctx l))
      (do (reset! compare-state nil)
          ;; a list of differences that are gone goes too
          (when (some-> @state :list :title (str/starts-with? "Differences from "))
            (clear-locations! ctx)
            (locations-pane/show! locp {:title "" :entries [] :index nil})
            (hide-right-panel! right :locations))
          (set-status! (str "; No differences from " label "."))))))

(defn- read-disk
  [path]
  (let [f (java.io.File. ^String path)]
    (if-not (.exists f)
      {:error "The file isn't on disk any more."}
      (try {:text (slurp path)}
           (catch :default e {:error (str "Couldn't read the file: " (or (ex-message e) (str e)))})))))

(defn- read-head
  [path]
  (let [f (java.io.File. ^String path)
        r (try (jolt.process/sh {:dir (.getParent f)} "git" "show" (str "HEAD:./" (.getName f)))
               (catch :default e {:exit -1 :err (ex-message e)}))]
    (if (= 0 (:exit r))
      {:text (:out r)}
      {:error (str "git: " (or (not-empty (first (str/split-lines (str (:err r)))))
                               (str "exited " (:exit r))))})))

(defn- compare-with!
  [{:keys [active-tab set-status!] :as ctx} label read-other]
  (let [t (active-tab)
        path @(:path t)]
    (if-not path
      (set-status! "; Compare needs a saved file.")
      (let [{:keys [text error]} (read-other path)]
        (if error
          (set-status! (str "; " error))
          (compare-list! ctx {:tab t :label label :other text}))))))

(defn- compare-tab!
  "Compare with tab: the active tab against the open tab other-id, as it
  is now. Neither needs to be saved."
  [{:keys [active-tab tabs tab-by-id set-status!] :as ctx} other-id]
  (let [t (active-tab)
        o (tab-by-id tabs other-id)]
    (cond
      (nil? o) (set-status! "; That tab isn't open any more.")
      (= (:id o) (:id t)) (set-status! "; Pick another tab to compare this one with.")
      :else (compare-list! ctx {:tab t :label (g/label-text (:label o))
                                :other (editor/editor-text (:ed o))
                                :extra {:other-tab-id other-id}}))))

(def ^:private last-run-label "the last Run")

(defn- last-run-text [t] (some-> t :ed :changed :last-run deref))

(defn- compare-last-run!
  "Compare with the last Run: the active tab against the text its last
  whole Run sent, when that Run ended well."
  [{:keys [active-tab set-status!] :as ctx}]
  (let [t (active-tab)]
    (if-let [text (last-run-text t)]
      (compare-list! ctx {:tab t :label last-run-label :other text :extra {:last-run? true}})
      (set-status! "; This tab hasn't had a whole Run that ended well yet."))))

(defn- take-hunk!
  "Takes other's lines for the difference at the caret of t, as one undo
  step. extra is what compare-state keeps beside the label, kept again."
  [{:keys [state set-status!] :as ctx} {:keys [tab label other extra]}]
  (let [ed (:ed tab)
        buffer (editor/editor-text ed)
        line (first (g/cursor-line-col (:buf ed)))]
    (if-let [h (compare/hunk-at (compare/hunks other buffer) line buffer)]
      (let [[s e r] (compare/take-edit buffer h)]
        ;; one undo step, exactly the other version's lines
        (editor/apply-op-plain! ed {:edits [[s e r]] :caret s})
        (compare-list! ctx {:tab tab :label label :other other :extra extra})
        (when (seq (:entries (:list @state)))
          (set-status! (str "; Took " (if (= "disk" label) "the disk's" (str label "'s"))
                            " version of line " (inc (:line h)) "."))))
      (set-status! "; No difference at the caret: F8 steps to the next one."))))

(defn- compare-take!
  [{:keys [compare-state active-tab tabs tab-by-id set-status!] :as ctx}]
  (let [t (active-tab)
        {:keys [tab-id label other-tab-id] :as st} @compare-state
        ;; the disk, or the other tab, as it is now, not as it was at the
        ;; compare; a tab closed since then gives the text it had
        other (cond
                other-tab-id (or (some-> (tab-by-id tabs other-tab-id) :ed editor/editor-text) (:other st))
                (:last-run? st) (or (last-run-text t) (:other st))
                (= "disk" label) (or (:text (some-> @(:path t) read-disk)) (:other st))
                :else (:other st))]
    (if (not= tab-id (:id t))
      (set-status! "; Compare this tab first: with the file on disk, git HEAD, another tab or the last Run.")
      (take-hunk! ctx {:tab t :label label :other other
                       :extra (select-keys st [:other-tab-id :last-run?])}))))

(defn- set-handlers!
  [{:keys [state locp right hide-right-panel! set-status!] :as ctx}]
  (locations-pane/set-handlers!
   locp {:on-activate (fn [i] (when-let [lst (:list @state)]
                                (goto-location! ctx (assoc lst :index i))))
         :on-close (fn [] (hide-right-panel! right :locations))
         :on-hows (fn [shown]
                    (when-let [lst (:list @state)]
                      (let [l (loc/set-hows lst shown)]
                        (swap! state assoc :list l)
                        (locations-pane/show! locp l)
                        (set-status! (str "; " (:title l) ": " (loc/position-text l))))))}))

(defn- tab-closed!
  "The tab hook: a closing tab takes its own marks with it, and the list when
  the list was in its buffer. Runs while the buffer is alive."
  [{:keys [state right hide-right-panel!] :as ctx} id]
  (let [{:keys [tab-id anchors]} @state]
    (cond
      (= id tab-id) (do (clear-locations! ctx)
                        (hide-right-panel! right :locations))
      ;; a project list keeps its other files;
      ;; this one's marks went with its buffer
      (some #(= id (:tab-id %)) (vals anchors))
      (swap! state update :anchors
             (fn [as] (into {} (remove (fn [[_ a]] (= id (:tab-id a)))) as))))))

(defn- open-panel!
  "The panel key when the list isn't showing: back with its state when it
  showed before and still holds (a cleared list is off the recent list), else
  what fills it."
  [{:keys [locp right show-right-panel! set-status!]}]
  (if (some #{:locations} @(:recent-panels right))
    (do (show-right-panel! right :locations)
        (g/gtk-widget-grab-focus (:list locp)))
    (set-status! "; No locations yet: list a symbol's uses or definitions, or the find matches.")))

(defn- locations-fns
  [ctx]
  {:locations-uses-fn (fn [] (list-for-symbol! ctx loc/uses))
   :locations-definitions-fn (fn [] (list-for-symbol! ctx loc/definitions))
   :locations-matches-fn (fn [] (locations-matches! ctx))
   :locations-step-fn (fn [delta] (locations-step! ctx delta))
   :locations-project-usages-fn (fn [] (project-usages! ctx))})

(defn- compare-fns
  [ctx]
  {:compare-disk-fn (fn [] (compare-with! ctx "disk" read-disk))
   :compare-head-fn (fn [] (compare-with! ctx "HEAD" read-head))
   :compare-take-fn (fn [] (compare-take! ctx))
   :compare-tab-fn (fn [id] (compare-tab! ctx id))
   :compare-last-run-fn (fn [] (compare-last-run! ctx))})

(defn- install
  "The Locations part for one window: builds the pane, wires its handlers and
  answers the ten list and compare fns and the tab-removed hook.

  state is the locations list, or nil. A buffer list is {:tab-id :list
  :marks}, its marks in that tab's buffer. A project list (:scope
  :project, Find Usages in Project) is {:list :anchors}: anchors
  maps an entry's :id to {:tab-id :marks}, made for a whole file
  the first time its tab is open, when the list is made or when one
  of its places is visited. A new list deletes the marks, and a
  closing tab takes its own with it. GTK thread only.

  gen is bumped by every new list, so a Find Usages still running lands
  only when nothing has replaced the list it was asked for."
  [wctx]
  (let [locp (locations-pane/make-pane)
        ctx (assoc wctx :locp locp :state (atom nil) :gen (atom 0) :compare-state (atom nil))]
    (set-handlers! ctx)
    {:fns (merge (locations-fns ctx) (compare-fns ctx))
     :ui {:locations-pane locp}
     :hooks {:on-tab-removed (fn [_ t] (tab-closed! ctx (:id t)))}
     :panels {:locations {:root (:root locp)
                          :focus! (fn [] (g/gtk-widget-grab-focus (:list locp)))
                          :open! (fn [] (open-panel! ctx))}}}))

(def part
  "The Locations part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :locations}))
