(ns gtk-selftest.parinfer-format
  "Editor rows for Parinfer together with Format All, and the apply-op NUL refusal."
  (:require [clojure.string :as str]
            [gtk-async :refer [go-row on-gtk]]
            [gtk-selftest.support :refer [check tail parinfer-on! parinfer-off! type-str! caret!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

;; --- Parinfer and Format All interplay rows -----
;; A format is a deliberate whole-text operation: with Parinfer on its
;; result lands through the plain path, already made Paren-stable by the
;; op builder, so the hook never rewrites it and never shows the too-long
;; note; with Parinfer off the formatter's output is byte-identical to
;; before.
(def ^:private fi-unstable "(def a\n  [{:x 1}\n    {:y 2}])")

(def ^:private fi-stable "(def a\n  [{:x 1}\n   {:y 2}])")

(def ^:private fi-misindented "(def a\n[{:x 1}\n{:y 2}])")

(defn row-parinfer-format-stabilizes-clean-text [ed]
  (go-row :parinfer-format-stabilizes-clean-text check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; formatter-clean but Paren-unstable text: a format with
                 ;; Parinfer on now lands the Paren-stable form, and one
                 ;; undo restores the input (one user action)
                 (editor/set-text! ed fi-unstable)
                 (caret! ed 0)
                 (editor/do-format-all! ed)
                 (let [text (editor/editor-text ed)]
                   (g/gtk-text-buffer-undo (:buf ed))
                   (let [undone (editor/editor-text ed)]
                     (check :parinfer-format-stabilizes-clean-text
                            (and (= fi-stable text) (= fi-unstable undone))
                            {:text text :undone undone})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-format-lands-untouched [ed]
  (go-row :parinfer-format-lands-untouched check
    (on-gtk (let [status (atom [])]
              (try (editor/set-parinfer-fns! ed (constantly :smart)
                                             (fn [msg] (swap! status conj msg)))
                   ;; the op builder makes the result Paren-stable, so the
                   ;; hook has nothing to do: no rewrite action, and the
                   ;; status line is never touched (today the hook clears it)
                   (editor/set-text! ed fi-misindented)
                   (caret! ed 0)
                   (editor/do-format-all! ed)
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-format-lands-untouched
                            (and (= fi-stable text) (= [] @status))
                            {:text text :status @status}))
                   (finally (parinfer-off! ed)))))))

(defn row-parinfer-format-too-long-quietly [ed]
  (go-row :parinfer-format-too-long-quietly check
    (on-gtk (let [status (atom [])
                  big-mis (str "(def big\n[{:x 1}\n"
                               (apply str (repeat 300 "{:y 2}\n")) "{:y 3}])")
                  big-stable (str "(def big\n  [{:x 1}\n"
                                  (apply str (repeat 300 "   {:y 2}\n")) "   {:y 3}])")]
              (try (editor/set-parinfer-fns! ed (constantly :smart)
                                             (fn [msg] (swap! status conj msg)))
                   ;; a format whose edit spans more than 300 lines: no
                   ;; too-long note any more (the hook never runs), and the
                   ;; over-limit form is stabilized too -- the user accepted
                   ;; the format's cost over a 300-line carve-out
                   (editor/set-text! ed (str big-mis "\n\n" fi-misindented))
                   (caret! ed 0)
                   (editor/do-format-all! ed)
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-format-too-long-quietly
                            (and (= (str big-stable "\n\n" fi-stable) text)
                                 (= [] @status))
                            {:text-tail (tail text 40) :status @status}))
                   (finally (parinfer-off! ed)))))))

(defn row-parinfer-format-off-byte-identical [ed]
  (go-row :parinfer-format-off-byte-identical check
    (on-gtk (try (parinfer-off! ed)
                 ;; with Parinfer off, format output is the formatter's
                 ;; alone, byte for byte
                 (editor/set-text! ed fi-misindented)
                 (caret! ed 0)
                 (editor/do-format-all! ed)
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-format-off-byte-identical
                          (= fi-unstable text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-format-selection-scoped [ed]
  (go-row :parinfer-format-selection-scoped check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; Format Selection stabilizes only the top-level forms the
                 ;; selection touches, and keeps its selection semantics
                 ;; (the selection collapses to the carried caret, as today)
                 (editor/set-text! ed (str fi-unstable "\n\n"
                                           "(def b\n  [{:x 3}\n    {:y 4}])"))
                 (g/with-iters* (:buf ed) 2 10
                   (fn [i j] (g/gtk-text-buffer-select-range (:buf ed) i j)))
                 (editor/do-format-selection! ed)
                 (let [text (editor/editor-text ed)
                       [sa sb] (g/selection-offsets (:buf ed))]
                   (check :parinfer-format-selection-scoped
                          (and (= (str fi-stable "\n\n"
                                       "(def b\n  [{:x 3}\n    {:y 4}])") text)
                               (= sa sb))
                          {:text text :sel [sa sb]}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-format-second-is-noop [ed]
  (go-row :parinfer-format-second-is-noop check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; a second format finds the text already Paren-stable and
                 ;; applies nothing: no second undo step, so one undo after
                 ;; two formats restores the input
                 (editor/set-text! ed fi-misindented)
                 (caret! ed 0)
                 (editor/do-format-all! ed)
                 (editor/do-format-all! ed)
                 (let [text (editor/editor-text ed)]
                   (g/gtk-text-buffer-undo (:buf ed))
                   (let [undone (editor/editor-text ed)]
                     (check :parinfer-format-second-is-noop
                            (and (= fi-stable text) (= fi-misindented undone))
                            {:text text :undone undone})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-format-next-keystroke-hits [ed]
  (go-row :parinfer-format-next-keystroke-hits check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; the plain path primes the cache from the op, so the
                 ;; keystroke after a format is a hit (zero full reads) --
                 ;; and a no-op second format must leave that prime alone
                 (editor/set-text! ed fi-misindented)
                 (caret! ed 0)
                 (editor/do-format-all! ed)
                 (let [fulls (atom 0) real g/buffer-text
                       ;; only the keystrokes are counted: a format's own
                       ;; snapshot is a full read by design (the formatter
                       ;; needs the text), so it runs outside the counter
                       count! (fn [f]
                                (with-redefs-fn
                                  {#'g/buffer-text
                                   (fn [& a] (swap! fulls inc) (apply real a))}
                                  f))]
                   (count! #(do (caret! ed 6) (type-str! ed "x")))
                   (editor/do-format-all! ed)
                   (count! #(do (caret! ed 6) (type-str! ed "y")))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-format-next-keystroke-hits
                            (and (str/includes? text "ayx") eq (zero? @fulls))
                            {:text text :eq eq :full-reads @fulls})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-format-style-pin-paren-stable [ed]
  (go-row :parinfer-format-style-pin-paren-stable check
    (on-gtk (try (parinfer-off! ed)
                 ;; a project's :format-style :paren-stable runs the pass
                 ;; with the mode off, so a repo formats one way for
                 ;; everyone
                 (editor/set-format-style-fn! ed (constantly :paren-stable))
                 (editor/set-text! ed fi-misindented)
                 (caret! ed 0)
                 (editor/do-format-all! ed)
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-format-style-pin-paren-stable
                          (= fi-stable text) {:text text}))
                 (finally (editor/set-format-style-fn! ed (constantly nil))
                          (parinfer-off! ed))))))

(defn row-parinfer-format-style-pin-plain [ed]
  (go-row :parinfer-format-style-pin-plain check
    (on-gtk (let [status (atom [])]
              (try (editor/set-parinfer-fns! ed (constantly :smart)
                                             (fn [msg] (swap! status conj msg)))
                   ;; :plain keeps the plain formatter's bytes under a live
                   ;; mode, still with no hook and no status noise
                   (editor/set-format-style-fn! ed (constantly :plain))
                   (editor/set-text! ed fi-misindented)
                   (caret! ed 0)
                   (editor/do-format-all! ed)
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-format-style-pin-plain
                            (and (= fi-unstable text) (= [] @status))
                            {:text text :status @status}))
                   (finally (editor/set-format-style-fn! ed (constantly nil))
                            (parinfer-off! ed)))))))

(defn row-apply-op-refuses-nul [ed]
  (go-row :apply-op-refuses-nul check
    (on-gtk
     ;; GTK takes C strings, so an op whose edit text holds a NUL would
     ;; land truncated. The apply-op! funnel refuses
     ;; it like a read-only editor: nil, buffer untouched. Every op path
     ;; (plugin :edit commands, MCP replace) goes through this funnel.
     (editor/set-text! ed "(+ 1 1)")
     (let [r (editor/apply-op-plain! ed {:edits [[0 0 "x\u0000y"]] :caret 0})
           text (editor/editor-text ed)]
       (check :apply-op-refuses-nul
              (and (nil? r) (= "(+ 1 1)" text))
              {:r r :text text})))))
