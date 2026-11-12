(ns gtk-selftest-window.source-pane
  "The source panel widget in isolation, in its own transient window: what
  it shows for a location, its header and Open button, its footer notes
  and errors, and its own history. The window's wiring is covered by the
  JVM group (dev/gtk_selftest_clj/source.clj).

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [selftest-keys]
            [clojure.core.async :refer [<! timeout]]
            [clojure.java.io :as io]
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
                                     (selftest-keys/watch! win)
                                     (g/gtk-window-present win)
                                     {:win win :pane pane}))]
    (try (f pane)
         (finally (on-gtk (g/gtk-window-close win))))))

(defn- caret-line [pane] (inc (first (g/cursor-line-col (:buf (:editor pane))))))

(defn- caret-rect+height
  "[[x y w h] view-height] for pane's own editor, GTK-thread only."
  [pane]
  (let [view (:view (:editor pane))]
    [(g/caret-window-rect view) (g/gtk-widget-get-height view)]))

(defn row-source-pane-shows-line
  "A long editable file (2000 lines), line 1500: the text loads, the caret
  is on line 1500, the header names the file, Open in window is sensitive,
  the footer shows the key hints, and the caret's own rectangle actually
  lands inside the view -- not just that scroll_to_mark was called once,
  but that the panel really scrolled there."
  [_ui]
  (go-row :source-pane-shows-line support/check
    (with-source-pane
      (fn [pane]
        (let [dir (temp-dirs/temp-dir! "jonquil-selftest-source-")
              f (io/file dir "x.clj")
              _ (spit f (numbered-lines 2000))
              r (on-gtk (source-pane/open-location! pane {:path (.getPath f) :line 1500 :editable? true :label "x.clj"}))
              visible? (await-until 3000 20 #(source-pane/caret-visible? pane))
              [rect view-height] (on-gtk (caret-rect+height pane))
              v (on-gtk {:r r :line (caret-line pane) :header (g/label-text (:header pane))
                         :footer (g/label-text (:footer pane))
                         :open (g/gtk-widget-get-sensitive (:open-btn pane))
                         :starts (str/starts-with? (editor/editor-text (:editor pane)) "(def l1 1)")})]
          (on-gtk (support/check :source-pane-shows-line
                                 (and (nil? (:r v)) (= 1500 (:line v)) (= "x.clj" (:header v))
                                      (= source-pane/hint-text (:footer v)) (= 1 (:open v)) (:starts v) visible?)
                                 (assoc v :visible visible? :rect rect :view-height view-height))))))))

(def ^:private USER-SCROLL-WINS-THRESHOLD
  ;; MEASURED (the adjustment does not stay at 0): even with the
  ;; cancel working correctly, GTK's own internal re-validation of a
  ;; freshly loaded, very long buffer nudges the vadjustment on its own,
  ;; independent of the panel's own re-scroll timer -- confirmed by a probe
  ;; that disabled arm-scroll-settle! entirely (so nothing
  ;; ever touches the adjustment again): the SAME 752.0 value still
  ;; appeared after 600 ms, twice, across separate runs. Re-scrolling
  ;; TOWARD line 1500 (the uncancelled failure mode, mutation-checked
  ;; below) reaches the tens of thousands (22367 measured). This
  ;; threshold sits with a wide margin on both sides.
  5000)

(defn row-source-pane-user-scroll-wins
  "A person's own scroll cancels the panel's re-scroll settling instead of
  being fought by it: open the 2000-line file at line
  1500, call the panel's own :on-user-scroll! handler at once -- the same
  fn GtkEventControllerScroll's real \"scroll\" signal calls, since there
  is no synthesizable GdkScroll event to drive here -- force the
  vadjustment to 0, then wait out the settle window. Absence has no
  positive condition to poll for (run_repl.clj's own row-run-selection-
  blank reasoning), so this waits the full window rather than polling for
  something that should never happen: an uncancelled settle re-scrolling
  toward line 1500, which would land in the tens of thousands (see
  USER-SCROLL-WINS-THRESHOLD's own comment), well before 600 ms pass."
  [_ui]
  (go-row :source-pane-user-scroll-wins support/check
    (with-source-pane
      (fn [pane]
        (let [dir (temp-dirs/temp-dir! "jonquil-selftest-source-")
              f (io/file dir "x.clj")
              _ (spit f (numbered-lines 2000))
              vadj (on-gtk
                    (source-pane/open-location! pane {:path (.getPath f) :line 1500 :editable? true :label "x.clj"})
                    ((:on-user-scroll! pane) nil 0.0 1.0 nil)
                    (let [vadj (g/gtk-scrollable-get-vadjustment (:view (:editor pane)))]
                      (g/gtk-adjustment-set-value vadj 0.0)
                      vadj))]
          (<! (timeout 600))
          (on-gtk (let [value (g/gtk-adjustment-get-value vadj)]
                    (support/check :source-pane-user-scroll-wins
                                   (< value USER-SCROLL-WINS-THRESHOLD) {:value value}))))))))

(defn row-source-pane-hide-stops-settle
  "Hiding the panel (source-pane/cancel-scroll-settle!, the same public fn
  jonquil.gtk.window.help's close-source-panel! (through
  jonquil.gtk.window.right-slot's hide-right-panel!) call when the
  source root hides) stops the settle timer in flight instead of leaving
  it to keep re-scrolling a view nobody's looking at. Same shape as row-source-pane-user-scroll-wins, above,
  with cancel-scroll-settle! in place of :on-user-scroll! as the thing
  that's supposed to stop it: open the 2000-line file at line 1500,
  cancel at once the way hiding does, then force the vadjustment to 0 and
  wait out the settle window. An armed settle timer that ignored the
  cancel would re-scroll back toward line 1500 (the tens of thousands
  USER-SCROLL-WINS-THRESHOLD's own comment measured), well before 600 ms
  pass; this also checks scroll-armed? went back to false, since a
  cancelled timer removes itself on its very next tick."
  [_ui]
  (go-row :source-pane-hide-stops-settle support/check
    (with-source-pane
      (fn [pane]
        (let [dir (temp-dirs/temp-dir! "jonquil-selftest-source-")
              f (io/file dir "x.clj")
              _ (spit f (numbered-lines 2000))
              vadj (on-gtk
                    (source-pane/open-location! pane {:path (.getPath f) :line 1500 :editable? true :label "x.clj"})
                    (source-pane/cancel-scroll-settle! (:scroll-cancelled? pane))
                    (let [vadj (g/gtk-scrollable-get-vadjustment (:view (:editor pane)))]
                      (g/gtk-adjustment-set-value vadj 0.0)
                      vadj))]
          (<! (timeout 600))
          (on-gtk (let [value (g/gtk-adjustment-get-value vadj)
                        armed @(:scroll-armed? pane)]
                    (support/check :source-pane-hide-stops-settle
                                   (and (< value USER-SCROLL-WINS-THRESHOLD) (false? armed))
                                   {:value value :armed armed}))))))))

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

(defn row-source-pane-wiring
  "Open in window and Close reach the registered :on-open and :on-close
  (the Open button is insensitive here, but the signal doesn't check), a
  throw in a handler lands on the footer, and so does a throw in the
  panel's own scroll handler."
  [_ui]
  (go-row :source-pane-wiring support/check
    (with-source-pane
      (fn [pane]
        (let [calls (atom [])]
          (try
            (on-gtk
             (source-pane/set-handlers! pane {:on-open (fn [] (swap! calls conj :open))
                                              :on-close (fn [] (swap! calls conj :close))})
             (g/g-signal-emit-by-name (:open-btn pane) "clicked")
             (g/g-signal-emit-by-name (:close-btn pane) "clicked")
             (source-pane/set-handlers! pane {:on-close (fn [] (throw (ex-info "boom" {})))})
             (g/g-signal-emit-by-name (:close-btn pane) "clicked")
             (let [boom (g/label-text (:footer pane))
                   scrolled (with-redefs-fn {#'source-pane/cancel-scroll-settle!
                                             (fn [_] (throw (ex-info "late" {})))}
                              (fn [] ((:on-user-scroll! pane) nil 0.0 0.0 nil)))
                   late (g/label-text (:footer pane))]
               (support/check :source-pane-wiring
                              (and (= [:open :close] @calls) (= "; boom" boom)
                                   (= 0 scrolled) (= "; late" late))
                              {:calls @calls :boom boom :scrolled scrolled :late late})))
            (finally (on-gtk (source-pane/set-handlers! pane {})))))))))
