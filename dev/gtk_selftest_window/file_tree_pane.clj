(ns gtk-selftest-window.file-tree-pane
  "jonquil.gtk.file-tree-pane in isolation (file-tree plan P2.T1), each row
  on a fresh pane in its own throwaway window over a temp folder tree.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [clojure.java.io :as io]
            [gtk-async :refer [go-row on-gtk]]
            [gtk-selftest-window.support :as support]
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
  closes."
  [f]
  (let [opened (atom [])
        {:keys [win p]} (on-gtk (let [win (g/gtk-window-new)
                                      p (pane/make-pane {:on-open (fn [path] (swap! opened conj path))
                                                         :on-open-folder (fn [] nil)
                                                         :on-error (fn [_] nil)})]
                                  (g/gtk-window-set-default-size win 300 500)
                                  (g/gtk-window-set-child win (:root p))
                                  (g/gtk-window-present win)
                                  {:win win :p p}))]
    (try (f p opened)
         (finally (on-gtk (g/gtk-window-close win))))))

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
  "A folder of 3,000 files expands in under a second (Review Focus 1)."
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
