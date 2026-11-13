(ns gtk-selftest.console
  "Console rows: the read-only prompt, submit, history, styling and flood handling."
  (:require [clojure.core.async :refer [<! timeout]]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest.support :refer [check mark-offset tail console-submits history-prev-calls]]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]))

;; --- console rows -------------------------------------------------
;; on-submit/history-prev/history-next are stubs recording their calls in
;; atoms; the rows call the same functions the key handlers call
;; (handle-return!/handle-history!), never a synthesized keystroke.
(defn row-console-readonly [cv]
  (go-row :console-readonly check
    (cview/append! cv :out "hello\n")
    (let [drained (await-until 1000 20 #(str/includes? (g/buffer-text (:buf cv)) "hello\n"))]
      (on-gtk (let [buf (:buf cv)
                    before (g/buffer-text buf)
                    it (ffi/alloc g/ITER-BYTES)
                    refused (try
                              (g/gtk-text-buffer-get-iter-at-offset buf it 2)
                              (zero? (g/gtk-text-buffer-insert-interactive buf it "X" -1 1))
                              (finally (ffi/free it)))
                    after (g/buffer-text buf)]
                (check :console-readonly (and drained refused (= before after))
                       {:drained drained :refused refused :before before :after after}))))))

(defn row-console-readonly-delete [cv]
  (go-row :console-readonly-delete check
    (on-gtk (cview/clear-input! cv))
    (cview/append! cv :out "guard\n")
    (let [drained (await-until 1000 20 #(str/includes? (g/buffer-text (:buf cv)) "guard\n"))]
      (on-gtk (let [buf (:buf cv)
                    before (g/buffer-text buf)
                    input-start-at (mark-offset buf (:input-start cv))
                    ;; the space just before input-start is the second
                    ;; char of the prompt's "user=> " -- still read-only.
                    prompt-space-at (dec input-start-at)
                    delete-at (fn [pos]
                                (let [i1 (ffi/alloc g/ITER-BYTES)
                                      i2 (ffi/alloc g/ITER-BYTES)]
                                  (try
                                    (g/gtk-text-buffer-get-iter-at-offset buf i1 pos)
                                    (g/gtk-text-buffer-get-iter-at-offset buf i2 (inc pos))
                                    (not (zero? (g/gtk-text-buffer-delete-interactive buf i1 i2 1)))
                                    (finally (ffi/free i1) (ffi/free i2)))))
                    prompt-delete-succeeded (delete-at prompt-space-at)
                    after-prompt-delete (g/buffer-text buf)
                    history-delete-succeeded (delete-at 0)
                    after-history-delete (g/buffer-text buf)
                    _ (g/gtk-text-buffer-insert-interactive-at-cursor buf "z" -1 1)
                    input-delete-at (mark-offset buf (:input-start cv))
                    input-delete-succeeded (delete-at input-delete-at)
                    input-after (cview/input-text cv)]
                (check :console-readonly-delete
                       (and drained
                            (not prompt-delete-succeeded) (= before after-prompt-delete)
                            (not history-delete-succeeded) (= before after-history-delete)
                            input-delete-succeeded (= "" input-after))
                       {:drained drained
                        :prompt-delete-succeeded prompt-delete-succeeded
                        :history-delete-succeeded history-delete-succeeded
                        :input-delete-succeeded input-delete-succeeded
                        :before before
                        :after-prompt-delete after-prompt-delete
                        :after-history-delete after-history-delete
                        :input-after input-after}))))))

(defn row-console-output-above-input [cv]
  (go-row :console-output-above-input check
    (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "abc" -1 1))
    (future (cview/append! cv :out "late\n"))
    (let [ok (await-until 1000 20 #(str/includes? (g/buffer-text (:buf cv)) "late\n"))]
      (on-gtk (let [text (g/buffer-text (:buf cv))
                    input (cview/input-text cv)
                    late-at (str/index-of text "late\n")
                    prompt-at (str/index-of text "user=> abc")]
                (check :console-output-above-input
                       (and ok (str/ends-with? text "user=> abc")
                            (some? late-at) (some? prompt-at) (< late-at prompt-at)
                            (= "abc" input))
                       {:text text :input input :late-at late-at :prompt-at prompt-at}))))))

(defn row-console-submit [cv]
  (go-row :console-submit check
    (on-gtk (cview/clear-input! cv)
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 2)" -1 1)
            ;; the offset of the submitted text's first char, captured from
            ;; input-start BEFORE handle-return! moves the mark to the fresh
            ;; prompt -- never a hard-coded number.
            (let [submitted-at (mark-offset (:buf cv) (:input-start cv))
                  got (cview/handle-return! cv false)
                  input-after (cview/input-text cv)
                  text (g/buffer-text (:buf cv))
                  history-readonly (g/has-tag-at? (:buf cv) submitted-at (:readonly-tag cv))]
              (check :console-submit
                     (and (= 1 got)
                          (= {:action :eval :text "(+ 1 2)"} (last @console-submits))
                          history-readonly
                          (= "" input-after)
                          (str/ends-with? text "\nuser=> "))
                     {:return got :last-submit (last @console-submits)
                      :history-readonly history-readonly :submitted-at submitted-at
                      :input-after input-after :text text})))))

(defn row-console-style-after-submit
  "Once a REPL entry is committed, out-end sits exactly
  on the boundary between the just-committed prompt+input span and the
  fresh \"user=> \" prompt right after it -- both tagged :prompt, no untagged
  character between them. GtkTextBuffer treats inserting text at such a
  boundary as inside one continuous :prompt span and extends it onto the
  new text; insert-chunk! must not only ADD its kind's own tag on top via
  gtk_text_buffer_apply_tag but also strip a tag that leaked in from
  the insertion boundary. Submits an entry (so out-end lands on that
  boundary), appends plain :out text, and asserts the appended text
  carries :out but NOT :prompt -- and, the reverse direction, that the
  just-submitted input still carries its own :prompt and stays
  read-only."
  [cv]
  (go-row :console-style-after-submit check
    (let [[submitted-at got] (on-gtk (cview/clear-input! cv)
                                     (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 3 3)" -1 1)
                                     (let [submitted-at (mark-offset (:buf cv) (:input-start cv))
                                           got (cview/handle-return! cv false)]
                                       [submitted-at got]))]
      (future (cview/append! cv :out "plain\n"))
      (let [ok (await-until 1000 20 #(str/includes? (g/buffer-text (:buf cv)) "plain\n"))]
        (on-gtk (let [buf (:buf cv)
                      text (g/buffer-text buf)
                      plain-at (str/index-of text "plain\n")
                      out-tag (get (:tags cv) :out)
                      prompt-tag (get (:tags cv) :prompt)
                      plain-out? (g/has-tag-at? buf plain-at out-tag)
                      plain-prompt? (g/has-tag-at? buf plain-at prompt-tag)
                      submitted-prompt? (g/has-tag-at? buf submitted-at prompt-tag)
                      submitted-readonly? (g/has-tag-at? buf submitted-at (:readonly-tag cv))]
                  (check :console-style-after-submit
                         (and (= 1 got) ok plain-out? (not plain-prompt?)
                              submitted-prompt? submitted-readonly?)
                         {:return got :plain-at plain-at :plain-out plain-out? :plain-prompt plain-prompt?
                          :submitted-at submitted-at :submitted-prompt submitted-prompt?
                          :submitted-readonly submitted-readonly? :text (tail text 200)})))))))

(defn row-console-unbalanced-newline [cv]
  (go-row :console-unbalanced-newline check
    (on-gtk (cview/clear-input! cv)
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(defn f [x]" -1 1)
            (let [before-count (count @console-submits)
                  got (cview/handle-return! cv false)
                  after-count (count @console-submits)]
              (check :console-unbalanced-newline
                     (and (= 0 got) (= before-count after-count))
                     {:return got :before-count before-count :after-count after-count})))))

(defn row-console-flood [cv]
  (go-row :console-flood check
    ;; row-console-unbalanced-newline deliberately leaves "(defn f [x]" in
    ;; the input (its :newline branch never touches the buffer); clear it
    ;; so output-chars below measures the output region, not output plus
    ;; that leftover input.
    (on-gtk (cview/clear-input! cv))
    (let [samples (atom [(System/currentTimeMillis)])
          active? (atom true)
          ;; The sampler measures the GTK main loop itself, so it stays a GTK
          ;; timer rather than a go loop (which would time on-gui! round
          ;; trips instead): one retained callable that re-arms by returning
          ;; 1 until the flood is over.
          _ (on-gtk (g/g-timeout-add 20 (cb/callable (fn [_data]
                                                       (if @active?
                                                         (do (swap! samples conj (System/currentTimeMillis)) 1)
                                                         0))
                                                     [:pointer] :int)
                                     ffi/null))
          _ (future (dotimes [i 100000] (cview/append! cv :out (str i "\n"))))
          ;; active? must always be reset, even if await-until itself
          ;; throws (its predicate reads g/buffer-text, which could) --
          ;; otherwise the sampler's 20 ms GTK timer callable keeps
          ;; re-arming (its own (if @active? ... 1) branch) for the rest
          ;; of the run instead of stopping with this row.
          ok (try
               (await-until 60000 50 #(str/includes? (g/buffer-text (:buf cv)) "99999\n"))
               (finally (reset! active? false)))]
      (on-gtk (let [gaps (map - (rest @samples) @samples)
                    max-gap (if (seq gaps) (apply max gaps) 0)
                    out-chars (- (count (g/buffer-text (:buf cv))) (count @(:prompt-text cv)))]
                ;; drain! doesn't insert a whole oversized batch
                ;; before trimming, and trim! measures out-end's own
                ;; offset instead of copying the output region just to
                ;; count it -- both bounded costs, so the GTK main
                ;; loop's tick cadence should stay well under 1s even
                ;; under this flood (measured 99-111ms).
                (check :console-flood (and ok (<= out-chars cview/MAX-OUTPUT-CHARS) (< max-gap 1000))
                       {:last-line-visible ok
                        :output-chars out-chars
                        :max-output-chars cview/MAX-OUTPUT-CHARS
                        :longest-tick-gap-ms max-gap}))))))

(defn row-console-unicode [cv]
  (go-row :console-unicode check
    (on-gtk (cview/clear-input! cv))
    (cview/append! cv :out "😀한글\n")
    (let [drained (await-until 1000 20 #(str/includes? (g/buffer-text (:buf cv)) "한글\n"))]
      (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "한" -1 1)
              (let [input (cview/input-text cv)
                    buf (:buf cv)
                    text (g/buffer-text buf)
                    prompt-at (str/last-index-of text "user=> ")
                    it (ffi/alloc g/ITER-BYTES)
                    refused (try
                              (g/gtk-text-buffer-get-iter-at-offset buf it prompt-at)
                              (zero? (g/gtk-text-buffer-insert-interactive buf it "X" -1 1))
                              (finally (ffi/free it)))]
                (check :console-unicode
                       (and drained (= "한" input) refused)
                       {:drained drained :input input :refused refused :text (tail text 200)}))))))

(defn row-console-history [cv]
  (go-row :console-history check
    (on-gtk (cview/clear-input! cv)
            (let [handled (cview/handle-history! cv :prev)
                  input (cview/input-text cv)]
              (check :console-history (and handled (= "(inc 1)" input))
                     {:handled handled :input input})))))

(defn row-console-history-multiline-caret [cv]
  (go-row :console-history-multiline-caret check
    (on-gtk (cview/clear-input! cv)
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "line1\nline2" -1 1)
            ;; the insert leaves the caret at the end of "line2" -- the input's
            ;; second (last) line -- so Up must be gated off entirely: a
            ;; multi-line entry's own caret movement must not be hijacked into
            ;; browsing history. history-prev must never even be CALLED.
            (let [before-calls (count @history-prev-calls)
                  got (cview/handle-history! cv :prev)
                  input (cview/input-text cv)]
              (check :console-history-multiline-caret
                     (and (not got) (= "line1\nline2" input) (= before-calls (count @history-prev-calls)))
                     {:got got :input input
                      :calls-before before-calls :calls-after (count @history-prev-calls)})))))

(defn row-console-preedit-guard
  "An active IME preedit composition makes Return and Up/Down no-ops
  (setting the atom directly proves the yield; the real preedit-changed
  wiring is a human smoke step)."
  [cv]
  (go-row :console-preedit-guard check
    (on-gtk (cview/clear-input! cv)
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 2)" -1 1)
            (reset! (:preedit-active? cv) true)
            (let [return-got (cview/handle-return! cv false)
                  history-got (cview/handle-history! cv :prev)
                  input (cview/input-text cv)]
              (reset! (:preedit-active? cv) false)
              (check :console-preedit-guard
                     (and (= 0 return-got) (false? history-got) (= "(+ 1 2)" input))
                     {:return-got return-got :history-got history-got :input input})))))

(defn row-console-set-prompt-keeps-selection
  "set-prompt! runs whenever an evaluation or a connection change moves the
  namespace, so it can land while someone has part of their input
  selected. The rewrite keeps that selection, shifted by the prompt's new
  length, and keeps the typed text and the read-only prompt."
  [cv]
  (go-row :console-set-prompt-keeps-selection check
    (on-gtk (let [buf (:buf cv)]
              (cview/clear-input! cv)
              (g/gtk-text-buffer-insert-interactive-at-cursor buf "abcdef" -1 1)
              (let [at (mark-offset buf (:input-start cv))]
                ;; "bcd", selected right to left: the caret sits at its start
                (g/with-iters* buf (+ at 4) (+ at 1)
                  (fn [a b] (g/gtk-text-buffer-select-range buf b a))))
              (cview/set-prompt! cv "demo.sel")
              (let [at (mark-offset buf (:input-start cv))
                    text (g/buffer-text buf)
                    sel (g/selection-offsets buf)
                    caret (g/cursor-offset buf)
                    input (cview/input-text cv)
                    prompt-readonly (g/has-tag-at? buf (dec at) (:readonly-tag cv))]
                (cview/set-prompt! cv "user")
                (cview/clear-input! cv)
                (check :console-set-prompt-keeps-selection
                       (and (str/ends-with? text "demo.sel=> abcdef")
                            (= "abcdef" input)
                            (= [(+ at 1) (+ at 4)] sel)
                            (= (+ at 1) caret)
                            prompt-readonly)
                       {:tail (subs text (max 0 (- (count text) 40))) :input input :at at :sel sel :caret caret
                        :prompt-readonly prompt-readonly}))))))

(defn row-callable-contains-throw
  "callable's backstop, through the real FFI boundary: two GLib timers
  whose callbacks throw. One names on-throw 1 (G_SOURCE_CONTINUE), so GLib
  keeps calling it until it stops throwing and answers 0 itself; the other
  takes the :int default, 0, so GLib removes it after its one throw. Both
  throws are reported on *err*, and the process carries on."
  [_cv]
  (go-row :callable-contains-throw check
    (let [kept (atom 0)
          dropped (atom 0)]
      (on-gtk
       (g/g-timeout-add 10 (cb/callable (fn [_data]
                                          (if (< (swap! kept inc) 3)
                                            (throw (ex-info "kept timer throws" {}))
                                            0))
                                        [:pointer] :int :on-throw 1)
                        ffi/null)
       (g/g-timeout-add 10 (cb/callable (fn [_data]
                                          (swap! dropped inc)
                                          (throw (ex-info "dropped timer throws" {})))
                                        [:pointer] :int)
                        ffi/null))
      (let [ok (await-until 2000 20 #(= 3 @kept))]
        (<! (timeout 200))
        (on-gtk (check :callable-contains-throw (and ok (= 3 @kept) (= 1 @dropped))
                       {:kept @kept :dropped @dropped}))))))

(defn row-console-input-fn-throw-named
  "A registrant of on-input-changed! that throws must not break the cursor
  move that called it: the console reports it through its error fn as
  \"; cursor: <message>\" and leaves its text alone."
  [cv]
  (go-row :console-input-fn-throw-named check
    (on-gtk (let [buf (:buf cv)
                  saved-fns @(:on-input-fns cv)
                  saved-error-fn @(:on-error-fn cv)
                  errors (atom [])]
              (try
                (cview/set-error-fn! cv (fn [msg] (swap! errors conj msg)))
                (cview/on-input-changed! cv (fn [] (throw (ex-info "bad" {}))))
                (cview/clear-input! cv)
                (g/gtk-text-buffer-insert-interactive-at-cursor buf "ab" -1 1)
                ;; the insert itself fires the throwing fn; only the caret moves may satisfy the check
                (reset! errors [])
                (let [before (g/buffer-text buf)
                      end (g/gtk-text-buffer-get-char-count buf)]
                  (g/with-iters* buf end end (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                  (g/with-iters* buf (dec end) (dec end) (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                  (check :console-input-fn-throw-named
                         (and (some #{"; cursor: bad"} @errors)
                              (= before (g/buffer-text buf)))
                         {:errors @errors :before-chars (count before)
                          :after-chars (count (g/buffer-text buf))}))
                (finally
                  (cview/clear-input! cv)
                  (reset! (:on-input-fns cv) saved-fns)
                  (reset! (:on-error-fn cv) saved-error-fn)))))))
