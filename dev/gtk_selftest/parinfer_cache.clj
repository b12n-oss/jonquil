(ns gtk-selftest.parinfer-cache
  "Editor rows for the Parinfer speedup: the cache, the splice and undo and redo."
  (:require [gtk-async :refer [go-row on-gtk]]
            [gtk-selftest.support :refer [check parinfer-on! parinfer-off! type-str! delete-action! caret!]]
            [jonquil.edit :as jonquil.edit]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.parinfer-edit :as pe]))

;; --- Parinfer speedup rows ---------------------
;; Each row asserts the edit's visible result equals what the fallback
;; (cache-less) path computes on copied strings, so a stale cache or a
;; wrong span shows up as a text mismatch, not just a slow path.
(defn row-parinfer-cache-steady-typing [ed]
  (go-row :parinfer-cache-steady-typing check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (caret! ed 0)
                 (type-str! ed "(")
                 (type-str! ed "a")
                 (type-str! ed "[")
                 (let [text (editor/editor-text ed)
                       cache @(:parinfer-prev-cache ed)]
                   (check :parinfer-cache-steady-typing
                          (and (= "(a[])" text)
                               (= text (:text cache))
                               (= (:gen cache) @(:edit-gen ed)))
                          {:text text :cache-gen (:gen cache) :gen @(:edit-gen ed)}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-cache-invalidated-by-undo [ed]
  (go-row :parinfer-cache-invalidated-by-undo check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a\n  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)          ; -> "(a)\nb", cached
                 (g/gtk-text-buffer-undo (:buf ed))  ; outside the hook
                 (let [stale? (= (:gen @(:parinfer-prev-cache ed)) @(:edit-gen ed))]
                   (caret! ed 5)
                   (delete-action! ed 3 5)        ; must work from the real buffer
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-cache-invalidated-by-undo
                            (and (not stale?) (= "(a)\nb" text))
                            {:was-stale stale? :text text})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-cache-reprimed-by-set-text [ed]
  (go-row :parinfer-cache-reprimed-by-set-text check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (caret! ed 0)
                 (type-str! ed "(")               ; primes the cache
                 (editor/set-text! ed "(x\n  y)") ; a reload re-primes
                 ;; pin the re-prime itself, not just the edit that follows it
                 (let [c @(:parinfer-prev-cache ed)
                       primed? (and (= "(x\n  y)" (:text c))
                                    (= (:gen c) @(:edit-gen ed)))]
                   (caret! ed 5)
                   (delete-action! ed 3 5)
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-cache-reprimed-by-set-text
                            (and primed? (= "(x)\ny" text))
                            {:primed primed? :cache (update c :text pr-str) :text text})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-cache-invalidated-by-plain-op [ed]
  (go-row :parinfer-cache-invalidated-by-plain-op check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "")
                 (caret! ed 0)
                 (type-str! ed "(")               ; primes the cache
                 (editor/apply-op-plain! ed {:edits [[0 2 "(fn [a b]\n     (h a))"]] :caret 0})
                 (caret! ed 19)
                 (type-str! ed "x")               ; first edit: must re-indent
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-cache-invalidated-by-plain-op
                          (= "(fn [a b]\n    (h ax))" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-caret-move-keeps-first-edit-reindent [ed]
  (go-row :parinfer-caret-move-keeps-first-edit-reindent check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(q)
(fn [a b]
     (h a))")
                 (caret! ed 2)
                 (type-str! ed "q")               ; edit form A: cache primes
                 ;; click into the misindented form B: a caret move, no
                 ;; edit, so the gen still matches and the cache hits
                 (caret! ed 24)
                 (type-str! ed "x")               ; first edit of B
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-caret-move-keeps-first-edit-reindent
                          (= "(qq)
(fn [a b]
    (h ax))" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-splice-equals-buffer [ed]
  (go-row :parinfer-splice-equals-buffer check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; set-text! bumps the gen, so the FIRST action after it is
                 ;; a cache miss; each sequence below primes with one
                 ;; throwaway action, then measures a HIT action, counting
                 ;; the ranged reads to prove the splice really ran
                 (let [splices (atom 0)
                       real g/buffer-text-range
                       eqs (atom [])]
                   (with-redefs-fn {#'g/buffer-text-range
                                    (fn [& a] (swap! splices inc) (apply real a))}
                     #(do
                        ;; typing beside an emoji, through a hit
                        (editor/set-text! ed "(a 🙂 b)")
                        (caret! ed 7)
                        (type-str! ed "x")      ; prime (miss)
                        (type-str! ed "(")      ; hit, splice
                        (swap! eqs conj (= (:text @(:parinfer-prev-cache ed))
                                           (editor/editor-text ed)))
                        ;; an op with a fix, through a hit
                        (editor/set-text! ed "(a
  b)")
                        (caret! ed 6)
                        (type-str! ed "z")      ; prime (miss)
                        (editor/do-toggle-comment! ed)   ; hit, fix lands
                        (swap! eqs conj (= (:text @(:parinfer-prev-cache ed))
                                           (editor/editor-text ed)))
                        ;; a one-action selection replace, through a hit
                        (editor/set-text! ed "(a bb c)")
                        (caret! ed 5)
                        (type-str! ed "y")      ; prime (miss)
                        (let [buf (:buf ed)]
                          (g/gtk-text-buffer-begin-user-action buf)
                          (g/with-iters* buf 3 6 (fn [i j] (g/gtk-text-buffer-delete buf i j)))
                          (g/with-iters* buf 3 3 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                          (g/gtk-text-buffer-insert-interactive-at-cursor buf "(" -1 1)
                          (g/gtk-text-buffer-end-user-action buf))
                        (swap! eqs conj (= (:text @(:parinfer-prev-cache ed))
                                           (editor/editor-text ed)))))
                   (check :parinfer-splice-equals-buffer
                          ;; six: the three primes splice too (a fresh begin
                          ;; read is ground truth), plus the three measured hits
                          (and (= [true true true] @eqs) (= 6 @splices))
                          {:eqs @eqs :splices @splices
                           :t3 (editor/editor-text ed)}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-plain-op-updates-a-valid-cache [ed]
  (go-row :parinfer-plain-op-updates-a-valid-cache check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; an MCP or plugin write knows its own edits: when the
                 ;; cache is valid at that moment, apply-edits on the cached
                 ;; string keeps it valid with no buffer read, and the next
                 ;; keystroke is a hit
                 (editor/set-text! ed "(a)")      ; primes
                 (editor/apply-op-plain! ed {:edits [[1 2 "fn [a b]
     (h a)"]] :caret 0})
                 (let [fulls (atom 0)
                       real g/buffer-text]
                   (with-redefs-fn {#'g/buffer-text
                                    (fn [& a] (swap! fulls inc) (apply real a))}
                     #(do (caret! ed 18)
                          (type-str! ed "x")))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-plain-op-updates-a-valid-cache
                            (and (= "(fn [a b]
    (h xa))" text)
                                 eq (zero? @fulls))
                            {:text text :eq eq :full-reads @fulls})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-plain-op-leaves-an-invalid-cache [ed]
  (go-row :parinfer-plain-op-leaves-an-invalid-cache check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; after an undo the cache is stale; a plain op then must
                 ;; NOT half-trust it, and the next keystroke misses
                 ;; honestly and still lands the right text
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (g/gtk-text-buffer-undo (:buf ed))   ; cache now stale
                 (editor/apply-op-plain! ed {:edits [[0 0 "(q)
"]] :caret 0})
                 (caret! ed 9)
                 (delete-action! ed 7 9)
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-plain-op-leaves-an-invalid-cache
                          (= "(q)
(a)
b" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-nul-reload-clears-the-prime [ed]
  (go-row :parinfer-nul-reload-clears-the-prime check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; GTK takes C strings, so a NUL truncates what the buffer
                 ;; keeps; the prime must notice the counts disagree and
                 ;; clear, or the next keystroke splices from a string the
                 ;; buffer never held
                 (editor/set-text! ed (str "(a)" (char 0) "(b)"))
                 (caret! ed 1)
                 (type-str! ed "x")
                 (let [text (editor/editor-text ed)
                       eq (= (:text @(:parinfer-prev-cache ed)) text)]
                   (check :parinfer-nul-reload-clears-the-prime
                          (and (= "(xa)" text) eq)
                          {:text text :eq eq}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-nul-plain-op-clears-the-cache [ed]
  (go-row :parinfer-nul-plain-op-clears-the-cache check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; an op's replacement text carrying a NUL is refused by
                 ;; apply-op! outright (never landed truncated). The refusal still
                 ;; clears the cache (one honest miss), and the next
                 ;; keystroke lands on the untouched text with the cache
                 ;; coherent again.
                 (editor/set-text! ed "(a)")
                 (let [r (editor/apply-op-plain!
                          ed {:edits [[1 2 (str "x" (char 0) "y")]] :caret 0})
                       ;; pin the clear itself (the accepted one honest
                       ;; miss), so keeping the cache would have to be a
                       ;; deliberate change
                       cleared? (nil? @(:parinfer-prev-cache ed))]
                   (caret! ed 1)
                   (type-str! ed "z")
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-nul-plain-op-clears-the-cache
                            (and (nil? r) cleared? (= "(za)" text) eq)
                            {:r r :cleared cleared? :text text :eq eq})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-redo-splices-the-end-read [ed]
  (go-row :parinfer-redo-splices-the-end-read check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; redo is the undo row's mirror: a miss whose begin read
                 ;; is ground truth, so the end-side splice must still run
                 ;; and land the fallback's text
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (g/gtk-text-buffer-undo (:buf ed))
                 (g/gtk-text-buffer-redo (:buf ed))
                 (let [splices (atom 0)
                       real g/buffer-text-range]
                   (with-redefs-fn {#'g/buffer-text-range
                                    (fn [& a] (swap! splices inc) (apply real a))}
                     #(do (caret! ed 2)
                          (type-str! ed "x")))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-redo-splices-the-end-read
                            (and (= "(ax)
b" text) eq (pos? @splices))
                            {:text text :eq eq :splices @splices})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-undo-recovery-reads-nothing [ed]
  (go-row :parinfer-undo-recovery-reads-nothing check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; undo replays through the buffer's own insert and delete,
                 ;; so the armed outside-span records what changed since the
                 ;; prime: the next keystroke rebuilds prev from the cache
                 ;; plus one ranged read, no full copy
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (g/gtk-text-buffer-undo (:buf ed))
                 (let [fulls (atom 0) ranged (atom 0)
                       real-f g/buffer-text real-r g/buffer-text-range]
                   (with-redefs-fn {#'g/buffer-text
                                    (fn [& a] (swap! fulls inc) (apply real-f a))
                                    #'g/buffer-text-range
                                    (fn [& a] (swap! ranged inc) (apply real-r a))}
                     #(do (caret! ed 5)
                          (delete-action! ed 3 5)))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-undo-recovery-reads-nothing
                            (and (= "(a)
b" text) eq (zero? @fulls) (pos? @ranged))
                            {:text text :eq eq :full-reads @fulls
                             :ranged-reads @ranged})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-undo-redo-interleaving [ed]
  (go-row :parinfer-undo-redo-interleaving check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; type, undo, type, redo, type: every measured keystroke
                 ;; lands on exactly the real buffer with no full read (the
                 ;; second type clears the redo stack, so the redo is dead:
                 ;; no signals, no gen bump, the next type stays a hit)
                 (editor/set-text! ed "(q)
(fn [a b]
     (h a))")
                 (caret! ed 22)
                 (type-str! ed "x")
                 (let [fulls (atom 0) real-f g/buffer-text
                       eqs (atom [])
                       ;; through the captured reader, so the row's own
                       ;; equality checks don't count as the hook's reads
                       eq! (fn [] (swap! eqs conj
                                         (= (:text @(:parinfer-prev-cache ed))
                                            (real-f (:buf ed)))))]
                   (with-redefs-fn {#'g/buffer-text
                                    (fn [& a] (swap! fulls inc) (apply real-f a))}
                     #(do (g/gtk-text-buffer-undo (:buf ed))
                          (caret! ed 22) (type-str! ed "y") (eq!)
                          (g/gtk-text-buffer-redo (:buf ed))
                          (caret! ed 22) (type-str! ed "w") (eq!)))
                   (let [text (editor/editor-text ed)]
                     (check :parinfer-undo-redo-interleaving
                            (and (= "(q)
(fn [a b]
    (h ywa))" text)
                                 (every? true? @eqs) (= 2 (count @eqs))
                                 (zero? @fulls))
                            {:text text :eqs @eqs :full-reads @fulls})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-undo-past-a-reload [ed]
  (go-row :parinfer-undo-past-a-reload check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; set-text! is an irreversible action: it empties the undo
                 ;; history, so an undo straight after it must change
                 ;; nothing, and the next keystroke lands on the real buffer
                 ;; whatever the span says
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (editor/set-text! ed "(zz)")
                 (g/gtk-text-buffer-undo (:buf ed))
                 (caret! ed 3)
                 (type-str! ed "x")
                 (let [text (editor/editor-text ed)
                       eq (= (:text @(:parinfer-prev-cache ed)) text)]
                   (check :parinfer-undo-past-a-reload
                          (and (= "(zzx)" text) eq)
                          {:text text :eq eq}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-undo-keeps-first-edit-reindent [ed]
  (go-row :parinfer-undo-keeps-first-edit-reindent check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; a recovered prev never carries :edit-at, so a form
                 ;; reached through undo still re-indents on its first
                 ;; edit: recovery never fakes consistency (the probe is the
                 ;; user's first-edit re-indent)
                 (editor/set-text! ed "(q)
(fn [a b]
     (h a))")
                 (caret! ed 2)
                 (type-str! ed "z")
                 (g/gtk-text-buffer-undo (:buf ed))
                 (caret! ed 22)
                 (type-str! ed "x")
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-undo-keeps-first-edit-reindent
                          (= "(q)
(fn [a b]
    (h xa))" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-reload-primes-the-cache [ed]
  (go-row :parinfer-reload-primes-the-cache check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; set-text! hands the new text in, so it primes the cache
                 ;; for free: the next keystroke is a hit
                 ;; (zero full reads), and the first-edit re-indent still
                 ;; lands because no consistency offset is stored
                 (editor/set-text! ed "(q)
(fn [a b]
     (h a))")
                 (let [fulls (atom 0)
                       real g/buffer-text]
                   (with-redefs-fn {#'g/buffer-text
                                    (fn [& a] (swap! fulls inc) (apply real a))}
                     #(do (caret! ed 22)
                          (type-str! ed "x")))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-reload-primes-the-cache
                            (and (= "(q)
(fn [a b]
    (h xa))" text)
                                 eq (zero? @fulls))
                            {:text text :eq eq :full-reads @fulls})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-reload-double-flip [ed]
  (go-row :parinfer-reload-double-flip check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; two reloads back to back: the prime must carry the LAST
                 ;; text with the LAST gen, or the next keystroke trusts a
                 ;; stale string
                 (editor/set-text! ed "(zz)")
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-reload-double-flip
                          (= "(a)
b" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-miss-still-splices-the-end-read [ed]
  (go-row :parinfer-miss-still-splices-the-end-read check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; a miss's begin read is ground truth, so the end-side
                 ;; splice is just as exact there: after
                 ;; an undo (a miss), the keystroke must still splice and
                 ;; land the fallback's text
                 (editor/set-text! ed "(a
  b)")
                 (caret! ed 5)
                 (delete-action! ed 3 5)
                 (g/gtk-text-buffer-undo (:buf ed))
                 (let [splices (atom 0)
                       real g/buffer-text-range]
                   (with-redefs-fn {#'g/buffer-text-range
                                    (fn [& a] (swap! splices inc) (apply real a))}
                     #(do (caret! ed 5)
                          (delete-action! ed 3 5)))
                   (let [text (editor/editor-text ed)
                         eq (= (:text @(:parinfer-prev-cache ed)) text)]
                     (check :parinfer-miss-still-splices-the-end-read
                            (and (= "(a)
b" text) eq (pos? @splices))
                            {:text text :eq eq :splices @splices})))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-fix-moves-the-edit-offset [ed]
  (go-row :parinfer-fix-moves-the-edit-offset check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; the first edit's fix re-indents EARLIER lines, moving
                 ;; text before the edit offset; the cached offset must
                 ;; follow it, or the next form inherits the consistency
                 ;; flag
                 (editor/set-text! ed "(f [a b]
        a)
(fn [a b]
     (h a))")
                 (caret! ed 18)
                 (type-str! ed "x")
                 (caret! ed (+ 3 (.indexOf (editor/editor-text ed) "(h a")))
                 (type-str! ed "y")
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-fix-moves-the-edit-offset
                          (= "(f [a b]
   ax)
(fn [a b]
    (h ya))" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-noop-action-marks-nothing [ed]
  (go-row :parinfer-noop-action-marks-nothing check
    (on-gtk (try (parinfer-on! ed :smart)
                 ;; stepping over a closing quote is a user action that
                 ;; changes nothing; it must not mark the form consistent
                 (editor/set-text! ed "(q)\n(fn [a b]\n     (h \"a\"))")
                 (caret! ed 24)                      ; just before the closing quote
                 (editor/handle-key! ed 0x022 0)     ; quote over the closer: step past
                 (type-str! ed "x")
                 (let [text (editor/editor-text ed)]
                   (check :parinfer-noop-action-marks-nothing
                          (= "(q)\n(fn [a b]\n    (h \"a\"x))" text)
                          {:text text}))
                 (finally (parinfer-off! ed))))))

(defn row-parinfer-selection-replace-bounds [ed]
  (go-row :parinfer-selection-replace-bounds check
    (on-gtk (try (parinfer-on! ed :smart)
                 (editor/set-text! ed "(a bb c)")
                 ;; one user action, delete then insert, as a selection
                 ;; replace emits: the span must union both signals
                 (let [buf (:buf ed)]
                   (g/gtk-text-buffer-begin-user-action buf)
                   (g/with-iters* buf 3 5 (fn [i j] (g/gtk-text-buffer-delete buf i j)))
                   (g/with-iters* buf 3 3 (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
                   (g/gtk-text-buffer-insert-interactive-at-cursor buf "(" -1 1)
                   (g/gtk-text-buffer-end-user-action buf))
                 ;; the fallback path on copied strings is the oracle
                 (let [r (pe/adjust {:mode :smart
                                     :prev-text (apply str "(a bb c)") :prev-caret 3
                                     :text (apply str "(a ( c)") :caret 4})
                       want (jonquil.edit/apply-edits "(a ( c)" (:edits r))
                       text (editor/editor-text ed)]
                   (check :parinfer-selection-replace-bounds
                          (= want text)
                          {:want want :text text}))
                 (finally (parinfer-off! ed))))))
