(ns gtk-selftest-window.file-tree-pane
  "jonquil.gtk.file-tree-pane in isolation, each row
  on a fresh pane in its own throwaway window over a temp folder tree.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [clojure.java.io :as io]
            [gtk-async :as gtk-async :refer [go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.file-tree :as ft]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as pane]
            [temp-dirs]))

(defn- tree!
  "A temp folder with src/a.clj, src/b/c.clj, README.md and .git/x; its
  canonical path."
  []
  (let [root (temp-dirs/temp-dir! "jonquil-selftest-tree-")]
    (doseq [rel ["src/a.clj" "src/b/c.clj" "README.md" ".git/x"]]
      (let [f (io/file root rel)] (io/make-parents f) (spit f "")))
    (.getCanonicalPath (io/file root))))

(defn- with-pane
  "A fresh pane in its own shown window; (f pane opened) runs in the calling
  go block, opened an atom of the paths on-open got; then the window
  closes. extra is merged over the pane's opts (the file-operation
  callbacks)."
  ([f] (with-pane {} f))
  ([extra f]
   (let [opened (atom [])
         {:keys [win p]} (on-gtk (let [win (g/gtk-window-new)
                                       p (pane/make-pane (merge {:on-open (fn [path] (swap! opened conj path))
                                                                 :on-open-folder (fn [] nil)
                                                                 :on-error (fn [_] nil)}
                                                                extra))]
                                   (g/gtk-window-set-default-size win 300 500)
                                   (g/gtk-window-set-child win (:root p))
                                   (g/gtk-window-present win)
                                   {:win win :p p}))]
     (try (f p opened)
          (finally (on-gtk (g/gtk-window-close win)))))))

(defn- names [p] (mapv :name (pane/visible-rows p)))

(defn- activate! [p name]
  (let [i (first (keep-indexed (fn [i r] (when (= name (:name r)) i)) (pane/visible-rows p)))]
    (pane/activate-index! p i)))

(defn row-tree-pane-shows-rows
  "A model's rows: src and README.md (dimmed), no .git; the header names
  the root."
  [_ui]
  (go-row :tree-pane-shows-rows support/check
    (with-pane
      (fn [p _]
        (on-gtk (let [root (tree!)
                      _ (pane/show-model! p (ft/load-model root))
                      readme (second (pane/row-widgets p))
                      v {:names (names p) :rows (count (pane/row-widgets p))
                         :readme-dim (g/gtk-widget-has-css-class (:name-label readme) "dim-label")
                         :header (g/label-text (:header-label p))
                         :root-name (.getName (io/file root))}]
                  (support/check :tree-pane-shows-rows
                                 (and (= ["src" "README.md"] (:names v)) (= 2 (:rows v)) (= 1 (:readme-dim v))
                                      (= (:root-name v) (:header v)))
                                 v)))))))

(defn row-tree-pane-folder-click
  "Activating a folder row expands it."
  [_ui]
  (go-row :tree-pane-folder-click support/check
    (with-pane
      (fn [p _]
        (on-gtk (pane/show-model! p (ft/load-model (tree!)))
                (activate! p "src")
                (let [v {:names (names p) :toggle (g/label-text (:toggle-label (first (pane/row-widgets p))))}]
                  (support/check :tree-pane-folder-click
                                 (and (= ["src" "b" "a.clj" "README.md"] (:names v)) (= "▾" (:toggle v)))
                                 v)))))))

(defn row-tree-pane-file-click
  "Activating a file row calls on-open with its canonical path, once."
  [_ui]
  (go-row :tree-pane-file-click support/check
    (with-pane
      (fn [p opened]
        (on-gtk (let [root (tree!)]
                  (pane/show-model! p (ft/load-model root))
                  (activate! p "src")
                  (activate! p "a.clj")
                  (support/check :tree-pane-file-click (= [(str root "/src/a.clj")] @opened) {:opened @opened})))))))

