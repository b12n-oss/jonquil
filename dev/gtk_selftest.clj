(ns gtk-selftest
  "P2.T5 self-test: opens a real GtkApplicationWindow around the editor
  under test, runs the rows on a timer chain (never a fixed sleep -- each
  wait polls a condition against a deadline), prints PASS/FAIL per row and
  a SUMMARY line, quits, and exits 0 or 1. Rows call the same functions the
  key handlers call; no keystroke is ever synthesized."
  (:require [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(defonce results (atom []))
(defonce true-fire-count (atom 0))

(defn- check [row ok detail]
  (swap! results conj [row (boolean ok) detail])
  (binding [*out* *err*]
    (println "[gtk-selftest]" (name row) (if ok "PASS" "FAIL") (pr-str detail))))

(defn- later [ms f]
  (g/g-timeout-add ms (cb/callable (fn [_data] (f) 0) [:pointer] :int) ffi/null))

(defn- poll-until
  "Polls (pred?) every interval-ms via g_timeout_add until it's truthy or
  deadline-ms elapses, then calls (done final-pred-value). Never sleeps."
  [deadline-ms interval-ms pred? done]
  (let [t0 (System/currentTimeMillis)]
    (letfn [(step []
              (cond
                (pred?) (done true)
                (>= (- (System/currentTimeMillis) t0) deadline-ms) (done false)
                :else (later interval-ms step)))]
      (step))))

;; --- rows ------------------------------------------------------------------
;; Each row is (fn [ed] (fn [next] ...)) -- it runs its actions against ed,
;; records exactly one check, then calls (next) to advance the chain.

(defn- row-editor-colours [ed]
  (fn [next]
    (let [buf (:buf ed) tags (:tags ed)]
      (editor/set-text! ed "")
      (g/gtk-text-buffer-insert-interactive-at-cursor buf "(defn f [x]\n  ; note\n  x)" -1 1)
      (poll-until 1000 20
                  (fn [] (and (g/has-tag-at? buf 1 (get tags :special-form))
                              (g/has-tag-at? buf 0 (get tags [:rainbow 0]))
                              (g/has-tag-at? buf 14 (get tags :comment))))
                  (fn [ok]
                    (check :editor-colours ok
                           {:text (editor/editor-text ed)
                            :special-form-at-1 (g/has-tag-at? buf 1 (get tags :special-form))
                            :rainbow0-at-0 (g/has-tag-at? buf 0 (get tags [:rainbow 0]))
                            :comment-at-14 (g/has-tag-at? buf 14 (get tags :comment))})
                    (next))))))

(defn- row-editor-undo-text-only [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (g/gtk-text-buffer-undo buf)
      (check :editor-undo-text-only (= "" (editor/editor-text ed))
             {:text (editor/editor-text ed)})
      (next))))

(defn- row-editor-smart-enter [ed]
  (fn [next]
    (let [buf (:buf ed)]
      (editor/set-text! ed "(defn f [x]")
      (g/with-iters* buf 11 11 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
      (editor/handle-return! ed)
      (let [text (editor/editor-text ed) caret (g/cursor-offset buf)]
        (check :editor-smart-enter (and (= "(defn f [x]\n  " text) (= 14 caret))
               {:text text :caret caret}))
      (next))))

(defn- row-editor-dirty [ed]
  (fn [next]
    (editor/set-text! ed "a")
    (let [dirty-right-after (editor/dirty? ed)]
      ;; give any pending flip left over from an earlier row's state (tick
      ;; runs every 20ms) time to settle before this row's own baseline.
      (later 150
             (fn []
               (let [baseline @true-fire-count
                     buf (:buf ed)]
                 (g/with-iters* buf 1 1 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                 (g/gtk-text-buffer-insert-interactive-at-cursor buf "b" -1 1)
                 (poll-until 1000 20
                             (fn [] (and (editor/dirty? ed)
                                         (>= (- @true-fire-count baseline) 1)))
                             (fn [became-dirty]
                               (check :editor-dirty
                                      (and (not dirty-right-after)
                                           became-dirty
                                           (= 1 (- @true-fire-count baseline)))
                                      {:dirty-right-after-set-text dirty-right-after
                                       :became-dirty-within-deadline became-dirty
                                       :true-fires (- @true-fire-count baseline)})
                               (next)))))))))

(defn- row-editor-emoji-offsets [ed]
  (fn [next]
    (let [buf (:buf ed) tags (:tags ed)]
      (editor/set-text! ed "😀(a)")
      (poll-until 1000 20
                  (fn [] (and (g/has-tag-at? buf 1 (get tags [:rainbow 0]))
                              (not (g/has-tag-at? buf 0 (get tags [:rainbow 0])))))
                  (fn [ok]
                    (check :editor-emoji-offsets ok
                           {:text (editor/editor-text ed)
                            :rainbow0-at-1 (g/has-tag-at? buf 1 (get tags [:rainbow 0]))
                            :rainbow0-at-0 (g/has-tag-at? buf 0 (get tags [:rainbow 0]))})
                    (next))))))

(def ^:private row-fns
  [row-editor-colours
   row-editor-undo-text-only
   row-editor-smart-enter
   row-editor-dirty
   row-editor-emoji-offsets])

;; --- driver ------------------------------------------------------------

(defn- run-rows! [app fns]
  (if (empty? fns)
    (let [passed (count (filter second @results)) total (count @results)]
      (println (str "SUMMARY " passed " of " total " passed"))
      (later 50 (fn [] (g/g-application-quit app))))
    ((first fns) (fn [] (run-rows! app (rest fns))))))

(defn- activate [app _data]
  (let [win (g/gtk-application-window-new app)
        ed (editor/make-editor)]
    (g/gtk-window-set-title win "Jonquil P2.T5 self-test")
    (g/gtk-window-set-default-size win 700 500)
    (g/gtk-window-set-child win (:scroll ed))
    (editor/on-dirty-changed! ed (fn [dirty] (when dirty (swap! true-fire-count inc))))
    (g/gtk-window-present win)
    (editor/focus! ed)
    (later 300 (fn [] (run-rows! app (mapv (fn [f] (f ed)) row-fns))))))

(defn -main [& _]
  (let [app (g/gtk-application-new "net.b12n.jonquil.selftest" g/APPLICATION-DEFAULT-FLAGS)]
    (g/g-signal-connect-data app "activate"
                             (cb/callable activate [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-application-run app 0 ffi/null)
    (System/exit (if (and (seq @results) (every? second @results)) 0 1))))
