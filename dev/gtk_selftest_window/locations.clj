(ns gtk-selftest-window.locations
  "The locations list: the panel on its own in a transient window, then
  the real window's commands that fill it and F8 / Shift+F8 stepping
  through it."
  (:require [selftest-keys]
            [clojure.java.io :as io]
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
                                       (selftest-keys/watch! win)
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

(defn row-locations-pane-wiring
  "Unticking a filter button reaches :on-hows, Close reaches :on-close, and
  a throw in a handler lands on the footer."
  [_ui]
  (go-row :locations-pane-wiring support/check
    (let [{:keys [win pane]} (on-gtk (let [win (g/gtk-window-new)
                                           pane (lpane/make-pane)]
                                       (g/gtk-window-set-child win (:root pane))
                                       (selftest-keys/watch! win)
                                       (g/gtk-window-present win)
                                       {:win win :pane pane}))
          calls (atom [])]
      (try
        (await-until 2000 20 #(pos? (g/gtk-widget-get-width (:root pane))))
        (on-gtk
         (lpane/set-handlers! pane {:on-hows (fn [shown] (swap! calls conj [:hows shown]))
                                    :on-close (fn [] (swap! calls conj [:close]))})
         (g/gtk-check-button-set-active (second (first (:filter-btns pane))) 0)
         (g/g-signal-emit-by-name (:close-btn pane) "clicked")
         (lpane/set-handlers! pane {:on-close (fn [] (throw (ex-info "boom" {})))})
         (g/g-signal-emit-by-name (:close-btn pane) "clicked")
         (let [footer (lpane/footer-text pane)]
           (support/check :locations-pane-wiring
                          (and (= [[:hows #{:value :definition :ns}] [:close]] @calls)
                               (= "; boom" footer))
                          {:calls @calls :footer footer})))
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

(defn row-find-usages-resolved
  "With the caret on a/join where a is demo.a's alias, Find Usages in
  Project lists only demo.a/join's own uses: its def and the a/join call,
  not str/join, and not c.clj's join brought in by :use, which the file
  can't decide. The title names the var, and a second row offers Other
  vars (1) and Unresolved (1); ticking Unresolved adds c.clj's call, marked
  as such. The tree's root, the tabs and the panel are put back after."
  [ui]
  (go-row :find-usages-resolved support/check
    (let [root (fn [] (:root @(:state (:file-tree ui))))
          original (on-gtk (root))
          d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-usages-resolved-")))
          ns-b "(ns demo.b (:require [demo.a :as a] [clojure.string :as str]))\n"
          _ (spit (io/file d "a.clj") "(ns demo.a)\n(defn join [x] x)\n")
          _ (spit (io/file d "b.clj") (str ns-b "(a/join 1)\n(str/join [2])\n"))
          _ (spit (io/file d "c.clj") "(ns demo.c (:use demo.a))\n(join 3)\n")
          pane (:locations-pane ui)]
      (on-gtk ((:simulate-folder-result! ui) {:path d})
              (editor/set-text! (:editor ui) (str ns-b "(a/join 9)"))
              (support/place-cursor! (:buf (:editor ui)) (+ (count ns-b) 3))
              (run-palette! ui "find usages in project"))
      (let [_ (await-until 5000 20 #(= 2 (count (lpane/row-texts pane))))
            rows (on-gtk (lpane/row-texts pane))
            title (on-gtk (lpane/title-text pane))
            match-row? (on-gtk (lpane/match-filter-shown? pane))
            labels (on-gtk (lpane/match-labels-text pane))
            with-unresolved (on-gtk (lpane/set-ticked! pane (conj (lpane/ticked pane) :unresolved))
                                    (lpane/row-texts pane))]
        (on-gtk
         (try
           (support/check :find-usages-resolved
                          (and (= ["a.clj:2: def: (defn join [x] x)" "b.clj:2: call: (a/join 1)"] rows)
                               (= "Usages of demo.a/join (2 in 2 files)" title)
                               match-row?
                               (= ["Other vars (1)" "Unresolved (1)"] labels)
                               (= ["a.clj:2: def: (defn join [x] x)" "b.clj:2: call: (a/join 1)"
                                   "c.clj:2: call, unresolved: (join 3)"]
                                  with-unresolved))
                          {:rows rows :title title :match-row match-row? :labels labels
                           :with-unresolved with-unresolved})
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

;; --- compare with disk (LM3) ---------------------------------------------------

(defn row-compare-with-disk-and-take
  "Compare with the file on disk lists the tab's two differences in the
  Locations list; Take the other version here, with the caret on the first,
  puts the disk's line back as one undo step and lists one difference."
  [ui]
  (go-row :compare-with-disk-and-take support/check
    (let [f (io/file (temp-dirs/temp-dir! "jonquil-selftest-compare-") "c.clj")
          _ (spit f "(ns c)\n(def a 1)\n(def b 2)\n(def c 3)\n")
          _ (on-gtk ((:open-file! ui) (.getPath f)))
          ed (on-gtk (:ed ((:active-tab* ui))))
          edited "(ns c)\n(def a 10)\n(def b 2)\n(def c 30)\n"
          _ (on-gtk (editor/apply-op-plain! ed {:edits [[0 (count (editor/editor-text ed)) edited]] :caret 0})
                    (run-palette! ui "compare with the file on disk"))
          listed? (await-until 3000 20 #(= 2 (count (lpane/row-texts (:locations-pane ui)))))
          rows (on-gtk (lpane/row-texts (:locations-pane ui)))
          _ (on-gtk (support/place-cursor! (:buf ed) 9)
                    (run-palette! ui "take the other version here"))
          took? (await-until 3000 20 #(= "(ns c)\n(def a 1)\n(def b 2)\n(def c 30)\n" (editor/editor-text ed)))
          one-left? (await-until 3000 20 #(= 1 (count (lpane/row-texts (:locations-pane ui)))))
          _ (on-gtk (g/gtk-text-buffer-undo (:buf ed)))
          undone (on-gtk (editor/editor-text ed))]
      (on-gtk (support/check :compare-with-disk-and-take
                             (and listed? took? one-left? (= edited undone)
                                  (= ["2: 1 line here, 1 line on disk: (def a 1)" "4: 1 line here, 1 line on disk: (def c 3)"] rows))
                             {:listed listed? :rows rows :took took? :one-left one-left? :undone undone :status (status ui)})
              (editor/mark-saved! ed)
              ((:close-tab! ui) (:id ((:active-tab* ui))))))))

(defn row-compare-this-form-and-take
  "This form: compare with the file on disk, with the caret in defn f, lists
  only f's difference, titled for it, though (def b ...) differs too; Take
  puts f's disk line back and leaves b's edit alone."
  [ui]
  (go-row :compare-this-form-and-take support/check
    (let [f (io/file (temp-dirs/temp-dir! "jonquil-selftest-compare-form-") "c.clj")
          _ (spit f "(ns c)\n(defn f [x]\n  (inc x))\n(def b 2)\n")
          _ (on-gtk ((:open-file! ui) (.getPath f)))
          ed (on-gtk (:ed ((:active-tab* ui))))
          edited "(ns c)\n(defn f [x]\n  (dec x))\n(def b 20)\n"
          _ (on-gtk (editor/apply-op-plain! ed {:edits [[0 (count (editor/editor-text ed)) edited]] :caret 0})
                    (support/place-cursor! (:buf ed) 10)
                    (run-palette! ui "this form: compare with the file on disk"))
          listed? (await-until 3000 20 #(= 1 (count (lpane/row-texts (:locations-pane ui)))))
          title (on-gtk (lpane/title-text (:locations-pane ui)))
          _ (on-gtk (support/place-cursor! (:buf ed) 22)
                    (run-palette! ui "take the other version here"))
          want "(ns c)\n(defn f [x]\n  (inc x))\n(def b 20)\n"
          took? (await-until 3000 20 #(= want (editor/editor-text ed)))
          text (on-gtk (editor/editor-text ed))]
      (on-gtk (support/check :compare-this-form-and-take
                             (and listed? took? (= "Differences in defn f from disk" title))
                             {:listed listed? :title title :took took? :text text :status (status ui)})
              (editor/mark-saved! ed)
              ((:close-tab! ui) (:id ((:active-tab* ui))))))))

(defn row-compare-with-head
  "Compare with git HEAD lists what changed since the last commit; a file
  outside any repository says git's own reason."
  [ui]
  (go-row :compare-with-head support/check
    (let [d (io/file (temp-dirs/temp-dir! "jonquil-selftest-compare-git-"))
          f (io/file d "g.clj")
          sh (requiring-resolve 'jolt.process/sh)
          _ (spit f "(ns g)\n(def x 1)\n")
          git (fn [& args] (apply sh {:dir (.getPath d)} "git" "-c" "user.name=t" "-c" "user.email=t@t" args))
          _ (git "init" "-q") _ (git "add" "g.clj") _ (git "commit" "-q" "-m" "one")
          _ (spit f "(ns g)\n(def x 2)\n")
          _ (on-gtk ((:open-file! ui) (.getPath f)) (run-palette! ui "compare with git head"))
          listed? (await-until 5000 20 #(= ["2: 1 line here, 1 line in HEAD: (def x 1)"] (lpane/row-texts (:locations-pane ui))))
          rows (on-gtk (lpane/row-texts (:locations-pane ui)))
          _ (on-gtk ((:close-tab! ui) (:id ((:active-tab* ui)))))
          loose (io/file (temp-dirs/temp-dir! "jonquil-selftest-compare-loose-") "l.clj")
          _ (spit loose "(ns l)\n")
          _ (on-gtk ((:open-file! ui) (.getPath loose)) (run-palette! ui "compare with git head"))
          said? (await-until 5000 20 #(str/starts-with? (status ui) "; git: fatal:"))]
      (on-gtk (support/check :compare-with-head (and listed? said?)
                             {:listed listed? :rows rows :said said? :status (status ui)})
              ((:close-tab! ui) (:id ((:active-tab* ui))))))))

(defn row-compare-line-only-on-disk
  "A line only on disk is a place the list steps to; Take puts it back,
  and with no differences left the list goes."
  [ui]
  (go-row :compare-line-only-on-disk support/check
    (let [f (io/file (temp-dirs/temp-dir! "jonquil-selftest-compare-ins-") "i.clj")
          disk "(ns i)\n(def a 1)\n(def b 2)\n"
          _ (spit f disk)
          _ (on-gtk ((:open-file! ui) (.getPath f)))
          ed (on-gtk (:ed ((:active-tab* ui))))
          _ (on-gtk (editor/apply-op-plain! ed {:edits [[0 (count (editor/editor-text ed)) "(ns i)\n(def b 2)\n"]] :caret 0})
                    (run-palette! ui "compare with the file on disk"))
          listed? (await-until 3000 20 #(= ["2: none here, 1 line on disk: (def a 1)"] (lpane/row-texts (:locations-pane ui))))
          _ (on-gtk (support/place-cursor! (:buf ed) 0)
                    (run-palette! ui "go to the next place in the list"))
          stepped? (await-until 3000 20 #(= 1 (first (g/cursor-line-col (:buf ed)))))
          _ (on-gtk (run-palette! ui "take the other version here"))
          took? (await-until 3000 20 #(= disk (editor/editor-text ed)))
          cleared? (await-until 3000 20 #(and (empty? (lpane/row-texts (:locations-pane ui)))
                                              (= "; No differences from disk." (status ui))))]
      (on-gtk (support/check :compare-line-only-on-disk (and listed? stepped? took? cleared?)
                             {:listed listed? :stepped stepped? :took took? :cleared cleared?
                              :rows (lpane/row-texts (:locations-pane ui)) :status (status ui)
                              :text (editor/editor-text ed)})
              (editor/mark-saved! ed)
              ((:close-tab! ui) (:id ((:active-tab* ui))))))))

(defn- run-active-palette!
  "run-palette! on the active tab's own palette, which is the one
  :open-palette!* opens: (:palette ui) is the first tab's, so a row with
  several tabs open must use this. GTK-thread only."
  [ui query]
  ((:open-palette!* ui))
  (let [p (:palette ((:active-tab* ui)))]
    (palette/set-query! p query)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn row-compare-with-tab
  "Compare with tab: b.clj lists the active tab's difference from b.clj's
  tab, saved or not. Take reads the other tab as it is now, so an edit made
  there after the compare is what comes back, and with no differences left
  the list goes."
  [ui]
  (go-row :compare-with-tab support/check
    (let [d (temp-dirs/temp-dir! "jonquil-selftest-compare-tab-")
          fa (io/file d "a.clj")
          fb (io/file d "b.clj")
          _ (spit fa "(ns a)\n(def x 1)\n(def y 2)\n")
          _ (spit fb "(ns a)\n(def x 10)\n(def y 2)\n")
          _ (on-gtk ((:open-file! ui) (.getPath fb)))
          eb (on-gtk (:ed ((:active-tab* ui))))
          _ (on-gtk ((:open-file! ui) (.getPath fa)))
          ea (on-gtk (:ed ((:active-tab* ui))))
          _ (on-gtk (run-active-palette! ui "compare with tab: b.clj"))
          listed? (await-until 3000 20 #(= ["2: 1 line here, 1 line in b.clj: (def x 10)"]
                                           (lpane/row-texts (:locations-pane ui))))
          _ (on-gtk (editor/apply-op-plain! eb {:edits [[7 17 "(def x 20)"]] :caret 7})
                    (support/place-cursor! (:buf ea) 9)
                    (run-active-palette! ui "take the other version here"))
          took? (await-until 3000 20 #(= "(ns a)\n(def x 20)\n(def y 2)\n" (editor/editor-text ea)))
          cleared? (await-until 3000 20 #(and (empty? (lpane/row-texts (:locations-pane ui)))
                                              (= "; No differences from b.clj." (status ui))))]
      (on-gtk (support/check :compare-with-tab (and listed? took? cleared?)
                             {:listed listed? :took took? :cleared cleared?
                              :rows (lpane/row-texts (:locations-pane ui)) :status (status ui)
                              :a (editor/editor-text ea) :b (editor/editor-text eb)})
              ;; b.clj may have filled the launch tab, so keep the first
              ;; tab, as the usages rows do: closing the last closes the window
              (doseq [t ((:tabs* ui))] (editor/mark-saved! (:ed t)))
              (doseq [t (rest ((:tabs* ui)))] ((:close-tab! ui) (:id t)))
              ((:select-tab! ui) (:id (first ((:tabs* ui)))))))))

(defn row-compare-with-last-run
  "Before a whole Run there's nothing to compare with; after one, Compare
  with the last Run lists the line edited since, and Take puts the run
  version back, after which there are no differences."
  [ui]
  (go-row :compare-with-last-run support/check
    (let [f (io/file (temp-dirs/temp-dir! "jonquil-selftest-compare-run-") "r.clj")
          ran "(ns lrun)\n(def a 1)\n"
          _ (spit f ran)
          _ (on-gtk ((:open-file! ui) (.getPath f)))
          ed (on-gtk (:ed ((:active-tab* ui))))
          _ (on-gtk (run-active-palette! ui "compare with the last run"))
          not-yet? (await-until 3000 20 #(= "; This tab hasn't had a whole Run that ended well yet." (status ui)))
          _ (on-gtk ((:run!* ui)))
          recorded? (await-until 8000 20 #(= ran @(:last-run (:changed ed))))
          _ (on-gtk (editor/apply-op-plain! ed {:edits [[0 (count (editor/editor-text ed)) "(ns lrun)\n(def a 2)\n"]] :caret 0})
                    (run-active-palette! ui "compare with the last run"))
          listed? (await-until 3000 20 #(= ["2: 1 line here, 1 line in the last Run: (def a 1)"]
                                           (lpane/row-texts (:locations-pane ui))))
          _ (on-gtk (support/place-cursor! (:buf ed) 12)
                    (run-active-palette! ui "take the other version here"))
          took? (await-until 3000 20 #(= ran (editor/editor-text ed)))
          cleared? (await-until 3000 20 #(and (empty? (lpane/row-texts (:locations-pane ui)))
                                              (= "; No differences from the last Run." (status ui))))]
      (on-gtk (support/check :compare-with-last-run (and not-yet? recorded? listed? took? cleared?)
                             {:not-yet not-yet? :recorded recorded? :listed listed? :took took? :cleared cleared?
                              :rows (lpane/row-texts (:locations-pane ui)) :status (status ui)
                              :text (editor/editor-text ed)})
              ;; r.clj may have filled the launch tab: keep the first tab
              (doseq [t ((:tabs* ui))] (editor/mark-saved! (:ed t)))
              (doseq [t (rest ((:tabs* ui)))] ((:close-tab! ui) (:id t)))
              ((:select-tab! ui) (:id (first ((:tabs* ui)))))))))
