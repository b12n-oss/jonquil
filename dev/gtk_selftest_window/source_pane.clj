(ns gtk-selftest-window.source-pane
  "The source panel widget in isolation, in its own transient window: what
  it shows for a location, its header and Open button, its footer notes
  and errors, and its own history. The window's wiring is covered by the
  JVM group (dev/gtk_selftest_clj/source.clj).

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.source :as source]
            [temp-dirs]))

(defn- numbered-lines
  "n lines \"(def l1 1)\" ... so line k is easy to recognise."
  [n]
  (apply str (map (fn [i] (str "(def l" i " " i ")\n")) (range 1 (inc n)))))

(defn- write-jar! [path entries]
  (with-open [z (java.util.zip.ZipOutputStream. (java.io.FileOutputStream. ^String path))]
    (doseq [[n text] entries]
      (.putNextEntry z (java.util.zip.ZipEntry. ^String n))
      (.write z (.getBytes ^String text "UTF-8"))
      (.closeEntry z)))
  path)

(defn- with-source-pane
  "A fresh panel in its own transient window, shown; (f pane) runs in the
  calling go block; then the window closes."
  [f]
  (let [{:keys [win pane]} (on-gtk (let [win (g/gtk-window-new)
                                         pane (source-pane/make-pane)]
                                     (g/gtk-window-set-default-size win 420 500)
                                     (g/gtk-window-set-child win (:root pane))
                                     (g/gtk-window-present win)
                                     {:win win :pane pane}))]
    (try (f pane)
         (finally (on-gtk (g/gtk-window-close win))))))

(defn- caret-line [pane] (inc (first (g/cursor-line-col (:buf (:editor pane))))))

(defn row-source-pane-shows-line
  "An editable file on line 120 of 200: the text loads, the caret is on line
  120, the header names the file, Open in window is sensitive, the footer
  shows the key hints, and the view has scrolled down (vadjustment > 0)."
  [_ui]
  (go-row :source-pane-shows-line support/check
    (with-source-pane
      (fn [pane]
        (let [dir (temp-dirs/temp-dir! "jonquil-selftest-source-")
              f (io/file dir "x.clj")
              _ (spit f (numbered-lines 200))
              r (on-gtk (source-pane/open-location! pane {:path (.getPath f) :line 120 :editable? true :label "x.clj"}))
              scrolled? (await-until 2000 20 #(pos? (g/gtk-adjustment-get-value
                                                     (g/gtk-scrollable-get-vadjustment (:view (:editor pane))))))
              v (on-gtk {:r r :line (caret-line pane) :header (g/label-text (:header pane))
                         :footer (g/label-text (:footer pane))
                         :open (g/gtk-widget-get-sensitive (:open-btn pane))
                         :starts (str/starts-with? (editor/editor-text (:editor pane)) "(def l1 1)")})]
          (on-gtk (support/check :source-pane-shows-line
                                 (and (nil? (:r v)) (= 120 (:line v)) (= "x.clj" (:header v))
                                      (= source-pane/hint-text (:footer v)) (= 1 (:open v)) (:starts v) scrolled?)
                                 (assoc v :scrolled scrolled?))))))))

(defn row-source-pane-jar-read-only
  "A jar entry: the header names the entry and its jar and says read-only,
  Open in window is insensitive and its tooltip says why."
  [_ui]
  (go-row :source-pane-jar-read-only support/check
    (with-source-pane
      (fn [pane]
        (let [dir (temp-dirs/temp-dir! "jonquil-selftest-source-")
              jar (write-jar! (str dir "/lib.jar") {"demo/lib.clj" (numbered-lines 30)})
              loc (source/location {:file (str "jar:file:" jar "!/demo/lib.clj") :line 12} {:project-dir dir})
              v (on-gtk (source-pane/open-location! pane loc)
                        {:line (caret-line pane) :header (g/label-text (:header pane))
                         :open (g/gtk-widget-get-sensitive (:open-btn pane))
                         :tip (g/tooltip-text (:open-btn pane))})]
          (on-gtk (support/check :source-pane-jar-read-only
                                 (and (= 12 (:line v)) (= "lib.clj in lib.jar  · read-only" (:header v))
                                      (= 0 (:open v)) (= (source-pane/read-only-reason loc) (:tip v)))
                                 v)))))))

(defn row-source-pane-missing-entry
  "A jar without the entry: open-location! answers {:error ...}, the footer
  says which entry is missing, and nothing was loaded (current is nil)."
  [_ui]
  (go-row :source-pane-missing-entry support/check
    (with-source-pane
      (fn [pane]
        (let [dir (temp-dirs/temp-dir! "jonquil-selftest-source-")
              jar (write-jar! (str dir "/lib.jar") {"demo/lib.clj" "x"})
              v (on-gtk {:r (source-pane/open-location! pane {:jar jar :entry "nope/ghost.clj" :line 1
                                                              :editable? false :label "ghost.clj in lib.jar"})
                         :footer (g/label-text (:footer pane))
                         :current (source-pane/current pane)})]
          (on-gtk (support/check :source-pane-missing-entry
                                 (and (= {:error "nope/ghost.clj isn't in lib.jar."} (:r v))
                                      (= "nope/ghost.clj isn't in lib.jar." (:footer v))
                                      (nil? (:current v)))
                                 v)))))))

