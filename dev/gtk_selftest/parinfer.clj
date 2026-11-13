(ns gtk-selftest.parinfer
  "Editor rows for Parinfer: modes, caret and selection, engine failures and a cursor hook that throws."
  (:require [gtk-async :refer [go-row on-gtk]]
            [gtk-selftest.support :refer [check parinfer-on! parinfer-off! type-str! delete-action! caret!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.parinfer-edit :as pe]))

;; --- Parinfer rows --------------------------------------------
;; Each row turns the mode on through editor/set-parinfer-fns! (what
;; jonquil.gtk.window.tabs wires per tab) and makes the edit the way GTK's own
;; commit does: one begin/end-user-action around an insert or delete, so
;; the end-user-action hook runs. The last step of every row turns
;; Parinfer back off, so the other rows are untouched.
(defn row-parinfer-open-paren [ed]
  (go-row :parinfer-open-paren check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (caret! ed 0)
                 (type-str! ed "(")
                 (let [text (editor/editor-text ed)
                       caret (g/cursor-offset (:buf ed))]
                   (check :parinfer-open-paren (and (= "()" text) (= 1 caret))
                          {:text text :caret caret}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-dedent-undo-redo [ed]
  (go-row :parinfer-dedent-undo-redo check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a\n  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)       ; the user dedents b
                 (let [buf (:buf ed)
                       dedented (editor/editor-text ed)
                       _ (g/gtk-text-buffer-undo buf)
                       undone (editor/editor-text ed)
                       _ (g/gtk-text-buffer-redo buf)
                       redone (editor/editor-text ed)]
                   (check :parinfer-dedent-undo-redo
                          (and (= "(a)\nb" dedented)
                               (= "(a\n  b)" undone)
                               (= "(a)\nb" redone))
                          {:dedented dedented :undone undone :redone redone}))
                 (finally (parinfer-off! ed))))))

(defn row-reload-text-undoable [ed]
  (go-row :reload-text-undoable check
    (on-gtk (let [buf (:buf ed)]
              (editor/set-text! ed "(a)\n")
              (caret! ed 4)
              (type-str! ed "x")
              (editor/reload-text! ed "(b)\n")
              (let [text (editor/editor-text ed)
                    clean? (not (editor/dirty? ed))
                    caret (g/cursor-offset buf)
                    _ (g/gtk-text-buffer-undo buf)
                    undone (editor/editor-text ed)
                    dirty? (editor/dirty? ed)
                    _ (g/gtk-text-buffer-undo buf)
                    undone2 (editor/editor-text ed)]
                (check :reload-text-undoable
                       (and (= "(b)\n" text) clean? (= 4 caret)
                            (= "(a)\nx" undone) dirty? (= "(a)\n" undone2))
                       {:text text :clean clean? :caret caret :undone undone :dirty dirty? :undone2 undone2}))))))

(defn row-reload-text-caret-clamps [ed]
  (go-row :reload-text-caret-clamps check
    (on-gtk (editor/set-text! ed "(defn long-name [])\n")
            (caret! ed 18)
            (editor/reload-text! ed "(a)")
            (let [caret (g/cursor-offset (:buf ed))]
              (check :reload-text-caret-clamps (= 3 caret) {:caret caret})))))

(defn row-reload-text-parinfer-held-off [ed]
  (go-row :reload-text-parinfer-held-off check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (editor/reload-text! ed "(a\n  b")
                 (let [text (editor/editor-text ed)]
                   (check :reload-text-parinfer-held-off (= "(a\n  b" text) {:text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-mcp-and-reload-unchanged [ed]
  (go-row :parinfer-mcp-and-reload-unchanged check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (editor/apply-op-plain! ed {:edits [[0 0 "(fn [a b]\n     (h a))"]] :caret 0})
                 (let [mcp-text (editor/editor-text ed)]
                   (editor/set-text! ed "(x\n  y)")   ; a reload
                   (let [reload-text (editor/editor-text ed)]
                     (check :parinfer-mcp-and-reload-unchanged
                            (and (= "(fn [a b]\n     (h a))" mcp-text)
                                 (= "(x\n  y)" reload-text))
                            {:mcp mcp-text :reload reload-text})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-unstable-form-only [ed]
  (go-row :parinfer-unstable-form-only check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(fn [a b]\n     (h a))\n(q [c]\n     d)")
                 (caret! ed 19)                 ; just after "a" in (h a)
                 (type-str! ed "x")
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-unstable-form-only
                          (= "(fn [a b]\n    (h ax))\n(q [c]\n     d)" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-off-no-change [ed]
  (go-row :parinfer-off-no-change check
    (on-gtk (editor/set-text! ed "(a\n  b)")
            (caret! ed 5)
            (delete-action! ed 3 5)
            (let [text (editor/editor-text ed)]
              (check :parinfer-off-no-change (= "(a\nb)" text) {:text text})))))

(defn row-parinfer-too-long-form-says-so [ed]
  (go-row :parinfer-too-long-form-says-so check
    (on-gtk (let [status (atom [])]
              (try (editor/set-parinfer-fns! ed (constantly :smart) (fn [msg] (swap! status conj msg)))
                   (editor/set-text! ed (str "(a\n" (apply str (repeat 301 "  b\n")) ")"))
                   (caret! ed 5)                ; before the first b
                   (type-str! ed "x")
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-too-long-form-says-so
                            (and (= [(str "over 300 lines here, not adjusted")] @status)
                                 (= (str "(a\n  xb\n" (apply str (repeat 300 "  b\n")) ")") text))
                            {:status @status}))
                   (finally (parinfer-off! ed)))))))

(defn row-parinfer-paren-mode-keeps-pairs [ed]
  (go-row :parinfer-paren-mode-keeps-pairs check
    (on-gtk (try (parinfer-on! ed :paren)
                 (editor/set-text! ed "")
                 (caret! ed 0)
                 ;; Paren mode never balances, so the auto-pair stays on
                 ;; (handle-key! takes the key and inserts the pair).
                 (let [handled (editor/handle-key! ed 0x028 0)
                       text (editor/editor-text ed)]
                   (check :parinfer-paren-mode-keeps-pairs
                          (and (= 1 handled) (= "()" text))
                          {:handled handled :text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-quote-backspace-keeps-pair [ed]
  (go-row :parinfer-quote-backspace-keeps-pair check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a \"\")")
                 (caret! ed 4)
                 ;; Backspace in an empty string pair still removes both
                 ;; quotes: a lone quote would unclose the form.
                 (let [handled (editor/handle-key! ed 0xff08 0)
                       text (editor/editor-text ed)]
                   (check :parinfer-quote-backspace-keeps-pair
                          (and (= 1 handled) (= "(a )" text))
                          {:handled handled :text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-op-keeps-caret [ed]
  (go-row :parinfer-op-keeps-caret check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a\n  b)")
                 (caret! ed 7)
                 ;; Toggle comment through apply-op!: Parinfer moves the
                 ;; closer, and the op's caret must survive it.
                 (editor/do-toggle-comment! ed)
                 (let [text (editor/editor-text ed)
                       caret (g/cursor-offset (:buf ed))]
                   (check :parinfer-op-keeps-caret
                          (and (= "(a)\n;   b)" text) (= (count text) caret))
                          {:text text :caret caret}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-op-keeps-selection [ed]
  (go-row :parinfer-op-keeps-selection check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a\n  b\n  c)")
                 (g/with-iters* (:buf ed) 5 11
                   (fn [i1 i2] (g/gtk-text-buffer-select-range (:buf ed) i1 i2)))
                 ;; Shift+Tab over b and c: the closer moves up, and the
                 ;; selection still covers the two dedented lines, so a
                 ;; second Shift+Tab has nothing left to remove.
                 (editor/do-unindent! ed)
                 (let [text (editor/editor-text ed)
                       [a b] (g/selection-offsets (:buf ed))
                       sel (when (and a b (< a b)) (subs text a b))]
                   (check :parinfer-op-keeps-selection
                          (and (= "(a)\nb\nc" text) (= "b\nc" sel))
                          {:text text :sel sel}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-engine-failure-named-once [ed]
  (go-row :parinfer-engine-failure-named-once check
    (on-gtk (let [errors (atom [])]
              (try (parinfer-on! ed :smart)
                   (editor/set-error-fn! ed (fn [msg] (swap! errors conj msg)))
                   (editor/set-text! ed "(a)")
                   (caret! ed 2)
                   ;; A Jolt shim gap surfaces as an "unhandled" engine
                   ;; result; the hook names it once and the text stays as
                   ;; typed.
                   (with-redefs-fn {#'pe/adjust
                                    (fn [_] {:engine-error "boom"})}
                     #(type-str! ed "x"))
                   (let [after-fail (editor/editor-text ed)]
                     (type-str! ed "(")
                     (check :parinfer-engine-failure-named-once
                            (and (= ["; parinfer: boom"] @errors)
                                 (= "(ax)" after-fail)
                                 (= "(ax())" (editor/editor-text ed)))
                            {:errors @errors :after-fail after-fail
                             :text (editor/editor-text ed)}))
                   (finally (editor/set-error-fn! ed (fn [_]))
                            (parinfer-off! ed)))))))

(defn row-parinfer-throw-clears-the-prime [ed]
  (go-row :parinfer-throw-clears-the-prime check
    (on-gtk (let [errors (atom [])]
              (try (parinfer-on! ed :smart)
                   (editor/set-error-fn! ed (fn [msg] (swap! errors conj msg)))
                   (editor/set-text! ed "(a)")
                   (caret! ed 2)
                   ;; primes the cache
                   (type-str! ed "x")
                   ;; a throw (not an engine-error result) lands in the
                   ;; hook's catch: it names the error once and drops the
                   ;; cache and the outside span, so the next keystroke
                   ;; starts from scratch
                   (reset! errors [])
                   (with-redefs-fn {#'pe/adjust
                                    (fn [_] (throw (ex-info "kaput" {})))}
                     #(type-str! ed "y"))
                   (let [after (editor/editor-text ed)
                         cache @(:parinfer-prev-cache ed)
                         span @(:parinfer-outside-span ed)]
                     (type-str! ed "(")
                     (check :parinfer-throw-clears-the-prime
                            (and (= ["; parinfer: kaput"] @errors)
                                 (= "(axy)" after)
                                 (nil? cache)
                                 (nil? span)
                                 (= "(axy())" (editor/editor-text ed)))
                            {:errors @errors :after after :cache cache
                             :span span :text (editor/editor-text ed)}))
                   (finally (editor/set-error-fn! ed (fn [_]))
                            (parinfer-off! ed)))))))

(defn row-editor-cursor-fn-throw-named
  "A registrant of on-cursor-moved! that throws must not break the cursor
  move that called it: the editor reports it through its error fn as
  \"; cursor: <message>\" and leaves its text alone."
  [ed]
  (go-row :editor-cursor-fn-throw-named check
    (on-gtk (let [saved @(:on-cursor-fns ed)
                  errors (atom [])]
              (try
                (editor/set-error-fn! ed (fn [msg] (swap! errors conj msg)))
                (editor/set-text! ed "abc")
                (editor/on-cursor-moved! ed (fn [_] (throw (ex-info "bad" {}))))
                ;; set-text! may notify too; only the caret move may satisfy the check
                (reset! errors [])
                (caret! ed 1)
                (check :editor-cursor-fn-throw-named
                       (and (some #{"; cursor: bad"} @errors)
                            (= "abc" (editor/editor-text ed)))
                       {:errors @errors :text (editor/editor-text ed)})
                (finally
                  (reset! (:on-cursor-fns ed) saved)
                  (editor/set-error-fn! ed (fn [_]))))))))