(defn row-tree-pane-select-path
  "select-path! selects a row without calling on-open."
  [_ui]
  (go-row :tree-pane-select-path support/check
    (with-pane
      (fn [p opened]
        (on-gtk (let [root (tree!)]
                  (pane/show-model! p (ft/toggle (ft/load-model root) (str root "/src")))
                  (pane/select-path! p (str root "/src/a.clj"))
                  (let [v {:selected (pane/selected-path p) :opened @opened}]
                    (support/check :tree-pane-select-path
                                   (and (= (str root "/src/a.clj") (:selected v)) (empty? (:opened v)))
                                   v))))))))

(defn row-tree-pane-refresh-deleted
  "An expanded folder deleted on disk drops out on refresh!, with no error row."
  [_ui]
  (go-row :tree-pane-refresh-deleted support/check
    (with-pane
      (fn [p _]
        (on-gtk (let [root (tree!)
                      b (str root "/src/b")]
                  (pane/show-model! p (-> (ft/load-model root) (ft/toggle (str root "/src")) (ft/toggle b)))
                  (doseq [f (reverse (file-seq (io/file b)))] (io/delete-file f))
                  (pane/refresh! p)
                  (let [v {:names (names p)}]
                    (support/check :tree-pane-refresh-deleted
                                   (= ["src" "a.clj" "README.md"] (:names v)) v))))))))

(defn row-tree-pane-unreadable-root
  "A root that can't be read shows Can't read in the body."
  [_ui]
  (go-row :tree-pane-unreadable-root support/check
    (with-pane
      (fn [p _]
        (on-gtk (let [root (tree!)
                      f (io/file root)]
                  (.setReadable f false false)
                  (try
                    (pane/show-model! p (ft/load-model root))
                    (let [v {:body-visible (g/gtk-widget-get-visible (:body-label p))
                             :body (g/label-text (:body-label p)) :rows (count (pane/row-widgets p))}]
                      (support/check :tree-pane-unreadable-root
                                     (and (= 1 (:body-visible v)) (= (str "Can't read " root) (:body v)) (zero? (:rows v)))
                                     v))
                    (finally (.setReadable f true false)))))))))

(defn row-tree-pane-large-folder
  "A folder of 3,000 files expands in under a second."
  [_ui]
  (go-row :tree-pane-large-folder support/check
    (with-pane
      (fn [p _]
        (on-gtk (let [root (temp-dirs/temp-dir! "jonquil-selftest-tree-")
                      big (io/file root "big")]
                  (.mkdirs big)
                  (dotimes [i 3000] (spit (io/file big (str "f" i ".png")) ""))
                  (let [m (ft/load-model root)
                        _ (pane/show-model! p m)
                        t0 (System/nanoTime)
                        _ (activate! p "big")
                        ms (/ (- (System/nanoTime) t0) 1e6)
                        n (count (pane/row-widgets p))]
                    (support/check :tree-pane-large-folder (and (< ms 1000) (= 3001 n)) {:ms ms :rows n}))))))))

(defn row-tree-pane-select-non-canonical
  "select-path! finds a row from a non-canonical path to the same file:
  temp-dirs hands out /var/... paths, rows hold
  /private/var/... on macOS."
  [_ui]
  (go-row :tree-pane-select-non-canonical support/check
    (with-pane
      (fn [p _]
        (on-gtk (let [raw (temp-dirs/temp-dir! "jonquil-selftest-tree-")
                      _ (doseq [rel ["src/a.clj"]] (let [f (io/file raw rel)] (io/make-parents f) (spit f "")))
                      root (.getCanonicalPath (io/file raw))]
                  (pane/show-model! p (ft/toggle (ft/load-model root) (str root "/src")))
                  (pane/select-path! p (str raw "/src/../src/a.clj"))
                  (let [v {:selected (pane/selected-path p) :raw raw :root root}]
                    (support/check :tree-pane-select-non-canonical
                                   (= (str root "/src/a.clj") (:selected v)) v))))))))

