(ns gtk-selftest-window.inspector
  "The inspector pane widget in isolation. Every row builds its own
  pane (jonquil.gtk.inspector-pane/make-pane) and hosts its :root in a
  fresh, real, transient GtkWindow -- never the self-test's own main
  window, and never disturbing it for later rows -- long enough to check
  rendering, keys and measurement, then closes that window.

  The later rows (below the isolated-pane ones) drive the REAL window's own
  pane instead (ui's :inspector), through the ui's own :inspect-last!*/
  :inspect-form!* entry fns and the pane's own key dispatch -- the actual
  wiring: layout, Cmd/Ctrl+I, Cmd/Ctrl+Shift+I, the menu, Escape, and the
  in-process inspect! round trip end to end. They build on one another's
  state (the SAME pane persists across rows, exactly as it does in the
  real app -- run-row only resets the editor and the console's input,
  never the inspector panel), so their order matters."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.callbacks :as callbacks]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.popover :as popover]
            [jonquil.inspect :as inspect]))

;; gdkkeysyms.h (GTK 4.22.4 installed headers): Down 0xff54, Return 0xff0d,
;; Escape 0xff1b -- this file's own private copies, same convention as
;; every other gtk-selftest-window.* namespace's local key table.
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-ESCAPE 0xff1b)

(defn- three-row-view []
  {:path "*1" :info "3 items" :header "" :start 0 :total 3
   :entries ["0" "1" "2"]})

(defn- leaf-view []
  {:path "*1" :info "String" :header "" :leaf "\"hello\""})

