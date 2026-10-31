(ns gtk-selftest-window.debugger
  "The Frames panel: on its own in a transient window, then the real
  window's commands after an error. On a Jolt with jolt.debug the row sees
  frames and locals and opens a local in the inspector; on one without, it
  sees the sentence that says so."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.debugger :as debugger]
            [jonquil.gtk.debugger-pane :as dpane]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]))

(defn row-debugger-pane-shows
  "show! lists a row per frame and per local under the error's title, a
  note on its own, and activating a row calls the frame or local handler."
  [_ui]
  (go-row :debugger-pane-shows support/check
    (let [{:keys [win pane]} (on-gtk (let [win (g/gtk-window-new)
                                           pane (dpane/make-pane)]
                                       (g/gtk-window-set-child win (:root pane))
                                       (g/gtk-window-present win)
                                       {:win win :pane pane}))
          calls (atom [])
          data {:title "ArithmeticException: Divide by zero"
                :frames [{:fn "demo.core/inner" :file "/x/demo/core.clj" :line 2
                          :locals [{:name "x" :text "21"}]}]}]
      (try
        (await-until 2000 20 #(pos? (g/gtk-widget-get-width (:root pane))))
        (on-gtk
         (dpane/set-handlers! pane {:on-frame (fn [i] (swap! calls conj [:frame i]))
                                    :on-local (fn [i n] (swap! calls conj [:local i n]))})
         (dpane/show! pane data)
         (dpane/activate! pane 0)
         (dpane/activate! pane 1)
         (let [rows (dpane/row-texts pane)
               title (dpane/title-text pane)
               _ (dpane/show! pane {:note "No error yet."})
               rows2 (dpane/row-texts pane)
               _ (dpane/activate! pane 0)]
           (support/check :debugger-pane-shows
                          (and (= ["demo.core/inner  core.clj:2" "    x = 21"] rows)
                               (= "ArithmeticException: Divide by zero" title)
                               (= ["No error yet."] rows2)
                               (= [[:frame 0] [:local 0 "x"]] @calls))
                          {:rows rows :title title :rows2 rows2 :calls @calls})))
        (finally (on-gtk (g/gtk-window-close win)))))))

;; --- the real window -----------------------------------------------------

(defn- run-palette!
  "Opens the palette, filters it to query and runs the first entry, then
  closes it. GTK-thread only."
  [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn- status [ui] (g/label-text (:status-label ui)))

(def ^:private NO-API "Frames need a Jolt with jolt.debug (newer than this one).")

(def ^:private LOCAL-ROW "    xs = [1 2 3]")

(defn row-frames-after-error
  "Keep every local, Run a form that throws, then Show the last error: the
  panel's title is the error; with jolt.debug its rows hold the frame and
  its local, and activating the local opens it in the inspector; without
  it, the one row says what's needed. Debug mode goes back off."
  [ui]
  (go-row :frames-after-error support/check
    (let [dp (:debugger-pane ui)
          avail? (debugger/available?)]
      (try
        (on-gtk (run-palette! ui "keep every local"))
        (let [mode? (await-until 3000 20 #(= (status ui) (if avail?
                                                           "; Debug mode on: code you load from now on keeps its locals."
                                                           (str "; " NO-API))))
              [ran? _] (support/run-and-await ui "(defn dbg-inner [xs]\n  (let [n (count xs)] (/ n 0)))\n(dbg-inner [1 2 3])"
                                              5000 #(str/includes? % "Divide by zero"))
              _ (on-gtk (run-palette! ui "show the last error"))
              shown? (await-until 3000 20 #(= "ArithmeticException: Divide by zero" (dpane/title-text dp)))
              rows (on-gtk (dpane/row-texts dp))
              rows-ok? (if avail?
                         (boolean (and (some #{LOCAL-ROW} rows)
                                       (some #(str/starts-with? % "user/dbg-inner  line 2 (no file)") rows)))
                         (= [NO-API] rows))
              inspected? (if avail?
                           (do (on-gtk (dpane/activate! dp (first (keep-indexed (fn [i r] (when (= r LOCAL-ROW) i)) rows))))
                               (await-until 3000 20 #(str/includes? (g/label-text (:info (:inspector ui))) "3 items")))
                           true)
              _ (when (on-gtk @(:debug-mode-on? ui)) (on-gtk (run-palette! ui "keep every local")))
              off? (await-until 3000 20 #(not @(:debug-mode-on? ui)))]
          (on-gtk (support/check :frames-after-error (and mode? ran? shown? rows-ok? inspected? off?)
                                 {:available avail? :mode mode? :ran ran? :shown shown? :rows rows
                                  :inspected inspected? :off off? :status (status ui)})))
        (finally
          (on-gtk (when-let [f (:on-close @(:handlers dp))] (f))
                  ((:close-inspector!* ui))))))))
