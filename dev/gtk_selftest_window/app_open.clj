(ns gtk-selftest-window.app-open
  "Files from Open With and Dock drops (P5a P2.T1): GApplication's `open`
  signal, and the window's :open-paths! it ends in. Row 1 goes through the
  real g_application_open on the running app; the others call :open-paths!
  directly. Every row leaves only the first tab and the checkout as the
  tree root.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [gtk-selftest-window.tabs :as tabs]
            [jolt.ffi :as ffi]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [temp-dirs]))

(defn- tab-list [ui] ((:tabs* ui)))
(defn- tab-paths [ui] (mapv (fn [t] @(:path t)) (tab-list ui)))

(defn- temp-file!
  [name text]
  (let [f (io/file (temp-dirs/temp-dir! "jonquil-selftest-appopen-") name)]
    (io/make-parents f)
    (spit f text)
    (.getCanonicalPath f)))

(defn- restore!
  "Extra tabs closed, first tab selected, tree rooted at original. GTK-thread only."
  [ui original]
  ((:simulate-folder-result! ui) {:path original})
  (doseq [t (rest (tab-list ui))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first (tab-list ui)))))

(defn- root [ui] (:root @(:state (:file-tree ui))))

(defn row-app-open-two-files
  "g_application_open with two GFiles opens both in new tabs."
  [ui]
  (go-row :app-open-two-files support/check
    (let [original (on-gtk (root ui))
          [a b] (on-gtk (tabs/occupy-first! ui)
                        [(temp-file! "one.clj" "(def one 1)\n") (temp-file! "two.clj" "(def two 2)\n")])]
      (on-gtk (let [arr (ffi/alloc 16)
                    gfs [(g/g-file-new-for-path a) (g/g-file-new-for-path b)]]
                (try
                  (ffi/write arr :pointer (first gfs) 0)
                  (ffi/write arr :pointer (second gfs) 8)
                  (g/g-application-open (:app ui) arr 2 "")
                  (finally
                    (doseq [f gfs] (g/g-object-unref f))
                    (ffi/free arr)))))
      (let [ok (await-until 3000 20 #(let [ps (tab-paths ui)] (and (some #{a} ps) (some #{b} ps))))]
        (on-gtk (try
                  (support/check :app-open-two-files ok {:paths (tab-paths ui)})
                  (finally (restore! ui original))))))))

(defn row-app-open-folder
  "A directory roots the tree there and shows it, even when it was hidden."
  [ui]
  (go-row :app-open-folder support/check
    (on-gtk (let [original (root ui)
                  was-visible? ((:file-tree-visible?* ui))
                  d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-appopen-")))]
              (try
                (when was-visible? ((:toggle-file-tree!* ui)))
                (let [hidden? (not ((:file-tree-visible?* ui)))]
                  ((:open-paths! ui) [d])
                  (support/check :app-open-folder
                                 (and hidden? (= d (root ui)) ((:file-tree-visible?* ui)))
                                 {:hidden-first hidden? :root (root ui) :want d
                                  :visible ((:file-tree-visible?* ui))}))
                (finally
                  (restore! ui original)
                  (when (not= was-visible? ((:file-tree-visible?* ui)))
                    ((:toggle-file-tree!* ui)))))))))

(defn row-app-open-missing
  "A path that doesn't exist names itself in the status line; no tab is added."
  [ui]
  (go-row :app-open-missing support/check
    (let [original (on-gtk (root ui))
          n0 (on-gtk (tabs/occupy-first! ui) (count (tab-list ui)))
          missing (str (.getPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-appopen-") "gone.clj")))
          _ (on-gtk ((:open-paths! ui) [missing]))
          ok (await-until 2000 20 #(str/includes? (g/label-text (:status-label ui)) "gone.clj"))]
      (on-gtk (try
                (support/check :app-open-missing (and ok (= n0 (count (tab-list ui))))
                               {:status (g/label-text (:status-label ui)) :n0 n0 :n (count (tab-list ui))})
                (finally (restore! ui original)))))))

(defn row-app-open-already-open
  "A file already in a tab selects that tab instead of adding one."
  [ui]
  (go-row :app-open-already-open support/check
    (on-gtk (let [original (root ui)]
              (try
                (tabs/occupy-first! ui)
                (let [f (temp-file! "again.clj" "(def again 1)\n")
                      _ ((:open-paths! ui) [f])
                      id (:id ((:active-tab* ui)))
                      n1 (count (tab-list ui))
                      _ ((:select-tab! ui) (:id (first (tab-list ui))))
                      _ ((:open-paths! ui) [f])
                      v {:n1 n1 :n2 (count (tab-list ui)) :same (= id (:id ((:active-tab* ui))))}]
                  (support/check :app-open-already-open (and (= 2 (:n1 v)) (= 2 (:n2 v)) (:same v)) v))
                (finally (restore! ui original)))))))

(defn row-app-open-non-local
  "A non-local GFile is named in the status line as not local, and the real
  file opened in the same call still gets its tab."
  [ui]
  (go-row :app-open-non-local support/check
    (let [original (on-gtk (root ui))
          a (on-gtk (tabs/occupy-first! ui) (temp-file! "real.clj" "(def real 1)\n"))]
      (on-gtk (let [arr (ffi/alloc 16)
                    gfs [(g/g-file-new-for-uri "http://example.invalid/x.clj") (g/g-file-new-for-path a)]]
                (try
                  (ffi/write arr :pointer (first gfs) 0)
                  (ffi/write arr :pointer (second gfs) 8)
                  (g/g-application-open (:app ui) arr 2 "")
                  (finally
                    (doseq [f gfs] (g/g-object-unref f))
                    (ffi/free arr)))))
      (let [ok (await-until 3000 20 #(and (some #{a} (tab-paths ui))
                                          (let [st (g/label-text (:status-label ui))]
                                            (and (str/includes? st "http://example.invalid/x.clj")
                                                 (str/includes? st "not a local file")))))]
        (on-gtk (try
                  (support/check :app-open-non-local ok
                                 {:status (g/label-text (:status-label ui)) :paths (tab-paths ui)})
                  (finally (restore! ui original))))))))
