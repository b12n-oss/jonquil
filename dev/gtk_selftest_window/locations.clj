(ns gtk-selftest-window.locations
  "The locations list: the panel on its own in a transient window, then
  the real window's commands that fill it and F8 / Shift+F8 stepping
  through it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.locations-pane :as lpane]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]
            [jonquil.locations :as loc]
            [jonquil.syntax :as syntax]
            [temp-dirs]))

(def ^:private fixture
  (str "(defmulti area :shape)\n"
       "(defmethod area :sq [s] (* (:w s) (:w s)))\n"
       "(defmethod area :ci [c] (* 3 (:r c)))\n"
       "(area {:shape :sq :w 2})"))

(defn row-locations-pane-shows
  "show! lists one row per entry as line: text, selects the current one and
  puts its position on the footer."
  [_ui]
  (go-row :locations-pane-shows support/check
    (let [{:keys [win pane]} (on-gtk (let [win (g/gtk-window-new)
                                           pane (lpane/make-pane)]
                                       (g/gtk-window-set-child win (:root pane))
                                       (g/gtk-window-present win)
                                       {:win win :pane pane}))
          lst (assoc (loc/definitions fixture "area") :index 1)]
      (try
        (await-until 2000 20 #(pos? (g/gtk-widget-get-width (:root pane))))
        (on-gtk
         (lpane/show! pane lst)
         (let [rows (lpane/row-texts pane)
               selected (lpane/selected-index pane)
               footer (lpane/footer-text pane)
               title (lpane/title-text pane)]
           (support/check :locations-pane-shows
                          (and (= ["1: (defmulti area :shape)"
                                   "2: (defmethod area :sq [s] (* (:w s) (:w s)))"
                                   "3: (defmethod area :ci [c] (* 3 (:r c)))"]
                                  rows)
                               (= 1 selected) (= "2 of 3" footer) (= "Definitions of area" title))
                          {:rows rows :selected selected :footer footer :title title})))
        (finally (on-gtk (g/gtk-window-close win)))))))

;; --- the real window: making a list, F8 and Shift+F8 ----------------------

(def ^:private KEY-F8 0xffc5)

(defn- run-palette!
  "Opens the palette, filters it to query and runs the first entry, then
  closes it. GTK-thread only."
  [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn- f8! [ui shift?]
  ((:handle-window-key! ui) ffi/null KEY-F8 0 (if shift? g/SHIFT-MASK 0) ffi/null))

(defn- sel [ed]
  (let [s (editor/snapshot ed)] [(:sel-start s) (:sel-end s)]))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- await-status
  "Waits for the status bar to read want: set-status! updates it on a
  later GTK turn (on-gui!), so it can't be read in the same one. Returns
  what it read last."
  [ui want]
  (await-until 2000 20 #(= want (status ui)))
  (on-gtk (status ui)))

(defn- start-on-area!
  "The fixture in ed with the caret on the last line's area."
  [ed]
  (editor/set-text! ed fixture)
  (support/place-cursor! (:buf ed) (inc (str/last-index-of fixture "(area"))))

(defn- close-panel! [ui]
  (when-let [f (:on-close @(:handlers (:locations-pane ui)))] (f)))

(def ^:private uses-ranges (syntax/find-symbol-occurrences fixture "area"))

(defn row-locations-uses-step
  "List the uses fills the panel with all four; F8 twice selects the
  second, Shift+F8 the first."
  [ui]
  (go-row :locations-uses-step support/check
    (on-gtk
     (let [ed (:editor ui)]
       (start-on-area! ed)
       (run-palette! ui "list the uses")
       (let [rows (count (lpane/row-texts (:locations-pane ui)))
             _ (f8! ui false)
             _ (f8! ui false)
             second-sel (sel ed)
             _ (f8! ui true)
             first-sel (sel ed)]
         (close-panel! ui)
         (support/check :locations-uses-step
                        (and (= 4 rows) (= (nth uses-ranges 1) second-sel) (= (first uses-ranges) first-sel))
                        {:rows rows :second second-sel :first first-sel :expected (take 2 uses-ranges)}))))))

(defn row-locations-survive-edit
  "An edit above a listed place doesn't strand it: after inserting a line
  at the start, the third place still selects area."
  [ui]
  (go-row :locations-survive-edit support/check
    (on-gtk
     (let [ed (:editor ui) buf (:buf ed) added ";; note\n"]
       (start-on-area! ed)
       (run-palette! ui "list the uses")
       (g/with-iters* buf 0 0 (fn [a _] (g/gtk-text-buffer-insert buf a added -1)))
       (dotimes [_ 3] (f8! ui false))
       (let [[s e] (sel ed)
             text (editor/editor-text ed)
             picked (subs text s e)
             want (+ (count added) (first (nth uses-ranges 2)))]
         (close-panel! ui)
         (support/check :locations-survive-edit (and (= "area" picked) (= want s))
                        {:picked picked :start s :want want}))))))

(defn row-locations-gone
  "A place whose text was deleted is reported gone, and nothing throws."
  [ui]
  (go-row :locations-gone support/check
    (let [ed (:editor ui) [s e] (nth uses-ranges 1)]
      (on-gtk (let [buf (:buf ed)]
                (start-on-area! ed)
                (run-palette! ui "list the uses")
                (g/with-iters* buf s e (fn [a b] (g/gtk-text-buffer-delete buf a b)))
                (f8! ui false)
                (f8! ui false)))
      (let [st (await-status ui "; That place is gone.")]
        (on-gtk (close-panel! ui)
                (support/check :locations-gone (= "; That place is gone." st) {:status st}))))))

(defn row-locations-ends
  "F8 past the last place says so and leaves the selection on it."
  [ui]
  (go-row :locations-ends support/check
    (let [ed (:editor ui)
          last-def (let [d (last (syntax/find-definitions fixture "area"))] [(:start d) (:end d)])]
      (on-gtk (start-on-area! ed)
              (run-palette! ui "list the definitions")
              (dotimes [_ 4] (f8! ui false)))
      (let [st (await-status ui "; End of the list.")
            s (on-gtk (sel ed))]
        (on-gtk (close-panel! ui)
                (support/check :locations-ends (and (= "; End of the list." st) (= last-def s))
                               {:status st :sel s :want last-def}))))))

(defn row-locations-definitions
  "List the definitions gives the defmulti and both defmethods, in order."
  [ui]
  (go-row :locations-definitions support/check
    (on-gtk
     (start-on-area! (:editor ui))
     (run-palette! ui "list the definitions")
     (let [rows (lpane/row-texts (:locations-pane ui))]
       (close-panel! ui)
       (support/check :locations-definitions
                      (and (= 3 (count rows)) (str/starts-with? (first rows) "1: (defmulti area"))
                      {:rows rows})))))

(defn row-locations-tab-close-clears
  "Closing the tab a list came from clears it: F8 then says there's no
  list."
  [ui]
  (go-row :locations-tab-close-clears support/check
    (on-gtk (let [_ ((:new-tab!* ui))
                  t ((:active-tab* ui))
                  ed (:ed t)]
              (start-on-area! ed)
              (run-palette! ui "list the uses")
              (editor/mark-saved! ed)
              ((:close-tab! ui) (:id t))
              (f8! ui false)))
    (let [st (await-status ui "; No locations list.")]
      (on-gtk (support/check :locations-tab-close-clears (= "; No locations list." st) {:status st})))))

(def ^:private replaced-status
  "; The buffer was replaced (Format All or a reload), so the list is out of date: list again.")

(defn row-locations-buffer-replaced
  "A whole-buffer replace (a reload's set-text!, Format All's one edit)
  collapses every place: F8 says the buffer was replaced, not that one
  place is gone."
  [ui]
  (go-row :locations-buffer-replaced support/check
    (let [ed (:editor ui)]
      (on-gtk (start-on-area! ed)
              (run-palette! ui "list the uses")
              (editor/set-text! ed fixture)
              (f8! ui false))
      (let [st (await-status ui replaced-status)]
        (on-gtk (close-panel! ui)
                (support/check :locations-buffer-replaced (= replaced-status st) {:status st}))))))

(defn row-locations-matches-empty-find
  "With the find bar open but empty, List every match uses the selection."
  [ui]
  (go-row :locations-matches-empty-find support/check
    (let [ed (:editor ui)
          [s e] (let [i (str/index-of fixture "shape")] [i (+ i 5)])]
      (on-gtk (start-on-area! ed)
              (find-bar/open! (:find-bar ui) :find)
              ;; earlier find rows leave their query in the entry
              (g/gtk-editable-set-text (:query (:find-bar ui)) "")
              (g/with-iters* (:buf ed) s e (fn [a b] (g/gtk-text-buffer-select-range (:buf ed) a b)))
              (run-palette! ui "list every match"))
      (let [rows (on-gtk (lpane/row-texts (:locations-pane ui)))]
        (on-gtk (close-panel! ui)
                (find-bar/close! (:find-bar ui))
                (support/check :locations-matches-empty-find (= 2 (count rows)) {:rows rows}))))))

(defn row-locations-empty-says-no-places
  "F8 on a list with no places says so."
  [ui]
  (go-row :locations-empty-says-no-places support/check
    (let [ed (:editor ui)]
      ;; + has uses here but no definition in the buffer: an empty list
      (on-gtk (editor/set-text! ed "(+ 1 2)")
              (support/place-cursor! (:buf ed) 1)
              (run-palette! ui "list the definitions")
              (f8! ui false))
      (let [st (await-status ui "; No places.")]
        (on-gtk (close-panel! ui)
                (support/check :locations-empty-says-no-places (= "; No places." st) {:status st}))))))

;; --- Find Usages in Project ------------------------------------------------

(defn row-find-usages-in-project
  "Find Usages in Project, from the palette with the caret on join, lists
  the three uses in a temp two-file project in path order, each with its
  file, line and kind. Ticking only Calls leaves b.clj's call, and F8 opens
  b.clj in a tab with a/join selected. The tree's root, the tabs and the
  panel are put back after."
  [ui]
  (go-row :find-usages-in-project support/check
    (let [root (fn [] (:root @(:state (:file-tree ui))))
          original (on-gtk (root))
          d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-usages-")))
          b-text "(ns demo.b (:require [demo.a :as a]))\n(a/join 1)\n(map a/join [2])\n"
          _ (spit (io/file d "a.clj") "(ns demo.a)\n(defn join [x] x)\n")
          _ (spit (io/file d "b.clj") b-text)
          pane (:locations-pane ui)]
      (on-gtk ((:simulate-folder-result! ui) {:path d})
              (editor/set-text! (:editor ui) "(join 2)")
              (support/place-cursor! (:buf (:editor ui)) 2)
              (run-palette! ui "find usages in project"))
      (let [_ (await-until 5000 20 #(= 3 (count (lpane/row-texts pane))))
            rows (on-gtk (lpane/row-texts pane))
            shown? (on-gtk (lpane/filter-shown? pane))
            calls (on-gtk (lpane/set-ticked! pane #{:call}) (lpane/row-texts pane))
            {:keys [path picked]} (on-gtk (f8! ui false)
                                          (let [t ((:active-tab* ui))
                                                s (editor/snapshot (:ed t))]
                                            {:path @(:path t)
                                             :picked (subs (:text s) (:sel-start s) (:sel-end s))}))]
        (on-gtk
         (try
           (support/check :find-usages-in-project
                          (and (= ["a.clj:2: def: (defn join [x] x)"
                                   "b.clj:2: call: (a/join 1)"
                                   "b.clj:3: value: (map a/join [2])"]
                                  rows)
                               shown?
                               (= ["b.clj:2: call: (a/join 1)"] calls)
                               (some-> path (str/ends-with? "/b.clj"))
                               (= "a/join" picked))
                          {:rows rows :filter-shown shown? :calls calls :path path :picked picked})
           (finally
             (close-panel! ui)
             ((:simulate-folder-result! ui) {:path original})
             (doseq [t (rest ((:tabs* ui)))]
               (editor/mark-saved! (:ed t))
               ((:close-tab! ui) (:id t)))
             ((:select-tab! ui) (:id (first ((:tabs* ui))))))))))))

(defn row-find-usages-stale-file
  "A file that changed on disk after the search isn't anchored at offsets
  read from its old text: F8 opens it and says it changed, and selects
  nothing there. The tree's root, the tabs and the panel are put back
  after."
  [ui]
  (go-row :find-usages-stale-file support/check
    (let [root (fn [] (:root @(:state (:file-tree ui))))
          original (on-gtk (root))
          d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-usages-stale-")))
          f (io/file d "b.clj")
          _ (spit f "(ns demo.b)\n(join 1)\n")
          pane (:locations-pane ui)]
      (on-gtk ((:simulate-folder-result! ui) {:path d})
              (editor/set-text! (:editor ui) "(join 2)")
              (support/place-cursor! (:buf (:editor ui)) 2)
              (run-palette! ui "find usages in project"))
      (let [_ (await-until 5000 20 #(= 1 (count (lpane/row-texts pane))))
            _ (spit f ";; edited after the search\n(ns demo.b)\n(join 1)\n")
            want "; b.clj changed since the search, so its places may be off: search again."
            _ (on-gtk (f8! ui false))
            st (await-status ui want)
            picked (on-gtk (let [s (editor/snapshot (:ed ((:active-tab* ui))))]
                             (subs (:text s) (:sel-start s) (:sel-end s))))]
        (on-gtk
         (try
           (support/check :find-usages-stale-file (and (= want st) (= "" picked))
                          {:status st :picked picked})
           (finally
             (close-panel! ui)
             ((:simulate-folder-result! ui) {:path original})
             (doseq [t (rest ((:tabs* ui)))]
               (editor/mark-saved! (:ed t))
               ((:close-tab! ui) (:id t)))
             ((:select-tab! ui) (:id (first ((:tabs* ui))))))))))))