(defn row-tree-pane-deferred-activation-by-path
  "A click's deferred action acts on the row that was clicked even if the
  list was rebuilt in between."
  [_ui]
  (go-row :tree-pane-deferred-activation-by-path support/check
    (with-pane
      (fn [p opened]
        (let [root (on-gtk (let [r (tree!)]
                             (pane/show-model! p (ft/load-model r))
                             ;; README.md is row 1; emit the row's own activate,
                             ;; which defers, then rebuild with src expanded so
                             ;; row 1 becomes src/b before the deferred run.
                             (g/g-signal-emit-by-name (:row (second (pane/row-widgets p))) "activate")
                             (pane/show-model! p (ft/toggle (ft/load-model r) (str r "/src")))
                             r))
              done (gtk-async/await-until 2000 20 #(seq @opened))]
          (on-gtk (support/check :tree-pane-deferred-activation-by-path
                                 (and done (= [(str root "/README.md")] @opened))
                                 {:opened @opened})))))))

;; --- file operations: menu, name entry, keys -----------------

(defn- ops-tree!
  "A temp folder with a/, a/x.clj and b.clj; its canonical path."
  []
  (let [root (temp-dirs/temp-dir! "jonquil-selftest-ops-")]
    (doseq [rel ["a/x.clj" "b.clj"]]
      (let [f (io/file root rel)] (io/make-parents f) (spit f "")))
    (.getCanonicalPath (io/file root))))

(defn- with-ops-pane
  "with-pane over ops-tree!, the pane showing it; (f p root calls result) where
  calls is an atom of the callbacks' recorded [kind & args] and result an
  atom holding what new-file, new-folder and rename return (nil at first)."
  [f]
  (let [calls (atom []) result (atom nil)]
    (with-pane {:on-new-file (fn [dir n] (swap! calls conj [:new-file dir n]) @result)
                :on-new-folder (fn [dir n] (swap! calls conj [:new-folder dir n]) @result)
                :on-rename (fn [path n] (swap! calls conj [:rename path n]) @result)
                :on-trash (fn [path] (swap! calls conj [:trash path]) nil)}
      (fn [p _]
        (let [root (on-gtk (let [r (ops-tree!)] (pane/show-model! p (ft/load-model r)) r))]
          ;; pop down first, so no row closes a window holding a mapped popover
          (try (f p root calls result)
               (finally (on-gtk (g/gtk-popover-popdown (:popover p))))))))))

(def ^:private FOUR ["New File…" "New Folder…" "Rename…" "Move to Trash"])

(defn row-tree-pane-menu-items
  "A file row and a folder row each offer four items; the root offers two."
  [_ui]
  (go-row :tree-pane-menu-items support/check
    (with-ops-pane
      (fn [p root _ _]
        (on-gtk (let [items (fn [path] (pane/open-menu! p path) (pane/menu-items p))
                      v {:file (items (str root "/b.clj")) :folder (items (str root "/a")) :root (items nil)}]
                  (support/check :tree-pane-menu-items
                                 (and (= FOUR (:file v)) (= FOUR (:folder v)) (= (take 2 FOUR) (:root v)))
                                 v)))))))

(defn row-tree-pane-new-file-flow
  "New File... on folder a: heading, empty and existing names refused with
  the entry open, a free name reaches the callback with a's canonical path
  and closes the menu."
  [_ui]
  (go-row :tree-pane-new-file-flow support/check
    (with-ops-pane
      (fn [p root calls _]
        (on-gtk (let [a (str root "/a")
                      _ (do (pane/open-menu! p a) (pane/press-item! p "New File…"))
                      heading (:heading (pane/menu-state p))
                      _ (pane/submit-name! p "")
                      s1 (pane/menu-state p) c1 @calls
                      _ (pane/submit-name! p "x.clj")
                      s2 (pane/menu-state p) c2 @calls
                      _ (pane/submit-name! p "y.clj")
                      s3 (pane/menu-state p)
                      v {:heading heading :s1 (:problem s1) :open1 (:open? s1) :c1 c1
                         :s2 (:problem s2) :c2 c2 :c3 @calls :open3 (:open? s3)}]
                  (support/check :tree-pane-new-file-flow
                                 (and (= "New file in a" heading)
                                      (= "Type a name." (:s1 v)) (:open1 v) (empty? c1)
                                      (= "x.clj already exists here." (:s2 v)) (empty? c2)
                                      (= [[:new-file a "y.clj"]] (:c3 v)) (false? (:open3 v)))
                                 v)))))))

(defn row-tree-pane-rename-prefill
  "Rename... prefills the name: a file selects the part before its last dot,
  a folder the whole name."
  [_ui]
  (go-row :tree-pane-rename-prefill support/check
    (with-ops-pane
      (fn [p root _ _]
        (on-gtk (let [probe (fn [path]
                              (pane/open-menu! p path)
                              (pane/press-item! p "Rename…")
                              {:text (g/editable-text (:entry p))
                               :sel (g/editable-selection-bounds (:entry p))
                               :heading (:heading (pane/menu-state p))})
                      v {:file (probe (str root "/b.clj")) :folder (probe (str root "/a"))}]
                  (support/check :tree-pane-rename-prefill
                                 (and (= {:text "b.clj" :sel [0 1] :heading "Rename b.clj"} (:file v))
                                      (= {:text "a" :sel [0 1] :heading "Rename a"} (:folder v)))
                                 v)))))))

(defn row-tree-pane-callback-error-shown
  "A callback's returned string shows in the problem label and the entry
  stays open."
  [_ui]
  (go-row :tree-pane-callback-error-shown support/check
    (with-ops-pane
      (fn [p root _ result]
        (on-gtk (let [_ (reset! result "disk full")
                      _ (do (pane/open-menu! p (str root "/a")) (pane/press-item! p "New Folder…"))
                      _ (pane/submit-name! p "zz")
                      s (pane/menu-state p)
                      v {:state s :label (g/label-text (:problem-label p))}]
                  (support/check :tree-pane-callback-error-shown
                                 (and (= "disk full" (:problem s)) (= "disk full" (:label v))
                                      (:open? s) (= :name (:mode s)))
                                 v)))))))

(defn row-tree-pane-menu-target-survives-refresh
  "The menu's target is recorded at open time: a refresh in between
  doesn't redirect Move to Trash."
  [_ui]
  (go-row :tree-pane-menu-target-survives-refresh support/check
    (with-ops-pane
      (fn [p root calls _]
        (on-gtk (let [b (str root "/b.clj")]
                  (pane/open-menu! p b)
                  (pane/refresh! p)
                  (pane/press-item! p "Move to Trash")
                  (support/check :tree-pane-menu-target-survives-refresh
                                 (= [[:trash b]] @calls) {:calls @calls})))))))

(defn row-tree-pane-keys
  "F2 opens rename on the selected row and Delete trashes it, through the
  list's key handler."
  [_ui]
  (go-row :tree-pane-keys support/check
    (with-ops-pane
      (fn [p root calls _]
        (let [b (str root "/b.clj")
              r1 (on-gtk (pane/select-path! p b) (pane/handle-key! p 0xffbf 0))
              renamed (gtk-async/await-until 2000 20 #(= :name (:mode @(:menu p))))
              s (on-gtk (pane/menu-state p))
              _ (on-gtk (pane/submit-name! p "b.clj"))
              r2 (on-gtk (pane/handle-key! p 0xffff 0))
              trashed (gtk-async/await-until 2000 20 #(seq @calls))
              r3 (on-gtk (pane/handle-key! p 0xff08 0))
              r4 (on-gtk (pane/handle-key! p 0xff08 g/CONTROL-MASK))
              later (gtk-async/await-until 2000 20 #(= 2 (count @calls)))
              v {:r [r1 r2 r3 r4] :renamed renamed :state s :trashed trashed :calls @calls :later later}]
          (on-gtk (support/check :tree-pane-keys
                                 (and renamed (= "Rename b.clj" (:heading s)) (= b (:target s))
                                      trashed later (= [1 1 0 1] (:r v))
                                      (= [[:trash b] [:trash b]] (:calls v)))
                                 v)))))))

(defn- list-focused?
  "True when keyboard focus is the pane's list or one of its rows."
  [p]
  (let [f (g/gtk-root-get-focus (g/gtk-widget-get-root (:root p)))
        l (:list p)]
    (and (not (ffi/null? f))
         (or (= f l) (not (zero? (g/gtk-widget-is-ancestor f l)))))))

(defn row-tree-pane-stale-menu-actions
  "F2 whose row vanished before its deferred run opens nothing and calls no
  stub; press-item! with the menu closed does nothing."
  [_ui]
  (go-row :tree-pane-stale-menu-actions support/check
    (with-ops-pane
      (fn [p root calls _]
        (let [b (str root "/b.clj")
              _ (on-gtk (pane/select-path! p b)
                        (pane/handle-key! p 0xffbf 0)      ; defers the rename
                        (.delete (io/file b))
                        (pane/refresh! p))                  ; the row is gone before the run
              _ (Thread/sleep 300)
              s (on-gtk (pane/menu-state p))
              closed (on-gtk (pane/press-item! p "Rename…")
                             (pane/press-item! p "Move to Trash")
                             {:state (pane/menu-state p) :calls @calls})
              v {:after-f2 s :closed closed :calls @calls}]
          (on-gtk (support/check :tree-pane-stale-menu-actions
                                 (and (false? (:open? s)) (nil? (:mode s)) (empty? @calls)
                                      (nil? (:mode (:state closed))) (empty? (:calls closed)))
                                 v)))))))

(defn row-tree-pane-menu-focus-and-anchor
  "After a submit the list has focus again; the root menu points inside the
  list area (not past the window); a click point is where the popover points."
  [_ui]
  (go-row :tree-pane-menu-focus-and-anchor support/check
    (with-ops-pane
      (fn [p root _ _]
        (let [anchors (on-gtk (pane/open-menu! p nil)
                              (let [a (g/popover-pointing-to (:popover p))
                                    _ (pane/open-menu! p nil [40 30])
                                    b (g/popover-pointing-to (:popover p))
                                    scroll-h (g/gtk-widget-get-height (:scroll p))
                                    scroll-y (first (rest (g/compute-point (:scroll p) (:root p) 0 0)))]
                                {:root a :click b :scroll-y (long scroll-y) :scroll-h scroll-h}))
              _ (on-gtk (pane/open-menu! p (str root "/a")) (pane/press-item! p "New File…")
                        (pane/submit-name! p "y.clj"))
              focused (loop [n 0]
                        (let [ok (on-gtk (list-focused? p))]
                          (if (or ok (> n 50)) ok (do (Thread/sleep 20) (recur (inc n))))))
              v {:anchors anchors :focused focused}
              [_ ay _ ah] (:root anchors)
              [_ cy _ ch] (:click anchors)]
          (on-gtk (support/check :tree-pane-menu-focus-and-anchor
                                 (and focused
                                      (= (:scroll-y anchors) ay) (= 1 ah)
                                      (< ay (+ (:scroll-y anchors) (:scroll-h anchors)))
                                      (= 1 ch) (> cy ay))
                                 v)))))))

(defn row-tree-pane-error-row-no-menu
  "An expanded folder that can't be read shows a
  \"(can't read)\" row; the menu on that row's path (what a right click on
  it passes) and Menu with it selected open nothing, rather than the full
  menu for its parent folder."
  [_ui]
  (go-row :tree-pane-error-row-no-menu support/check
    (with-ops-pane
      (fn [p root _ _]
        (let [a (str root "/a")
              f (io/file a)
              v (on-gtk (.setReadable f false false)
                        (try
                          (pane/show-model! p (ft/toggle (ft/load-model root) a))
                          (let [err (first (filter :error? (pane/visible-rows p)))
                                opened (pane/open-menu! p (:path err))
                                s1 (pane/menu-state p)]
                            (pane/select-path! p (:path err))
                            {:err (select-keys err [:path :name]) :opened opened :s1 s1})
                          (finally (.setReadable f true false))))
              ;; select-path! canonicalises, so select the error row by index
              k (on-gtk (let [i (first (keep-indexed (fn [i r] (when (:error? r) i)) (pane/visible-rows p)))]
                          (g/gtk-list-box-select-row (:list p) (g/gtk-list-box-get-row-at-index (:list p) i))
                          (pane/handle-key! p 0xff67 0)))
              _ (Thread/sleep 200)
              v (assoc v :menu-key k :s2 (on-gtk (pane/menu-state p)))]
          (on-gtk (support/check :tree-pane-error-row-no-menu
                                 (and (= "(can't read)" (:name (:err v)))
                                      (nil? (:opened v)) (false? (:open? (:s1 v)))
                                      (= 0 (:menu-key v)) (false? (:open? (:s2 v))))
                                 v)))))))
