(ns demo-shots.support
  "The helpers dev/demo_shots.clj and its scene namespaces share: the result
  log and check!, the output paths, the console and cursor readers copied
  from dev/gtk_selftest_window.clj, cgevent window lookup and GIF recording,
  and the helpers the Lisp Machine panel scenes use."
  (:require [clojure.core.async :as a :refer [<! timeout]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [gtk-async :as ga :refer [on-gtk]]
            [jolt.ffi :as ffi]
            [jonquil.eval :as eval]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]
            [temp-dirs]))

(def images-dir "docs/images")
(def demos-dir "docs/demos")

(defonce results (atom []))

(defn log [& args]
  (binding [*out* *err*]
    (apply println "[demo-shots]" args)))

(defn check! [scene ok? detail]
  (swap! results conj {:scene scene :ok (boolean ok?) :detail detail})
  (log (name scene) (if ok? "PASS" "FAIL") (pr-str detail)))

(defn own-pid [] (.pid (java.lang.ProcessHandle/current)))

(defn demo-path [name] (str demos-dir "/" name))
(defn image-path [id ext] (str images-dir "/" (clojure.core/name id) "." ext))

;; --- copied from dev/gtk_selftest_window.clj (small state-reading
;; helpers; see that file's own docstring for why each is a small copy,
;; not a refactor of it) -----------------------------------------------

(defn console-text [ui] (g/buffer-text (:buf (:console ui))))
(defn idle?
  "As gtk-selftest-window.support/idle?: nothing running, every run's
  on-complete returned, and the console drained."
  [ui]
  (let [cv (:console ui)]
    (and (eval/settled? (:ctx (:session ui)))
         (empty? @(:queue cv))
         (not @(:drain-scheduled? cv)))))

(defn mark-offset [buf mark]
  (let [it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-buffer-get-iter-at-mark buf it mark)
      (g/gtk-text-iter-get-offset it)
      (finally (ffi/free it)))))

(defn out-end-offset [ui]
  (let [cv (:console ui)]
    (mark-offset (:buf cv) (:out-end cv))))

(defn since [ui mark] (subs (console-text ui) mark))

(defn place-cursor!
  "Places buf's cursor at offset -- an ordinary GtkTextBuffer call,
  copied from dev/gtk_selftest_window.clj's own place-cursor! (same
  category as this file's own g/gtk-text-buffer-insert-interactive-at-
  cursor calls elsewhere: never a synthesized keystroke)."
  [buf offset]
  (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a))))

;; --- helpers beyond gtk-async's own: the close-prompt scene's alert-
;; window lookup and the GIF recording, both off the GTK thread ----------

(defn windows
  "`cgevent windows --pid <pid> --edn` on its own thread (ga/sh), parsed. A
  failed or unparseable call reads as no windows, never an exception, so a
  poller sees it as \"not there yet\"."
  [pid]
  (let [{:keys [out exit]} (ga/sh "cgevent" "windows" "--pid" (str pid) "--edn")]
    (if (zero? exit)
      (try (edn/read-string out) (catch Exception _ []))
      [])))

(defn await-windows
  "Like await-until, but the condition is (pred? (windows pid)), checked
  every interval-ms up to deadline-ms. Returns the last windows read,
  whether or not pred? passed. Never touches the GTK thread."
  [pid deadline-ms interval-ms pred?]
  (ga/assert-off-gtk! "await-windows")
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (let [ws (windows pid)]
        (if (or (pred? ws) (>= (- (System/currentTimeMillis) t0) deadline-ms))
          ws
          (do (<! (timeout interval-ms)) (recur)))))))

(defn screenshot-by-id
  "Like ga/screenshot, but by --window-id instead of --pid, for a window
  that isn't this process's main one (the close-prompt scene's alert)."
  [path window-id]
  (ga/sh "cgevent" "screenshot" path "--window-id" (str window-id)))

(defn record-gif
  "Starts cgevent record-gif on its own thread (a/thread) and returns the
  channel that delivers its result map once the recording ends, so a scene
  can go on driving the window meanwhile and take the result later."
  [path pid duration-secs fps width]
  (a/thread (sh/sh "cgevent" "record-gif" "--pid" (str pid) "--duration" (str duration-secs)
                   "-o" path "--fps" (str fps) "--width" (str width))))

;; --- Lisp Machine panels -------------------------------------------------------
;; Each scene stages its own demo project under a fresh temp directory named
;; like a project (my-talk), so only file and folder names reach the image.

(defn demo-project!
  "A temp folder named my-talk holding files ({rel text}); returns its
  canonical path. The scene's own, deleted with the rest by temp-dirs."
  [files]
  (let [dir (io/file (temp-dirs/temp-dir! "jonquil-demoshots-lm-") "my-talk")]
    (doseq [[rel text] files]
      (let [f (io/file dir rel)] (io/make-parents f) (spit f text)))
    (.getCanonicalPath dir)))

(defn run-palette!
  "Opens the palette, filters it to query and runs the first entry. GTK
  thread only."
  [ui query]
  (let [p (:palette ((:active-tab* ui)))]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn panel-key!
  "Cmd/Ctrl+Alt+digit through the window's key handler."
  [ui digit]
  ((:handle-window-key! ui) ffi/null (int digit) 0 (bit-or g/META-MASK g/ALT-MASK) ffi/null))

(defn status-text [ui] (g/label-text (:status-label ui)))

(defn console-enter!
  "Types code at the console prompt and presses Return; the out offset
  before it."
  [ui code]
  (on-gtk (let [cv (:console ui)
                m (out-end-offset ui)]
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
            (cview/handle-return! cv false)
            m)))

(defn caret-to-line!
  "The caret at the start of line n (1-based) of ed's text."
  [ed n]
  (let [text (editor/editor-text ed)
        at (loop [i 0 l 1] (if (= l n) i (recur (inc (str/index-of text "\n" i)) (inc l))))]
    (place-cursor! (:buf ed) at)))

(defn all-saved!
  "Every tab marked saved, so the harness's close isn't held up by the
  unsaved-changes alert."
  [ui]
  (doseq [t ((:tabs* ui))] (editor/mark-saved! (:ed t))))
