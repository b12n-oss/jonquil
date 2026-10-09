(ns gtk-selftest-window.tabs
  "Tabs in the real window (tabs plan P2.T3 to P2.T5): new, open, switch
  and close, labels, popovers across a switch, the theme across tabs, Run
  per tab, and the close alerts. Every row leaves the window with only its
  first tab (ruling 3: rows never close the first tab), through
  close-extra-tabs! in a finally.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [clojure.core.async :refer [<! go]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.gtk.tab :as tab]
            [temp-dirs]))

(defn- tabs [ui] ((:tabs* ui)))
(defn- active [ui] ((:active-tab* ui)))
(defn- labels [ui] (mapv (fn [t] (g/label-text (:label t))) (tabs ui)))

(defn- close-extra-tabs!
  "Marks every tab but the first saved and closes it, then selects the
  first. GTK-thread only."
  [ui]
  (doseq [t (rest (tabs ui))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first (tabs ui))))
  (reset! (:alert-pending? ui) false))

(defn- temp-file!
  "A file named name holding text in a fresh temp dir; its path."
  [name text]
  (let [f (io/file (temp-dirs/temp-dir! "jonquil-selftest-tabs-") name)]
    (io/make-parents f)
    (spit f text)
    (.getPath f)))

(defn- occupy-first!
  "Gives the first tab text, so Open adds a tab instead of filling it
  (run-row leaves it empty, and possibly Untitled, between rows)."
  [ui]
  (editor/set-text! (:ed (first (tabs ui))) ";; tabs\n"))

(defn- type-into! [t s]
  (g/gtk-text-buffer-insert-interactive-at-cursor (:buf (:ed t)) s -1 1))

(defn- cmd-key! [ui keyval shift?]
  ((:handle-window-key! ui) ffi/null keyval 0
                            (bit-or g/META-MASK (if shift? g/SHIFT-MASK 0)) ffi/null))

(defn row-tab-new-shows-strip
  "Cmd/Ctrl+T's action adds an active Untitled tab and shows the strip;
  closing it hides the strip again."
  [ui]
  (go-row :tab-new-shows-strip support/check
    (on-gtk
     (try
       (let [nb (:notebook ui)
             t2 ((:new-tab!* ui))
             v1 {:n (count (tabs ui)) :strip (g/gtk-notebook-get-show-tabs nb)
                 :active (= (:id t2) (:id (active ui))) :title (g/window-title (:window ui))}
             _ ((:close-tab! ui) (:id t2))
             v2 {:n (count (tabs ui)) :strip (g/gtk-notebook-get-show-tabs nb)}]
         (support/check :tab-new-shows-strip
                        (and (= 2 (:n v1)) (= 1 (:strip v1)) (:active v1) (= "Untitled - Jonquil" (:title v1))
                             (= 1 (:n v2)) (= 0 (:strip v2)))
                        {:with-two v1 :after-close v2}))
       (finally (close-extra-tabs! ui))))))

(defn row-tab-open-replaces-untouched
  "Open into an untouched Untitled tab fills that tab instead of adding one."
  [ui]
  (go-row :tab-open-replaces-untouched support/check
    (on-gtk
     (try
       (let [f (temp-file! "a.clj" "(+ 1 2)\n")
             _ ((:new-tab!* ui))
             _ ((:open-file! ui) f)
             v {:n (count (tabs ui)) :path @(:path (active ui)) :text (editor/editor-text (:ed (active ui)))}]
         (support/check :tab-open-replaces-untouched
                        (and (= 2 (:n v)) (= f (:path v)) (= "(+ 1 2)\n" (:text v)))
                        v))
       (finally (close-extra-tabs! ui))))))

(defn row-tab-open-focuses-existing
  "Opening a file that's already open, through a symlink, selects its tab."
  [ui]
  (go-row :tab-open-focuses-existing support/check
    (on-gtk
     (try
       (let [_ (occupy-first! ui)
             f (temp-file! "b.clj" "(def b 1)\n")
             link (str (.getParent (io/file f)) "/link.clj")
             _ (java.nio.file.Files/createSymbolicLink (.toPath (io/file link)) (.toPath (io/file f))
                                                       (make-array java.nio.file.attribute.FileAttribute 0))
             _ ((:open-file! ui) f)
             fid (:id (active ui))
             n-before (count (tabs ui))
             _ ((:select-tab! ui) (:id (first (tabs ui))))
             _ ((:open-file! ui) link)
             v {:n-before n-before :n (count (tabs ui)) :focused (= fid (:id (active ui)))}]
         (support/check :tab-open-focuses-existing
                        (and (= 2 n-before) (= 2 (:n v)) (:focused v))
                        v))
       (finally (close-extra-tabs! ui))))))