(defn- with-pane
  "Builds a pane (inspector-pane/make-pane opts) inside a fresh, real,
  transient GtkWindow, shows it and waits for the rows view to have a real
  allocated size, calls (f pane), then closes that window. Every GTK call
  here runs on the GTK thread; the wait parks the calling go block
  (dev/gtk_async.clj's own rule: the GTK thread never parks).

  jonquil.gtk.popover parents a popover directly to the rows view's own
  container (gtk_widget_set_parent, not gtk_scrolled_window_set_child), so
  destroying win would otherwise finalize that container while the
  popover is still attached to it -- harmless in the real app (a
  production popover lives for the whole session, per that namespace's
  own docstring), but this pane is thrown away fresh every row, so the
  def popover is unparented explicitly first, the same way every
  throwaway popover row elsewhere in this file's siblings already does."
  [opts f]
  (let [{:keys [win pane]}
        (on-gtk (let [win (g/gtk-window-new)
                      pane (inspector-pane/make-pane opts)]
                  (g/gtk-window-set-child win (:root pane))
                  (g/gtk-window-present win)
                  {:win win :pane pane}))]
    (try
      (await-until 2000 20 #(pos? (g/gtk-widget-get-width (:view pane))))
      (f pane)
      ;; A row that throws still tears its transient window down.
      (finally
        (on-gtk (g/gtk-widget-unparent (:popover (:def-popover pane)))
                (g/gtk-window-close win))))))

(defn- noop-opts []
  {:on-request (fn [_] nil) :on-close (fn [] nil) :on-def (fn [_] nil)})

(defn row-pane-renders-view
  "make-pane + show-state! on a literal 3-entry view: header, info, three
  row lines, the footer \"1-3 of 3\", the selected tag on line 0 only."
  [_ui]
  (go-row :row-pane-renders-view support/check
    (with-pane (noop-opts)
      (fn [pane]
        (on-gtk
         (inspector-pane/show-state! pane (inspect/open (three-row-view)))
         (let [header (g/label-text (:header pane))
               info (g/label-text (:info pane))
               lines (str/split-lines (g/buffer-text (:buf pane)))
               footer (g/label-text (:footer pane))
               tag0? (g/has-tag-at? (:buf pane) 0 (:selected-tag pane))
               line1-start (inc (count (first lines)))
               tag1? (g/has-tag-at? (:buf pane) line1-start (:selected-tag pane))]
           (support/check :pane-renders-view
                          (and (= "*1" header) (= "3 items" info)
                               (= 3 (count lines))
                               (= "1-3 of 3 · ↑↓ move · ⏎ in · ← out · d def · m meta · v table" footer)
                               tag0? (not tag1?))
                          {:header header :info info :lines lines :footer footer
                           :tag0 tag0? :tag1 tag1?})))))))

(defn row-pane-d-respects-disabled
  "d opens the def prompt, but not while the panel's actions are disabled
  (a lost connection), where the def button is already insensitive: the
  key is still consumed, and the popover stays hidden. (d is 0x064; this
  file's KEY-D is defined further down, and Jolt resolves at compile.)"
  [_ui]
  (go-row :row-pane-d-respects-disabled support/check
    (with-pane (noop-opts)
      (fn [pane]
        (on-gtk
         (let [dp (:def-popover pane)
               shown? (fn [] (pos? (g/gtk-widget-get-visible (:popover dp))))
               _ (inspector-pane/show-state! pane (assoc (inspect/open (three-row-view)) :disabled? true))
               h-disabled (inspector-pane/dispatch-key! pane 0x064)
               shown-disabled? (shown?)
               _ (inspector-pane/show-state! pane (inspect/open (three-row-view)))
               h-enabled (inspector-pane/dispatch-key! pane 0x064)
               shown-enabled? (shown?)]
           (popover/hide! dp)
           (support/check :pane-d-respects-disabled
                          (and (= 1 h-disabled) (not shown-disabled?) (= 1 h-enabled) shown-enabled?)
                          {:h-disabled h-disabled :shown-disabled shown-disabled?
                           :h-enabled h-enabled :shown-enabled shown-enabled?})))))))

(defn row-pane-keys-step
  "Down moves the tag to line 1 with no request; Return calls on-request
  with [:in {:idx 1}]; Escape calls on-close."
  [_ui]
  (go-row :row-pane-keys-step support/check
    (let [requests (atom []) closed? (atom false)]
      (with-pane {:on-request (fn [r] (swap! requests conj r))
                  :on-close (fn [] (reset! closed? true))
                  :on-def (fn [_] nil)}
        (fn [pane]
          (on-gtk
           (inspector-pane/show-state! pane (inspect/open (three-row-view)))
           (let [tag0-before? (g/has-tag-at? (:buf pane) 0 (:selected-tag pane))
                 h-down (inspector-pane/dispatch-key! pane KEY-DOWN)
                 lines (str/split-lines (g/buffer-text (:buf pane)))
                 line1-start (inc (count (first lines)))
                 tag1-after? (g/has-tag-at? (:buf pane) line1-start (:selected-tag pane))
                 no-request-yet? (empty? @requests)
                 h-return (inspector-pane/dispatch-key! pane KEY-RETURN)
                 request (last @requests)
                 h-escape (inspector-pane/dispatch-key! pane KEY-ESCAPE)]
             (support/check :pane-keys-step
                            (and (= 1 h-down) tag0-before? tag1-after? no-request-yet?
                                 (= 1 h-return) (= [:in {:idx 1}] request)
                                 (= 1 h-escape) @closed?)
                            {:h-down h-down :tag1-after tag1-after? :no-request no-request-yet?
                             :h-return h-return :request request
                             :h-escape h-escape :closed @closed?}))))))))

(defn row-pane-leaf
  "A leaf view shows the leaf text verbatim, and its footer is only the
  hints after out (nothing to move over or go into)."
  [_ui]
  (go-row :row-pane-leaf support/check
    (with-pane (noop-opts)
      (fn [pane]
        (on-gtk
         (inspector-pane/show-state! pane (inspect/open (leaf-view)))
         (let [text (g/buffer-text (:buf pane))
               footer (g/label-text (:footer pane))]
           (support/check :pane-leaf
                          (and (= "\"hello\"" text)
                               (= "← out · d def · m meta · v table" footer))
                          {:text text :footer footer})))))))

(defn row-pane-measure
  "measure gives :cols between 20 and 200 and :rows of at least 5."
  [_ui]
  (go-row :row-pane-measure support/check
    (with-pane (noop-opts)
      (fn [pane]
        (on-gtk
         (inspector-pane/show-state! pane (inspect/open (three-row-view)))
         (let [{:keys [cols rows]} (inspector-pane/measure pane)]
           (support/check :pane-measure
                          (and (<= 20 cols 200) (>= rows 5))
                          {:cols cols :rows rows})))))))

(defn row-pane-ready-wait-survives-measure-throw
  "measure-when-ready! on a pane that is never shown waits out its cap; a
  throw from measure then is reported in the footer, and every queued f
  is still called, with measure's floors, so the window's in-flight flag
  (released only through f) can't stay stuck."
  [_ui]
  (go-row :row-pane-ready-wait-survives-measure-throw support/check
    (let [pane (on-gtk (inspector-pane/make-pane (noop-opts)))
          got (atom [])]
      (with-redefs [inspector-pane/measure (fn [_] (throw (ex-info "forced measure failure" {})))]
        (on-gtk (inspector-pane/measure-when-ready! pane #(swap! got conj [:a %]))
                (inspector-pane/measure-when-ready! pane #(swap! got conj [:b %])))
        (await-until 3000 20 #(= 2 (count @got))))
      (on-gtk
       (let [footer (g/label-text (:footer pane))]
         (support/check :pane-ready-wait-survives-measure-throw
                        (and (= [[:a {:cols 20 :rows 5}] [:b {:cols 20 :rows 5}]] @got)
                             (str/includes? footer "forced measure failure"))
                        {:got @got :footer footer}))))))

(defn row-pane-note-clears
  "A state with :note \"not tabular\" shows it in the footer, and the
  footer drops it again within 4 s (NOTE-CLEAR-MS is 3000)."
  [_ui]
  (go-row :row-pane-note-clears support/check
    (with-pane (noop-opts)
      (fn [pane]
        (let [state (assoc (inspect/open (three-row-view)) :note "not tabular")
              before (on-gtk (inspector-pane/show-state! pane state)
                             (g/label-text (:footer pane)))
              cleared? (await-until 4000 50
                                    #(not (str/includes? (g/label-text (:footer pane)) "not tabular")))]
          (support/check :pane-note-clears
                         (and (str/includes? before "not tabular") cleared?)
                         {:before before :cleared cleared?}))))))

;; --- the window's own pane, wired end to end ------------------------

;; This file's own private copies, gdkkeysyms.h (GTK 4.22.4 installed
;; headers), same convention as every sibling gtk-selftest-window.* key
;; table -- jonquil.gtk.inspector-pane's own KEY-LEFT/KEY-D/KEY-M/KEY-V are
;; private to that namespace.
(def ^:private KEY-LEFT 0xff51)
(def ^:private KEY-D 0x064)
(def ^:private KEY-M 0x06d)
(def ^:private KEY-V 0x076)

(defn- submit-repl!
  "Submits code as a REPL entry through the console's own Return path (an
  interactive insert + handle-return!, never a synthesized key -- the same
  idiom row-stdin-roundtrip/row-quick-doc-after-define already use), and
  waits (up to deadline-ms) for it to finish. [ok mark]."
  [ui code deadline-ms]
  (let [cv (:console ui)
        mark (on-gtk (let [m (support/out-end-offset ui)]
                       (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
                       (cview/handle-return! cv false)
                       m))
        ok (await-until deadline-ms 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "=>")))]
    [ok mark]))

(defn- footer-start
  "The footer's own 0-based start (jonquil.inspect/footer's \"N-M of T\"),
  read back off pane's live footer label."
  [pane]
  (dec (Integer/parseInt (first (str/split (g/label-text (:footer pane)) #"-")))))

(defn row-inspect-focus-after-open
  "On the panel's FIRST open in this real window (every isolated-pane
  row above builds its own throwaway pane -- :ui's own pane has never
  been shown before this row runs): inspect-last! grabs the rows view's
  own focus, so Return goes into the panel instead of inserting a
  newline in the editor's own buffer; and the :start it sends
  carries the SAME :cols/:rows a measure taken right after opening gives,
  not the zero-floor measure an unallocated view would give
  (show-inspector-panel! must not measure in the very callback that first
  makes the root visible, before GTK's own layout pass has run).
  A self-contained REPL entry: every later row's own
  :inspect-last!* resets inspector-state to nil before its own :start
  anyway, so this row's own :in step below can't leak into what
  row-inspect-last-after-repl and row-inspect-in-out expect next."
  [ui]
  (go-row :row-inspect-focus-after-open support/check
    (let [pane (:inspector ui) win (:window ui) ed (:editor ui)
          [submitted? _] (submit-repl! ui "[1 2 3]" 3000)]
      (on-gtk ((:inspect-last!* ui)))
      (let [opened? (await-until 2000 20
                                 #(and (pos? (g/gtk-widget-get-visible (:root pane)))
                                       (= "*1" (g/label-text (:header pane)))))
            focused? (await-until 2000 20 #(inspector-pane/focused? pane win))
            sent (on-gtk ((:inspector-last-args* ui)))
            measured (on-gtk (inspector-pane/measure pane))
            before (on-gtk (editor/editor-text ed))
            h (on-gtk (inspector-pane/dispatch-key! pane KEY-RETURN))
            after (on-gtk (editor/editor-text ed))
            ;; Let the async :in round trip settle before the next row's
            ;; own :inspect-last!* -- a fresh :start is dropped while
            ;; one request is still pending.
            settled? (await-until 2000 20 #(str/includes? (g/label-text (:header pane)) "/"))]
        (on-gtk
         (support/check :row-inspect-focus-after-open
                        (and submitted? opened? focused? (= 1 h) (= before after) settled?
                             (= (:cols sent) (:cols measured)) (= (:rows sent) (:rows measured)))
                        {:submitted submitted? :opened opened? :focused focused? :settled settled?
                         :h h :before before :after after :sent sent :measured measured}))))))

(defn row-inspect-last-after-repl
  "A REPL entry {:a [1 2 {:b 3}]}, then :inspect-last!*: within 2000 ms the
  panel is visible, its header reads \"*1\" (jonquil.inspect.view/render's
  own :path for the top frame), its info names 1 item, and the row for :a
  is on screen -- table or list (a single-entry map's own single child
  trivially satisfies jonquil.inspect.view/table-keys' \"uniform\" check,
  so this renders as a one-row TABLE, not list-row's \"label separator
  preview\" text; either way \":a\" is the row's own label), which is
  exactly what row-inspect-table-meta's own v/toggle exercises on purpose
  a few rows down -- this row only checks the panel opened onto the right
  value, not which of the two forms it's currently in."
  [ui]
  (go-row :row-inspect-last-after-repl support/check
    (let [pane (:inspector ui)
          [submitted? _] (submit-repl! ui "{:a [1 2 {:b 3}]}" 3000)]
      (on-gtk ((:inspect-last!* ui)))
      (let [opened? (await-until 2000 20
                                 #(and (pos? (g/gtk-widget-get-visible (:root pane)))
                                       (= "*1" (g/label-text (:header pane)))))]
        (on-gtk
         (let [header (g/label-text (:header pane))
               info (g/label-text (:info pane))
               text (g/buffer-text (:buf pane))]
           (support/check :row-inspect-last-after-repl
                          (and submitted? opened? (= "*1" header)
                               (str/includes? info "1 items") (str/includes? text ":a"))
                          {:submitted submitted? :opened opened? :header header
                           :info info :text text})))))))

(defn row-inspect-in-out
  "Continues from row-inspect-last-after-repl's own open pane (still *1 on
  {:a [1 2 {:b 3}]}): Return goes in on :a (idx 0), landing on *1 / :a --
  the vector [1 2 {:b 3}], three DIFFERENT-shaped children so table-keys'
  own uniform check fails and this level renders as a plain three-row
  list, unlike the root above. Left comes back out with line 0 selected
  (out's own :cursor, recorded as 0 when in went in from selected 0)."
  [ui]
  (go-row :row-inspect-in-out support/check
    (let [pane (:inspector ui)
          h-in (on-gtk (inspector-pane/dispatch-key! pane KEY-RETURN))
          in? (await-until 2000 20 #(str/includes? (g/label-text (:header pane)) "/ :a"))
          lines-in (on-gtk (str/split-lines (g/buffer-text (:buf pane))))
          h-out (on-gtk (inspector-pane/dispatch-key! pane KEY-LEFT))
          out? (await-until 2000 20 #(= "*1" (g/label-text (:header pane))))]
      (on-gtk
       (let [tag0? (g/has-tag-at? (:buf pane) 0 (:selected-tag pane))]
         (support/check :row-inspect-in-out
                        (and (= 1 h-in) in? (= 3 (count lines-in))
                             (= 1 h-out) out? tag0?)
                        {:h-in h-in :in in? :lines-in lines-in
                         :h-out h-out :out out? :tag0 tag0?}))))))

(defn row-inspect-page-edge
  "A REPL entry (vec (range n)), open, then Down past the last visible
  row: the footer's start moves on and line 0 is selected (jonquil.inspect/
  key-step's own :page {:delta 1} request). The same
  row also checks that a fresh :start is dropped while a request is
  pending: a SECOND, fresh page -- (vec (range (* rows 5))), sized
  off the pane's own measured rows so it always spans exactly 5 real pages
  regardless of the real window's size -- crossed with TWO Down presses
  back to back, no wait between them, must still land on page 1 (start
  advances by exactly one page's worth, not two): a dropped-nothing bug
  would send a second :page request that the session processes for real,
  advancing the shared view atom's own page counter twice.

  The first value is sized off the pane's measured rows too (twice as
  many items): a fixed 40 fitted on one page whenever the pane had 40 or
  more rows, and then Down had no edge to cross (seen on macOS with 55)."
  [ui]
  (go-row :row-inspect-page-edge support/check
    (let [pane (:inspector ui)
          n-items (* 2 (on-gtk (:rows (inspector-pane/measure pane))))
          [submitted1? _] (submit-repl! ui (str "(vec (range " n-items "))") 3000)]
      (on-gtk ((:inspect-last!* ui)))
      ;; The panel is already visible from an earlier row, so a bare
      ;; visibility check can't tell "still showing the last row's
      ;; value" from "this fresh :start reply landed" -- wait for the
      ;; info line to actually name THIS value instead.
      (let [opened1? (await-until 2000 20
                                  #(str/includes? (g/label-text (:info pane)) (str n-items " items")))
            n1 (on-gtk (count (str/split-lines (g/buffer-text (:buf pane)))))
            start0 (on-gtk (footer-start pane))]
        (on-gtk (dotimes [_ (dec n1)] (inspector-pane/dispatch-key! pane KEY-DOWN)))
        (on-gtk (inspector-pane/dispatch-key! pane KEY-DOWN))
        (let [advanced1? (await-until 2000 20 #(> (footer-start pane) start0))
              start1 (on-gtk (footer-start pane))
              sel0-after1? (on-gtk (g/has-tag-at? (:buf pane) 0 (:selected-tag pane)))
              rows (on-gtk (:rows (inspector-pane/measure pane)))
              big-n (* rows 5)
              [submitted2? _] (submit-repl! ui (str "(vec (range " big-n "))") 3000)]
          (on-gtk ((:inspect-last!* ui)))
          (let [opened2? (await-until 2000 20
                                      #(str/includes? (g/label-text (:info pane)) (str big-n " items")))
                start2-0 (on-gtk (footer-start pane))]
            (on-gtk (dotimes [_ (dec rows)] (inspector-pane/dispatch-key! pane KEY-DOWN)))
                  ;; two rapid presses across the edge, no await in between.
            (on-gtk (inspector-pane/dispatch-key! pane KEY-DOWN)
                    (inspector-pane/dispatch-key! pane KEY-DOWN))
            (let [advanced2? (await-until 2000 20 #(> (footer-start pane) start2-0))
                  start2-1 (on-gtk (footer-start pane))
                  sel0-after2? (on-gtk (g/has-tag-at? (:buf pane) 0 (:selected-tag pane)))
                  one-page? (= (- start2-1 start2-0) rows)]
              (on-gtk
               (support/check :row-inspect-page-edge
                              (and submitted1? opened1? advanced1? sel0-after1?
                                   submitted2? opened2? advanced2? sel0-after2? one-page?)
                              {:submitted1 submitted1? :opened1 opened1? :advanced1 advanced1?
                               :sel0-after1 sel0-after1? :submitted2 submitted2? :opened2 opened2?
                               :advanced2 advanced2? :sel0-after2 sel0-after2?
                               :start0 start0 :start1 start1
                               :rows rows :start2-0 start2-0 :start2-1 start2-1
                               :one-page one-page?})))))))))

(def ^:private KEY-UP 0xff52)

(defn- line-offset
  "The character offset where line n of the rows view starts."
  [pane n]
  (let [lines (str/split-lines (g/buffer-text (:buf pane)))]
    (reduce + (map #(inc (count %)) (take n lines)))))

(defn- selected-line?
  [pane n]
  (g/has-tag-at? (:buf pane) (line-offset pane n) (:selected-tag pane)))

(defn row-inspect-reselect
  "The window and the pane share one state, so a reply lands on the row
  the key step asked for. Continues from row-inspect-page-edge's panel (a
  later page of a long vector, row 0 selected): Up goes back a page and
  selects that page's LAST row. Then a fresh [10 20 30 40]: Down, Down,
  Return goes in on row 2, and Left comes back out with row 2 selected,
  not row 0."
  [ui]
  (go-row :row-inspect-reselect support/check
    (let [pane (:inspector ui)
          start-before (on-gtk (footer-start pane))
          ;; Up asks for the previous page (its last row); Left in the SAME
          ;; step is dropped (Up's request is still in flight) and must not
          ;; rewrite Up's :pending.
          _ (on-gtk (inspector-pane/dispatch-key! pane KEY-UP)
                    (inspector-pane/dispatch-key! pane KEY-LEFT))
          paged-back? (await-until 2000 20 #(< (footer-start pane) start-before))
          n-lines (on-gtk (count (str/split-lines (g/buffer-text (:buf pane)))))
          last-selected? (on-gtk (selected-line? pane (dec n-lines)))
          [submitted? _] (submit-repl! ui "[10 20 30 40]" 3000)]
      (on-gtk ((:inspect-last!* ui)))
      (let [opened? (await-until 2000 20 #(str/includes? (g/label-text (:info pane)) "4 items"))]
        (on-gtk (inspector-pane/dispatch-key! pane KEY-DOWN)
                (inspector-pane/dispatch-key! pane KEY-DOWN)
                (inspector-pane/dispatch-key! pane KEY-RETURN))
        (let [in? (await-until 2000 20 #(str/includes? (g/label-text (:header pane)) "/ 2"))]
          (on-gtk (inspector-pane/dispatch-key! pane KEY-LEFT))
          (let [out? (await-until 2000 20 #(= "*1" (g/label-text (:header pane))))
                row2? (on-gtk (selected-line? pane 2))]
            (on-gtk
             (support/check :row-inspect-reselect
                            (and paged-back? last-selected? submitted? opened? in? out? row2?)
                            {:paged-back paged-back? :last-selected last-selected? :n-lines n-lines
                             :submitted submitted? :opened opened? :in in? :out out? :row2 row2?}))))))))

(defn row-inspect-table-meta
  "A REPL entry [{:id 1 :name \"a\"} {:id 2 :name \"b\"}] opens as a table
  (both children are maps with the same keys, table-keys' own uniform-maps?
  path) -- the table-header widget is visible. v (:table toggle) shows the
  list instead -- the table-header hides and the two rows read back as
  plain list-row text. A fresh (with-meta [1] {:doc \"m\"}), opened, then m
  (:meta) shows *1 / ^meta (toggle-meta's own stack push, jonquil.inspect.
  view/toggle-meta)."
  [ui]
  (go-row :row-inspect-table-meta support/check
    (let [pane (:inspector ui)
          [submitted1? _] (submit-repl! ui "[{:id 1 :name \"a\"} {:id 2 :name \"b\"}]" 3000)]
      (on-gtk ((:inspect-last!* ui)))
      (let [table-shown? (await-until 2000 20
                                      #(pos? (g/gtk-widget-get-visible (:table-header pane))))]
        (on-gtk (inspector-pane/dispatch-key! pane KEY-V))
        (let [list-shown? (await-until 2000 20
                                       #(zero? (g/gtk-widget-get-visible (:table-header pane))))
              lines (on-gtk (str/split-lines (g/buffer-text (:buf pane))))
              [submitted2? _] (submit-repl! ui "(with-meta [1] {:doc \"m\"})" 3000)]
          (on-gtk ((:inspect-last!* ui)))
                ;; Same staleness trap as row-inspect-page-edge: the panel
                ;; is already visible and the header stays "*1" either way
                ;; (a fresh :start always resets to the root frame), so
                ;; neither tells "this fresh :start reply landed" apart
                ;; from "still showing the last value" -- wait for the info
                ;; line to name THIS value's own type+count instead.
          (let [opened2? (await-until 2000 20
                                      #(str/includes? (g/label-text (:info pane))
                                                      "PersistentVector · 1 items"))]
            (on-gtk (inspector-pane/dispatch-key! pane KEY-M))
            (let [meta-shown? (await-until 2000 20
                                           #(= "*1 / ^meta" (g/label-text (:header pane))))]
              (support/check :row-inspect-table-meta
                             (and submitted1? table-shown? list-shown? (= 2 (count lines))
                                  submitted2? opened2? meta-shown?)
                             {:table-shown table-shown? :list-shown list-shown? :lines lines
                              :submitted2 submitted2? :opened2 opened2? :meta-shown meta-shown?}))))))))

(defn row-inspect-def
  "A REPL entry {:k 42}, open (a single-entry map whose one child, 42, is
  neither a map nor a vector, so this one really does render as list --
  the row :k · 42), go in (idx 0, landing on the leaf 42), d opens the def
  prompt. Typing \"answer\" into the prompt's entry (inserting text into
  the entry, the same gtk-editable-set-text idiom rename/find-bar rows use)
  and Return -- mirroring inspector-pane's own \"activate\" handler
  (jonquil.gtk.inspector-pane/make-pane's def-entry wiring: trim, hide,
  call on-def when non-blank), driven here through the pane's own exposed
  :on-def rather than a synthesized keystroke into a live popover -- sends
  :def {:name \"answer\"}: the console shows \"; Defined #'user/answer\",
  and answer evaluates to 42 at the prompt."
  [ui]
  (go-row :row-inspect-def support/check
    (let [pane (:inspector ui)
          [submitted1? _] (submit-repl! ui "{:k 42}" 3000)]
      (on-gtk ((:inspect-last!* ui)))
            ;; row-inspect-table-meta leaves the panel on "*1 / ^meta" --
            ;; a DIFFERENT header than a fresh :start's own plain "*1",
            ;; and (unlike that row's own info line, coincidentally also
            ;; "PersistentArrayMap · 1 items" for the ^meta frame's
            ;; {:doc "m"}) not a string this value's own info line can
            ;; collide with -- so the exact header match is the reliable
            ;; "this fresh reply landed" signal here, not a staleness trap.
      (let [opened? (await-until 2000 20 #(= "*1" (g/label-text (:header pane))))]
        (on-gtk (inspector-pane/dispatch-key! pane KEY-RETURN))
        (let [in? (await-until 2000 20 #(str/includes? (g/label-text (:header pane)) "/ :k"))
              mark (on-gtk (support/out-end-offset ui))]
          (on-gtk (inspector-pane/dispatch-key! pane KEY-D))
          (let [dp (:def-popover pane)]
            (on-gtk (g/gtk-editable-set-text (:entry dp) "answer"))
            (on-gtk (let [name (str/trim (or (g/editable-text (:entry dp)) ""))]
                      (popover/hide! dp)
                      (when (seq name) ((:on-def pane) name))))
            (let [defined? (await-until 2000 20
                                        #(str/includes? (support/since ui mark) "; Defined #'user/answer"))
                  [submitted2? mark2] (submit-repl! ui "answer" 3000)]
              (on-gtk
               (support/check :row-inspect-def
                              (and submitted1? opened? in? defined? submitted2?
                                   (str/includes? (support/since ui mark2) "=> 42"))
                              {:opened opened? :in in? :defined defined?
                               :console (support/since ui mark)
                               :submitted2 submitted2?
                               :answer-console (support/since ui mark2)})))))))))

(defn row-inspect-def-keeps-selection
  "Minor: on-inspector-def sets :pending :keep before sending :def, so a
  successful def leaves the selected row exactly where it was instead
  of resetting to 0 (inspect.clj's on-reply default). A fresh REPL
  entry with three rows, Down once to select row 1, d + a name: the
  view is unchanged (still three rows) and row 1, not row 0, keeps the
  selected tag afterward.

  A flat 3-entry MAP, not a vector: row-inspect-form's own opened? check
  right after this row looks for a PersistentVector of 3 items
  (jonquil.gtk.window's own comment on row-inspect-page-edge/row-
  inspect-table-meta names this staleness trap -- \"this fresh reply
  landed\" vs. \"still showing the last value\" -- and a 3-item vector
  left here would satisfy that check on the OLD reply, before its own
  real one lands). PersistentArrayMap never collides with it."
  [ui]
  (go-row :row-inspect-def-keeps-selection support/check
    (let [pane (:inspector ui)
          [submitted? _] (submit-repl! ui "{:a 1 :b 2 :c 3}" 3000)]
      (on-gtk ((:inspect-last!* ui)))
      (let [opened? (await-until 2000 20
                                 #(and (= "*1" (g/label-text (:header pane)))
                                       (str/includes? (g/label-text (:info pane))
                                                      "PersistentArrayMap · 3 items")))]
        (on-gtk (inspector-pane/dispatch-key! pane KEY-DOWN))
        (let [lines (on-gtk (str/split-lines (g/buffer-text (:buf pane))))
              line1-start (inc (count (first lines)))
              tag1-before? (on-gtk (g/has-tag-at? (:buf pane) line1-start (:selected-tag pane)))
              mark (on-gtk (support/out-end-offset ui))
              dp (:def-popover pane)]
          (on-gtk (inspector-pane/dispatch-key! pane KEY-D)
                  (g/gtk-editable-set-text (:entry dp) "row-def-demo")
                  (let [name (str/trim (or (g/editable-text (:entry dp)) ""))]
                    (popover/hide! dp)
                    (when (seq name) ((:on-def pane) name))))
          (let [defined? (await-until 2000 20
                                      #(str/includes? (support/since ui mark) "; Defined #'user/row-def-demo"))]
            (on-gtk
             (let [tag1-after? (g/has-tag-at? (:buf pane) line1-start (:selected-tag pane))
                   tag0-after? (g/has-tag-at? (:buf pane) 0 (:selected-tag pane))]
               (support/check :row-inspect-def-keeps-selection
                              (and submitted? opened? tag1-before? defined? tag1-after? (not tag0-after?))
                              {:submitted submitted? :opened opened? :tag1-before tag1-before?
                               :defined defined? :tag1-after tag1-after? :tag0-after tag0-after?})))))))))

(defn row-inspect-form
  "Editor text (vec (range 3)) with the caret inside, :inspect-form!*:
  do-run-form!'s own flash+echo+eval, and an :ok result opens the inspector
  on the freshly Run value -- the panel shows \"PersistentVector · 3
  items\" (jonquil.inspect.view/render's own :info)."
  [ui]
  (go-row :row-inspect-form support/check
    (let [pane (:inspector ui)
          ed (:editor ui)
          text "(vec (range 3))"]
      (on-gtk (editor/set-text! ed text)
              (support/place-cursor! (:buf ed) 5)
              ((:inspect-form!* ui)))
      (let [ok? (await-until 3000 20
                             #(and (pos? (g/gtk-widget-get-visible (:root pane)))
                                   (= "*1" (g/label-text (:header pane)))
                                   (str/includes? (g/label-text (:info pane)) "PersistentVector · 3 items")))]
        (on-gtk
         (support/check :row-inspect-form ok?
                        {:header (g/label-text (:header pane)) :info (g/label-text (:info pane))}))))))

(defn row-inspect-escape
  "With the pane focused, the ui's own key handler with Escape hides it and
  the editor has focus."
  [ui]
  (go-row :row-inspect-escape support/check
    (let [pane (:inspector ui) win (:window ui) ed (:editor ui)]
      (on-gtk (inspector-pane/focus! pane))
      (let [handled (on-gtk ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil))
            hidden? (await-until 2000 20 #(zero? (g/gtk-widget-get-visible (:root pane))))
            ed-focused? (on-gtk (= (g/gtk-root-get-focus win) (:view ed)))]
        (on-gtk
         (support/check :row-inspect-escape
                        (and (= 1 handled) hidden? ed-focused?)
                        {:handled handled :hidden hidden? :ed-focused ed-focused?}))))))

(defn row-inspect-cols-match
  "Reopens the panel (closed by row-inspect-escape); the :cols the last
  :start sent (:inspector-last-args*) equals the pane's own live measure.
  It measures only once GTK has laid the reopened panel out (its root's
  own width is positive again: hiding a widget zeroes its allocation,
  though not its children's), because before that the rows view still
  reports the width it had before the close, and a measure taken then
  agrees with a stale :start by accident."
  [ui]
  (go-row :row-inspect-cols-match support/check
    (let [pane (:inspector ui)]
      (on-gtk ((:inspect-last!* ui)))
      (let [opened? (await-until 2000 20 #(pos? (g/gtk-widget-get-visible (:root pane))))
            laid-out? (await-until 2000 20 #(pos? (g/gtk-widget-get-width (:root pane))))
            snapshot (fn [] {:sent-cols (:cols ((:inspector-last-args* ui)))
                             :measured-cols (:cols (inspector-pane/measure pane))})
            matched? (await-until 2000 20 #(let [{:keys [sent-cols measured-cols]} (snapshot)]
                                             (= sent-cols measured-cols)))]
        (on-gtk
         (let [{:keys [sent-cols measured-cols]} (snapshot)]
           (support/check :row-inspect-cols-match
                          (and opened? laid-out? matched?)
                          {:sent-cols sent-cols :measured-cols measured-cols
                           :opened opened? :laid-out laid-out?})))))))

(defn row-inspect-reopen-reuses-callback
  "Closing the panel and reopening it, twice, through Inspect Last makes
  no new foreign callable: each reopen waits for GTK's layout pass
  (measure-when-ready!), and jonquil.gtk.callbacks retains every callable
  for the process's life, so a fresh one per wait would leak. Leaves the
  panel open, as row-inspect-cols-match did."
  [ui]
  (go-row :row-inspect-reopen-reuses-callback support/check
    (let [pane (:inspector ui)
          before (count @callbacks/retained)
          cycle! (fn []
                   (on-gtk (inspector-pane/focus! pane)
                           ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil))
                   (let [hidden? (await-until 2000 20 #(zero? (g/gtk-widget-get-visible (:root pane))))
                         old-args ((:inspector-last-args* ui))
                         ;; armed on the same GTK turn: the reopen took the deferred path
                         deferred? (on-gtk ((:inspect-last!* ui)) @(:ready-armed? pane))
                         laid-out? (await-until 2000 20 #(pos? (g/gtk-widget-get-width (:root pane))))
                         ;; the wait's callback has run once :start's args are new
                         settled? (await-until 2000 20 #(not (identical? old-args ((:inspector-last-args* ui)))))]
                     (and hidden? deferred? laid-out? settled?)))
          ok1 (cycle!)
          ok2 (cycle!)
          after (count @callbacks/retained)]
      (on-gtk
       (support/check :row-inspect-reopen-reuses-callback
                      (and ok1 ok2 (= before after))
                      {:cycles [ok1 ok2] :retained-before before :retained-after after})))))

(defn row-inspect-def-prompt-escape-safe
  "The def prompt joins the window's own close-popovers registry, so
  Escape closes it before it can fall through to Stop,
  instead of Stopping a running program the way an unregistered popover
  would. A program running ((Thread/sleep 5000) via Run), the def prompt
  open (continuing from row-inspect-cols-match's own open panel):
  Escape must close the prompt and leave the program running, then Stop
  ends it cleanly. The rows view has keyboard focus again once the prompt
  has closed, not the inspector's scrolled window (the in-handler grab GTK
  undid, found with the rename popover)."
  [ui]
  (go-row :row-inspect-def-prompt-escape-safe support/check
    (let [pane (:inspector ui) cv (:console ui) dp (:def-popover pane)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(Thread/sleep 5000)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          running? (await-until 2000 20 #(not (support/idle? ui)))]
      (on-gtk (inspector-pane/dispatch-key! pane KEY-D))
      (let [prompt-open? (await-until 2000 20 #(popover/open? dp))
            handled (on-gtk ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil))
            prompt-closed? (await-until 2000 20 #(not (popover/open? dp)))
            still-running? (not (support/idle? ui))
            view-focused? (await-until 2000 20 #(= (:view pane) (g/gtk-root-get-focus (:window ui))))]
        (on-gtk ((:stop!* ui)))
        (let [stopped? (await-until 3000 20 #(support/idle? ui))]
          (support/check :row-inspect-def-prompt-escape-safe
                         (and running? prompt-open? (= 1 handled) prompt-closed?
                              still-running? stopped? view-focused?)
                         {:running running? :prompt-open prompt-open? :handled handled
                          :prompt-closed prompt-closed? :still-running still-running?
                          :view-focused view-focused?
                          :stopped stopped? :text (support/since ui mark)}))))))

;; The "ev* swap hides the panel" minor (apply-selection!, window.clj) has
;; no row here: apply-selection!'s evaluator-swap branch always builds a
;; BRAND NEW evaluator via (ev/make sel), even when the newly selected
;; profile is Jolt in-process again -- there's no way back to the exact
;; instance the window started with. gtk-selftest-window.support/idle?
;; (and so every row anywhere in this whole suite that calls it) reads
;; (:ctx (:session ui)), a FIXED reference to THAT original instance
;; captured once at window construction, never updated after a swap. A
;; row here that opened a different dialect's file and then "restored"
;; the default one would leave every later row's own idle?/support/check
;; reading a session nothing runs against any more -- measured: it broke
;; window-keys/row-command-chord-close's own started? check two groups
;; later. The fix itself (window.clj's apply-selection!, two lines: hide
;; the root, reset inspector-state to nil) is verified by reading, not by
;; a row in this shared harness.