(defn row-source-pane-line-moved
  "Line 999 of a 10-line file: the caret goes to the last line and the
  footer says the line moved."
  [_ui]
  (go-row :source-pane-line-moved support/check
    (with-source-pane
      (fn [pane]
        (let [dir (temp-dirs/temp-dir! "jonquil-selftest-source-")
              f (io/file dir "x.clj")
              _ (spit f (numbered-lines 10))
              v (on-gtk (source-pane/open-location! pane {:path (.getPath f) :line 999 :editable? true :label "x.clj"})
                        {:line (caret-line pane) :footer (g/label-text (:footer pane))})]
          (on-gtk (support/check :source-pane-line-moved
                                 (and (= 11 (:line v)) (= source/moved-note (:footer v)))
                                 v)))))))

(defn row-source-pane-history
  "Open A on line 5, move within A to line 8, open B, then back three
  times: A at line 8, A at line 5, then false (empty history)."
  [_ui]
  (go-row :source-pane-history support/check
    (with-source-pane
      (fn [pane]
        (let [dir (temp-dirs/temp-dir! "jonquil-selftest-source-")
              a (io/file dir "a.clj")
              b (io/file dir "b.clj")
              _ (spit a (numbered-lines 20))
              _ (spit b (numbered-lines 20))
              v (on-gtk
                 (source-pane/open-location! pane {:path (.getPath a) :line 5 :editable? true :label "a.clj"})
                 (source-pane/move-to-offset! pane (source/line-offset (editor/editor-text (:editor pane)) 8))
                 (source-pane/open-location! pane {:path (.getPath b) :line 3 :editable? true :label "b.clj"})
                 (let [on-b [(g/label-text (:header pane)) (caret-line pane)]
                       back1 (source-pane/back! pane)
                       after1 [(g/label-text (:header pane)) (caret-line pane)]
                       back2 (source-pane/back! pane)
                       after2 [(g/label-text (:header pane)) (caret-line pane)]
                       back3 (source-pane/back! pane)]
                   {:on-b on-b :back1 back1 :after1 after1 :back2 back2 :after2 after2 :back3 back3}))]
          (on-gtk (support/check :source-pane-history
                                 (= {:on-b ["b.clj" 3] :back1 true :after1 ["a.clj" 8]
                                     :back2 true :after2 ["a.clj" 5] :back3 false}
                                    v)
                                 v)))))))

(defn row-source-pane-back-deleted-file
  "Open A on line 5, open B on line 3, delete A from disk, then back!: the
  entry is dropped, the footer says it couldn't be read, back! reports
  false (it didn't move), and the panel keeps showing B on line 3. A
  second back! also returns false: the history is now empty."
  [_ui]
  (go-row :source-pane-back-deleted-file support/check
    (with-source-pane
      (fn [pane]
        (let [dir (temp-dirs/temp-dir! "jonquil-selftest-source-")
              a (io/file dir "a.clj")
              b (io/file dir "b.clj")
              _ (spit a (numbered-lines 20))
              _ (spit b (numbered-lines 20))
              v (on-gtk
                 (source-pane/open-location! pane {:path (.getPath a) :line 5 :editable? true :label "a.clj"})
                 (source-pane/open-location! pane {:path (.getPath b) :line 3 :editable? true :label "b.clj"})
                 (.delete a)
                 (let [back1 (source-pane/back! pane)
                       after1 {:header (g/label-text (:header pane)) :line (caret-line pane)
                               :footer (g/label-text (:footer pane))}
                       back2 (source-pane/back! pane)]
                   {:back1 back1 :after1 after1 :back2 back2}))]
          (on-gtk (support/check :source-pane-back-deleted-file
                                 (and (false? (:back1 v))
                                      (= "b.clj" (:header (:after1 v)))
                                      (= 3 (:line (:after1 v)))
                                      (str/starts-with? (:footer (:after1 v)) "Couldn't read ")
                                      (false? (:back2 v)))
                                 v)))))))

(defn row-source-pane-same-file-no-reload
  "Opening the location already showing, on another line, only moves the
  caret: the paint generation doesn't move (no reload, no repaint)."
  [_ui]
  (go-row :source-pane-same-file-no-reload support/check
    (with-source-pane
      (fn [pane]
        (let [dir (temp-dirs/temp-dir! "jonquil-selftest-source-")
              f (io/file dir "x.clj")
              _ (spit f (numbered-lines 40))
              loc {:path (.getPath f) :line 3 :editable? true :label "x.clj"}
              v (on-gtk (source-pane/open-location! pane loc)
                        (let [gen (:gen @(:paint (:editor pane)))]
                          (source-pane/open-location! pane (assoc loc :line 30))
                          {:gen-before gen :gen-after (:gen @(:paint (:editor pane))) :line (caret-line pane)}))]
          (on-gtk (support/check :source-pane-same-file-no-reload
                                 (and (= (:gen-before v) (:gen-after v)) (= 30 (:line v)))
                                 v)))))))