(defn row-tab-switch-keys
  "Cmd/Ctrl+Shift+] and [ step through tabs (wrapping), Cmd/Ctrl+9 picks
  the last and Cmd/Ctrl+2 the second; the title and focus follow."
  [ui]
  (go-row :tab-switch-keys support/check
    (on-gtk
     (try
       (let [_ (occupy-first! ui)
             f2 (temp-file! "two.clj" "2\n")
             f3 (temp-file! "three.clj" "3\n")
             _ ((:open-file! ui) f2)
             _ ((:open-file! ui) f3)
             [t1 t2 t3] (tabs ui)
             win (:window ui)
             at (fn [] {:id (:id (active ui)) :title (g/window-title win)
                        :focus (= (g/gtk-root-get-focus win) (:view (:ed (active ui))))})
             _ ((:select-tab! ui) (:id t1))
             _ (cmd-key! ui 0x7d true)
             s1 (at)
             _ (cmd-key! ui 0x7d true)
             _ (cmd-key! ui 0x7d true)
             s2 (at)
             _ (cmd-key! ui 0x7b true)
             s3 (at)
             _ (cmd-key! ui 0x39 false)
             s4 (at)
             _ (cmd-key! ui 0x32 false)
             s5 (at)]
         (support/check :tab-switch-keys
                        (and (= (:id t2) (:id s1)) (= "two.clj - Jonquil" (:title s1))
                             (= (:id t1) (:id s2)) (= (:id t3) (:id s3)) (= "three.clj - Jonquil" (:title s3))
                             (= (:id t3) (:id s4)) (= (:id t2) (:id s5))
                             (every? :focus [s1 s2 s3 s4 s5]))
                        {:s1 s1 :s2 s2 :s3 s3 :s4 s4 :s5 s5}))
       (finally (close-extra-tabs! ui))))))

