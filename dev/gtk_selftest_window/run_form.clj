(ns gtk-selftest-window.run-form
  "Run Form rows: Run Form on the window's in-process Jolt, through
  the ui's own :run-form!*, the editor's key dispatch and the editor menu."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(defn- run-form-and-await
  "Sets the editor to text with the caret at caret, then fires the run-form
  action -- :chord calls (:run-form!* ui); :menu fires the editor menu's
  Run Form item; [:key mask] dispatches Return with mask through
  editor/dispatch-key! -- all inside the SAME on-gtk step (the audit
  doesn't exempt a fn value carrying a GTK call handed in from outside one,
  only a call written literally inside on-gtk/await-until, so action is a
  plain dispatch value here, never a fn parameter). Then parks until the
  engine is idle and (expect-fn text-since) holds. [ok mark]."
  [ui text caret action deadline-ms expect-fn]
  (let [ed (:editor ui)
        mark (on-gtk (let [m (support/out-end-offset ui)]
                       (editor/set-text! ed text)
                       (support/place-cursor! (:buf ed) caret)
                       (cond
                         (= action :chord) ((:run-form!* ui))
                         (= action :menu) ((:activate-menu-action!* ui) "run-form")
                         (vector? action) (editor/dispatch-key! ed support/KEY-RETURN (second action)))
                       m))
        ok (await-until deadline-ms 20 #(and (support/idle? ui) (expect-fn (support/since ui mark))))]
    [ok mark]))

(defn row-run-form-top-level
  "A caret deep inside a multi-line top-level form runs that whole form."
  [ui]
  (go-row :run-form-top-level support/check
    (let [text "(inc 1)\n(+ 40\n   2)\n"
          [ok? mark] (run-form-and-await ui text (str/index-of text "2)") :chord 3000
                                         #(str/includes? % "=> 42"))]
      (on-gtk (let [t (support/since ui mark)]
                (support/check :run-form-top-level
                               (and ok? (str/includes? t "> (+ 40\n   2)") (not (str/includes? t "=> 2\n")))
                               {:ok? ok? :text t}))))))

(defn row-run-form-in-comment
  "Inside a (comment ...) block, the form holding the caret runs, never the
  whole block (which would answer => nil)."
  [ui]
  (go-row :run-form-in-comment support/check
    (let [text "(comment\n  (+ 1 2)\n  (* 6\n     7))\n"
          [ok? mark] (run-form-and-await ui text (str/index-of text "7)") :chord 3000
                                         #(str/includes? % "=> 42"))]
      (on-gtk (let [t (support/since ui mark)]
                (support/check :run-form-in-comment (and ok? (not (str/includes? t "=> nil")))
                               {:ok? ok? :text t}))))))

(defn row-run-form-flashes
  "The form's range is selected at once, then the caret goes back where it
  was, with no selection left."
  [ui]
  (go-row :run-form-flashes support/check
    (let [text "(+ 1 2)\n(* 2 3)"
          flashed (on-gtk (let [ed (:editor ui)]
                            (editor/set-text! ed text)
                            (support/place-cursor! (:buf ed) 11)
                            ((:run-form!* ui))
                            (g/selection-offsets (:buf ed))))
          restored? (await-until 2000 20 #(= [11 11] (g/selection-offsets (:buf (:editor ui)))))
          ran? (await-until 3000 20 #(support/idle? ui))]
      (on-gtk (support/check :run-form-flashes (and (= [8 15] flashed) restored? ran?)
                             {:flashed flashed :restored? restored?})))))

(defn row-run-form-key-cancels-flash
  "A key delivered while the flash is still showing cancels it AT
  ONCE -- the caret goes back to where Run Form found it and the
  selection clears, in the same call, rather than waiting out the timer
  (during which GTK's own key handling would otherwise act on the still-
  selected form). KEY-Q never matches any of handle-key!'s own clauses,
  so the only thing that can move the caret off the flash's range here is
  the cancel itself."
  [ui]
  (go-row :run-form-key-cancels-flash support/check
    (let [text "(+ 1 2)\n(* 2 3)"
          {:keys [flashed after-key handled? text-after]}
          (on-gtk (let [ed (:editor ui) buf (:buf ed)]
                    (editor/set-text! ed text)
                    (support/place-cursor! buf 11)
                    ((:run-form!* ui))
                    (let [flashed (g/selection-offsets buf)
                          handled? (editor/dispatch-key! ed support/KEY-Q 0)]
                      {:flashed flashed
                       :after-key (g/selection-offsets buf)
                       :handled? handled?
                       :text-after (editor/editor-text ed)})))]
      (on-gtk (support/check :run-form-key-cancels-flash
                             (and (= [8 15] flashed) (= [11 11] after-key) (zero? handled?) (= text text-after))
                             {:flashed flashed :after-key after-key :handled? handled? :text-after text-after})))))

(defn row-run-form-flash-overlap
  "The menu item's Run Form, fired right after the chord, with
  nothing in between touching the selection, is a second flash while the
  first is still showing -- it carries the FIRST flash's own saved caret
  over, instead of capturing the still-selected first flash's own mark
  position (its range's start) as the caret to restore. Both flashes
  settle with the caret back where it was before the FIRST one ran."
  [ui]
  (go-row :run-form-flash-overlap support/check
    (let [text "(+ 1 2)"
          {:keys [first-flash second-flash]}
          (on-gtk (let [ed (:editor ui) buf (:buf ed)]
                    (editor/set-text! ed text)
                    (support/place-cursor! buf 3)
                    ((:run-form!* ui))
                    (let [first-flash (g/selection-offsets buf)]
                      ((:activate-menu-action!* ui) "run-form")
                      {:first-flash first-flash :second-flash (g/selection-offsets buf)})))
          restored? (await-until 1000 20 #(= [3 3] (g/selection-offsets (:buf (:editor ui)))))]
      (on-gtk (support/check :run-form-flash-overlap
                             (and (= [0 7] first-flash) (= [0 7] second-flash) restored?)
                             {:first-flash first-flash :second-flash second-flash :restored? restored?})))))

(defn row-run-form-key-before-smart-enter
  "Cmd+Enter and Ctrl+Enter reach Run Form through the editor's own key
  dispatch, ahead of smart-Enter: the key is consumed, no newline goes in,
  and the form runs. Plain Return still reaches smart-Enter (the negative
  control: it does insert a newline)."
  [ui]
  (go-row :run-form-key-before-smart-enter support/check
    (let [ed (:editor ui)
          text "(* 6 7)"
          [meta-ok? _] (run-form-and-await ui text 7 [:key g/META-MASK] 3000 #(str/includes? % "=> 42"))
          meta-text (on-gtk (editor/editor-text ed))
          [ctrl-ok? _] (run-form-and-await ui text 7 [:key g/CONTROL-MASK] 3000 #(str/includes? % "=> 42"))
          ctrl-text (on-gtk (editor/editor-text ed))
          plain-text (on-gtk (editor/set-text! ed text)
                             (support/place-cursor! (:buf ed) 7)
                             (editor/dispatch-key! ed support/KEY-RETURN 0)
                             (editor/editor-text ed))]
      (on-gtk (support/check :run-form-key-before-smart-enter
                             (and meta-ok? ctrl-ok? (= text meta-text) (= text ctrl-text) (not= text plain-text))
                             {:meta meta-ok? :ctrl ctrl-ok? :meta-text meta-text :ctrl-text ctrl-text
                              :plain-text plain-text})))))

(defn row-run-form-menu-item
  "The editor menu's Run Form item runs the same fn."
  [ui]
  (go-row :run-form-menu-item support/check
    (let [[ok? mark] (run-form-and-await ui "(* 6 7)" 3 :menu 3000
                                         #(str/includes? % "=> 42"))]
      (on-gtk (support/check :run-form-menu-item ok? {:text (support/since ui mark)})))))

(defn row-run-form-nothing-at-caret
  "On a blank line between forms: nothing is echoed or run, and the status
  says why.

  since is always non-empty -- it includes the
  live \"user=> \" prompt -- so \"nothing echoed\" is asserted against a
  BASELINE captured right before the action, the same pattern
  run-repl/row-run-selection-blank uses, never a literal
  (not (str/includes? t \"user=> \")), which can never pass."
  [ui]
  (go-row :run-form-nothing-at-caret support/check
    (let [[mark baseline] (on-gtk (let [m (support/out-end-offset ui)
                                        ed (:editor ui)]
                                    (editor/set-text! ed "(a)\n\n(b)")
                                    (support/place-cursor! (:buf ed) 4)
                                    (let [baseline (support/since ui m)]
                                      ((:run-form!* ui))
                                      [m baseline])))
          said? (await-until 2000 20 #(= "No form at the caret." (g/label-text (:status-label ui))))]
      (on-gtk (let [t (support/since ui mark)]
                (support/check :run-form-nothing-at-caret
                               (and said? (= baseline t) (support/idle? ui))
                               {:said? said? :baseline baseline :text t}))))))