(defn row-tab-dirty-label
  "Typing into tab 2 marks its label with ● and leaves tab 1's alone."
  [ui]
  (go-row :tab-dirty-label support/check
    (let [f (on-gtk (temp-file! "dirty.clj" "(def d 1)\n"))]
      (try
        (on-gtk (occupy-first! ui) ((:open-file! ui) f) (type-into! (active ui) "x"))
        (let [ok (await-until 2000 20 #(str/ends-with? (second (labels ui)) " ●"))]
          (on-gtk (support/check :tab-dirty-label
                                 (and ok (not (str/ends-with? (first (labels ui)) " ●")))
                                 {:labels (labels ui)})))
        (finally (on-gtk (close-extra-tabs! ui)))))))

(defn- dirty-second-tab! [ui]
  (occupy-first! ui)
  (let [f (temp-file! "close.clj" "(def c 1)\n")]
    ((:open-file! ui) f)
    (type-into! (active ui) "z")
    (active ui)))

(defn row-tab-close-dirty-cancel
  "Closing a dirty tab asks first; Cancel keeps the tab, its text and its
  dirty mark."
  [ui]
  (go-row :tab-close-dirty-cancel support/check
    (on-gtk
     (try
       (let [t (dirty-second-tab! ui)
             text (editor/editor-text (:ed t))
             _ ((:close-tab! ui) (:id t))
             _ ((:close-tab-choice! ui) (:id t) {:index 2})
             v {:n (count (tabs ui)) :same-text (= text (editor/editor-text (:ed t))) :dirty (editor/dirty? (:ed t))}]
         (support/check :tab-close-dirty-cancel (and (= 2 (:n v)) (:same-text v) (:dirty v)) v))
       (finally (close-extra-tabs! ui))))))

(defn row-tab-close-dirty-discard
  "Don't Save closes the dirty tab; the first tab is active and focused."
  [ui]
  (go-row :tab-close-dirty-discard support/check
    (on-gtk
     (try
       (let [t (dirty-second-tab! ui)
             _ ((:close-tab! ui) (:id t))
             _ ((:close-tab-choice! ui) (:id t) {:index 1})
             first-tab (first (tabs ui))
             v {:n (count (tabs ui)) :active-first (= (:id first-tab) (:id (active ui)))
                :focus (= (g/gtk-root-get-focus (:window ui)) (:view (:ed first-tab)))}]
         (support/check :tab-close-dirty-discard (and (= 1 (:n v)) (:active-first v) (:focus v)) v))
       (finally (close-extra-tabs! ui))))))

(defn row-tab-close-with-open-popover
  "Closing a tab whose quick-doc popover is open: the tab goes, all four of
  its popovers are unparented, and the GTK thread keeps answering (the
  spike's warning loop never let it)."
  [ui]
  (go-row :tab-close-with-open-popover support/check
    (let [t2 (on-gtk (let [t ((:new-tab!* ui))]
                       (editor/set-text! (:ed t) "(map inc [1])")
                       (support/place-cursor! (:buf (:ed t)) 2)
                       ((:quick-doc! (:actions t)))
                       t))
          open? (await-until 2000 20 #(popover/open? (:help-pop t2)))
          held (on-gtk (mapv (fn [p] (g/g-object-ref (:popover p))) (tab/popovers t2)))
          _ (on-gtk ((:close-tab! ui) (:id t2)))
          t0 (System/currentTimeMillis)
          parents (on-gtk (let [r (mapv (fn [w] (ffi/null? (g/gtk-widget-get-parent w))) held)]
                            (run! g/g-object-unref held)
                            r))
          answered-ms (- (System/currentTimeMillis) t0)]
      (on-gtk (try
                (support/check :tab-close-with-open-popover
                               (and open? (= 1 (count (tabs ui))) (every? true? parents) (< answered-ms 1000))
                               {:open? open? :n (count (tabs ui)) :parents-null parents :answered-ms answered-ms})
                (finally (close-extra-tabs! ui)))))))

(defn row-tab-switch-closes-popovers
  "Switching tabs closes the old tab's open completion."
  [ui]
  (go-row :tab-switch-closes-popovers support/check
    (let [[t1 t2] (on-gtk (let [t1 (first (tabs ui))
                                t2 ((:new-tab!* ui))]
                            ((:select-tab! ui) (:id t1))
                            (editor/set-text! (:ed t1) "(ma")
                            (support/place-cursor! (:buf (:ed t1)) 3)
                            ((:complete! (:actions t1)))
                            [t1 t2]))
          open? (await-until 2000 20 #(popover/open? (:completion t1)))
          _ (on-gtk ((:select-tab! ui) (:id t2)))
          closed? (await-until 1000 20 #(not (popover/open? (:completion t1))))]
      (on-gtk (try
                (support/check :tab-switch-closes-popovers (and open? closed?) {:open? open? :closed? closed?})
                (finally (close-extra-tabs! ui)))))))

(defn row-tab-theme-all-tabs
  "A theme change retints every tab's editor, not only the first."
  [ui]
  (go-row :tab-theme-all-tabs support/check
    (on-gtk
     (try
       (let [t2 ((:new-tab!* ui))
             kw (fn [t] (g/rgba-property-hex (get (:tags (:ed t)) :keyword) "foreground-rgba"))
             _ ((:set-theme!* ui) :navy)
             navy [(kw (first (tabs ui))) (kw t2)]
             _ ((:set-theme!* ui) :light)
             light [(kw (first (tabs ui))) (kw t2)]]
         (support/check :tab-theme-all-tabs
                        (and (= ["#c792ea" "#c792ea"] navy) (= ["#cf222e" "#cf222e"] light))
                        {:navy navy :light light}))
       (finally (close-extra-tabs! ui))))))

(defn row-tab-source-open-in-window
  "The source panel's Open in window opens its file in a new tab, on the
  panel's line, and hides the panel."
  [ui]
  (go-row :tab-source-open-in-window support/check
    (on-gtk
     (try
       (let [_ (occupy-first! ui)
             sp (:source-pane ui)
             f (temp-file! "src.clj" "(def a 1)\n(def b 2)\n(def c 3)\n")
             _ (source-pane/open-location! sp {:path f :line 3 :editable? true :label "src.clj"})
             _ ((:source-open!* ui))
             t (active ui)
             v {:n (count (tabs ui)) :path @(:path t)
                :line (inc (first (g/cursor-line-col (:buf (:ed t)))))
                :panel-visible (g/gtk-widget-get-visible (:root sp))}]
         (support/check :tab-source-open-in-window
                        (and (= 2 (:n v)) (= f (:path v)) (= 3 (:line v)) (= 0 (:panel-visible v)))
                        v))
       (finally (close-extra-tabs! ui))))))

;; --- Run per tab (tabs plan P2.T4) -------------------------------------------

(defn run-tab
  "A go-block step: selects tab t, sets its text to code (when given) and
  Runs it; parks until the window's evaluator is idle and (expect-fn
  text-since-start) holds, or deadline-ms passes. A channel of [ok mark]."
  [ui t code deadline-ms expect-fn]
  (go
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         ((:select-tab! ui) (:id t))
                         (when code (editor/set-text! (:ed t) code))
                         ((:run!* ui))
                         m))
          ok (await-until deadline-ms 20 #(and (not (ev/evaluating? ((:evaluator* ui))))
                                               (expect-fn (support/since ui mark))))]
      [ok mark])))

(defn row-tab-run-keeps-other-tab-ns
  "Tab 1 defines tabs.one; tab 2 requires it and calls it; Running tab 2
  again (a reset) leaves tabs.one in place."
  [ui]
  (go-row :tab-run-keeps-other-tab-ns support/check
    (let [[t1 t2] (on-gtk [(first (tabs ui)) ((:new-tab!* ui))])
          [ok1 _] (<! (run-tab ui t1 "(ns tabs.one) (defn hello [] :hi)" 5000 #(str/includes? % "=> ")))
          code2 "(ns tabs.two (:require [tabs.one :as one])) (one/hello)"
          [ok2 m2] (<! (run-tab ui t2 code2 5000 #(str/includes? % "=> :hi")))
          [ok3 m3] (<! (run-tab ui t2 nil 5000 #(str/includes? % "=> :hi")))]
      (on-gtk (try
                (support/check :tab-run-keeps-other-tab-ns (and ok1 ok2 ok3)
                               {:ok1 ok1 :ok2 ok2 :ok3 ok3 :second (support/since ui m2) :third (support/since ui m3)})
                (finally (close-extra-tabs! ui)))))))

(defn row-tab-run-banner-names-file
  "Run in a saved tab names its file in the banner."
  [ui]
  (go-row :tab-run-banner-names-file support/check
    (let [t (on-gtk (occupy-first! ui)
                    ((:open-file! ui) (temp-file! "demo.clj" "(+ 1 2)\n"))
                    (active ui))
          [ok m] (<! (run-tab ui t nil 5000 #(str/includes? % "=> 3")))]
      (on-gtk (try
                (let [out (support/since ui m)]
                  (support/check :tab-run-banner-names-file
                                 (and ok (str/includes? out "; --- Running demo.clj ---")) {:out out}))
                (finally (close-extra-tabs! ui)))))))

(defn- cljd-project!
  "A temp ClojureDart project (pubspec.yaml) with lib/x.cljd; the file's path."
  []
  (let [dir (temp-dirs/temp-dir! "jonquil-selftest-tabs-")]
    (spit (io/file dir "pubspec.yaml") "name: other\n")
    (let [f (io/file dir "lib" "x.cljd")]
      (io/make-parents f)
      (spit f "(ns other.x)\n")
      (.getCanonicalPath f))))

(defn row-tab-foreign-refuses-run
  "With two tabs open the window's project is fixed: a ClojureDart file
  opens as a third tab, and Run there is refused with the console's note,
  leaving the evaluator and the dialect label as they were."
  [ui]
  (go-row :tab-foreign-refuses-run support/check
    (let [[before label t] (on-gtk (occupy-first! ui)
                                   ((:new-tab!* ui))
                                   (editor/set-text! (:ed (active ui)) ";; two\n")
                                   (let [before ((:evaluator* ui))
                                         label (g/label-text (:dialect-label ui))]
                                     ((:open-file! ui) (cljd-project!))
                                     [before label (active ui)]))
          [_ m] (<! (run-tab ui t nil 2000 #(str/includes? % "so it can't run here.")))]
      (on-gtk (try
                (let [out (support/since ui m)
                      v {:out out :n (count (tabs ui)) :same-ev (identical? before ((:evaluator* ui)))
                         :label (g/label-text (:dialect-label ui)) :label-before label}]
                  (support/check :tab-foreign-refuses-run
                                 (and (= 3 (:n v)) (str/includes? out "belongs to") (str/includes? out "so it can't run here.")
                                      (:same-ev v) (= label (:label v)))
                                 v))
                (finally (close-extra-tabs! ui)))))))

(defn row-tab-close-to-one-adopts-project
  "With two tabs, a ClojureDart file opened into tab 1 doesn't switch the
  window; closing tab 2 leaves one tab whose project differs, so the window
  adopts it. Restored afterwards by opening a plain .clj file into the one
  tab (one tab switches, as dialects.clj's rows rely on)."
  [ui]
  (go-row :tab-close-to-one-adopts-project support/check
    (let [[before t2 m] (on-gtk (let [before ((:evaluator* ui))
                                      t2 ((:new-tab!* ui))
                                      m (support/out-end-offset ui)]
                                  ((:select-tab! ui) (:id (first (tabs ui))))
                                  ((:open-path! ui) (cljd-project!))
                                  [before t2 m]))
          kept? (on-gtk (identical? before ((:evaluator* ui))))
          _ (on-gtk ((:close-tab! ui) (:id t2)))
          adopted? (await-until 3000 20 #(and (str/includes? (support/since ui m) "; Switched to ClojureDart")
                                              (str/starts-with? (g/label-text (:dialect-label ui)) "ClojureDart")))
          _ (on-gtk ((:open-path! ui) (temp-file! "back.clj" "(+ 1 1)\n")))
          back? (await-until 5000 20 #(= "Jolt" (g/label-text (:dialect-label ui))))]
      (on-gtk (support/check :tab-close-to-one-adopts-project (and kept? adopted? back?)
                             {:kept kept? :adopted adopted? :back back? :out (support/since ui m)})))))
