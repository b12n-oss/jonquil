(ns gtk-selftest-window.palette
  "The command palette rows: Cmd/Ctrl+Shift+P opens it through the
  window's key handler, the query filters it, run-selected! runs the
  picked command with its scope's ctx, Escape closes it, and a throw from
  a run is contained. Driven through ui fns and the palette's own fns,
  never synthesized OS keystrokes."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.command-registry :as registry]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]
            [jonquil.keymap :as km]
            [jonquil.palette :as pal-data]))

(def ^:private KEY-P 0x070)
(def ^:private KEY-T 0x074)
(def ^:private KEY-W 0x077)
(def ^:private KEY-F5 0xffc2)

(defn- open-by-chord!
  "Cmd/Ctrl+Shift+P through the window's own key handler, as
  window_keys.clj's rows send a window chord."
  [ui]
  ((:handle-window-key! ui) ffi/null KEY-P 0 (bit-or g/META-MASK g/SHIFT-MASK) ffi/null))

(defn- focus-in? [ui widget]
  (let [f (g/gtk-root-get-focus (:window ui))]
    (and (not (ffi/null? f)) (not (zero? (g/gtk-widget-is-ancestor f widget))))))

(defn- editor-focused? [ui]
  (let [f (g/gtk-root-get-focus (:window ui))]
    (= f (:view (:editor ui)))))

(defn- close! [ui]
  (let [p (:palette ui)] (when (popover/open? p) (popover/hide! p))))

(defn row-palette-entry-keeps-window-keys
  "With focus in the palette's entry, the window's capture-phase key
  handler leaves Cmd/Ctrl+T, Cmd/Ctrl+W and F5 to the entry (0: no new or
  closed tab, no Run), as it does for the rename popover's entry
  (rename-menus/row-rename-entry-keeps-window-keys); Escape still reaches
  the popover clause and closes it (1)."
  [ui]
  (go-row :palette-entry-keeps-window-keys support/check
    (let [p (:palette ui)
          tabs0 (on-gtk ((:open-palette!* ui)) (count ((:tabs* ui))))
          focused? (await-until 2000 20 #(focus-in? ui (:popover p)))
          [ht hw hf5 tabs1 open?] (on-gtk [((:handle-window-key! ui) ffi/null KEY-T 0 g/META-MASK ffi/null)
                                           ((:handle-window-key! ui) ffi/null KEY-W 0 g/META-MASK ffi/null)
                                           ((:handle-window-key! ui) ffi/null KEY-F5 0 0 ffi/null)
                                           (count ((:tabs* ui)))
                                           (popover/open? p)])
          hesc (on-gtk ((:handle-window-key! ui) ffi/null support/KEY-ESCAPE 0 0 ffi/null))
          closed? (await-until 1000 20 #(not (popover/open? p)))]
      (on-gtk (close! ui)
              (support/check :palette-entry-keeps-window-keys
                             (and focused? (= 0 ht hw hf5) (= tabs0 tabs1) open? (= 1 hesc) closed?)
                             {:focused focused? :cmd-t ht :cmd-w hw :f5 hf5 :tabs [tabs0 tabs1]
                              :open open? :escape hesc :closed closed?})))))

(defn row-palette-chord-opens
  "The chord opens the palette with every entry shown and the entry focused."
  [ui]
  (go-row :palette-chord-opens support/check
    (let [p (:palette ui)
          handled (on-gtk (open-by-chord! ui))
          focused? (await-until 2000 20 #(focus-in? ui (:popover p)))
          [open? shown] (on-gtk [(popover/open? p) (count (palette/shown-docs p))])
          expected (count (pal-data/entries {:commands registry/commands :keymap km/shipped
                                             :os (System/getProperty "os.name")}))]
      (on-gtk (close! ui)
              (support/check :palette-chord-opens
                             (and (= 1 handled) open? focused? (= expected shown) (pos? shown))
                             {:handled handled :open open? :focused focused? :shown shown
                              :expected expected})))))

(defn row-palette-filters
  "The query narrows the list to the entries every word matches."
  [ui]
  (go-row :palette-filters support/check
    (on-gtk (let [p (:palette ui)]
              ((:open-palette!* ui))
              (palette/set-query! p "comment")
              (let [docs (palette/shown-docs p)]
                (close! ui)
                (support/check :palette-filters (= ["Comment or uncomment the lines."] docs)
                               {:docs docs}))))))

(defn row-palette-runs-editor-command
  "Running the picked editor command acts on the active tab's buffer, with
  the palette closed first and the editor focused again."
  [ui]
  (go-row :palette-runs-editor-command support/check
    (let [ed (:editor ui) p (:palette ui)
          text (on-gtk (editor/set-text! ed "(+ 1 2)")
                       (support/place-cursor! (:buf ed) 0)
                       ((:open-palette!* ui))
                       (palette/set-query! p "comment")
                       (palette/run-selected! p)
                       (editor/editor-text ed))
          open? (on-gtk (popover/open? p))
          focused? (await-until 2000 20 #(editor-focused? ui))]
      (on-gtk (close! ui)
              (support/check :palette-runs-editor-command
                             (and (= "; (+ 1 2)" text) (not open?) focused?)
                             {:text text :open open? :focused focused?})))))

(defn row-palette-runs-popover-command
  "A command that opens a popover of its own (rename) keeps it open: the
  palette hides before it runs, so its close can't take the new popover
  with it."
  [ui]
  (go-row :palette-runs-popover-command support/check
    (let [ed (:editor ui) p (:palette ui) rp (:rename-popover ui)
          _ (on-gtk (editor/set-text! ed "(defn greet [n] n)")
                    (support/place-cursor! (:buf ed) 7)
                    ((:open-palette!* ui))
                    (palette/set-query! p "rename the symbol")
                    (palette/run-selected! p))
          open? (await-until 1000 20 #(popover/open? rp))
          ;; let posted focus grabs run, then the rename entry must still
          ;; hold the focus its own show-rename! gave it
          _ (await-until 300 20 (constantly false))
          entry-focused? (on-gtk (focus-in? ui (:popover rp)))
          palette-open? (on-gtk (popover/open? p))]
      (on-gtk (when (popover/open? rp) (popover/hide! rp))
              (close! ui)
              (support/check :palette-runs-popover-command (and open? entry-focused? (not palette-open?))
                             {:rename-open open? :entry-focused entry-focused? :palette-open palette-open?})))))

(defn row-palette-runs-window-command
  "A window command runs with the window's ctx: Clear the console empties
  it as Cmd/Ctrl+L does."
  [ui]
  (go-row :palette-runs-window-command support/check
    (let [cv (:console ui) p (:palette ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 2 2)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          produced? (await-until 3000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "=> 4")))
          text (on-gtk ((:open-palette!* ui))
                       (palette/set-query! p "clear the console")
                       (palette/run-selected! p)
                       (support/console-text ui))]
      (on-gtk (close! ui)
              (support/check :palette-runs-window-command
                             (and produced? (= "user=> " text))
                             {:produced produced? :text text})))))

(defn row-palette-escape-closes
  "Escape through the window's key handler closes the palette, leaves the
  buffer alone and gives the editor focus back."
  [ui]
  (go-row :palette-escape-closes support/check
    (let [ed (:editor ui) p (:palette ui)
          _ (on-gtk (editor/set-text! ed "(+ 1 2)")
                    ((:open-palette!* ui)))
          _ (await-until 2000 20 #(focus-in? ui (:popover p)))
          handled (on-gtk ((:handle-window-key! ui) ffi/null support/KEY-ESCAPE 0 0 ffi/null))
          closed? (await-until 1000 20 #(not (popover/open? p)))
          focused? (await-until 2000 20 #(editor-focused? ui))
          text (on-gtk (editor/editor-text ed))]
      (on-gtk (close! ui)
              (support/check :palette-escape-closes
                             (and (= 1 handled) closed? focused? (= "(+ 1 2)" text))
                             {:handled handled :closed closed? :focused focused? :text text})))))

(defn row-palette-no-match
  "A query matching nothing shows no rows; running then does nothing and
  the palette stays open."
  [ui]
  (go-row :palette-no-match support/check
    (on-gtk (let [ed (:editor ui) p (:palette ui)]
              (editor/set-text! ed "(+ 1 2)")
              ((:open-palette!* ui))
              (palette/set-query! p "zzzz")
              (let [docs (palette/shown-docs p)]
                (palette/run-selected! p)
                (let [open? (popover/open? p) text (editor/editor-text ed)]
                  (close! ui)
                  (support/check :palette-no-match
                                 (and (= [] docs) open? (= "(+ 1 2)" text))
                                 {:docs docs :open open? :text text})))))))

(defn row-palette-throw-contained
  "A command that throws from the palette is reported in the status bar as
  \"; palette: ...\", the buffer is untouched and the process lives."
  [ui]
  (go-row :palette-throw-contained support/check
    (let [ed (:editor ui) p (:palette ui) status (:status-label ui)
          text (on-gtk (editor/set-text! ed "(+ 1 2)")
                       (support/place-cursor! (:buf ed) 0)
                       ((:open-palette!* ui))
                       (palette/set-query! p "comment")
                       (with-redefs [editor/do-toggle-comment! (fn [& _] (throw (ex-info "forced palette failure" {})))]
                         (palette/run-selected! p))
                       (editor/editor-text ed))
          status-ok? (await-until 1000 20 #(str/includes? (g/label-text status) "; palette: forced palette failure"))]
      (on-gtk (close! ui)
              (support/check :palette-throw-contained (and status-ok? (= "(+ 1 2)" text))
                             {:status (g/label-text status) :text text})))))

(defn row-palette-closes-its-own-tab
  "Running Close the tab from a second tab's palette frees
  that tab's editor view while the palette's posted refocus is still
  queued. The refocus must hold its own ref on the view and skip a view
  that's no longer in a window: the process lives, the first tab is left,
  and no GTK warning is printed for the dead view (the selftest's output
  is the witness for that part)."
  [ui]
  (go-row :palette-closes-its-own-tab support/check
    (let [n0 (on-gtk (count ((:tabs* ui))))
          _ (on-gtk ((:new-tab!* ui))
                    (let [t ((:active-tab* ui)) p (:palette t)]
                      ((:open-palette!* ui))
                      (palette/set-query! p "close the tab")
                      (palette/run-selected! p)))
          settled? (await-until 2000 20 #(= n0 (count ((:tabs* ui)))))
          _ (await-until 300 20 (constantly false))
          n1 (on-gtk (count ((:tabs* ui))))]
      (on-gtk (support/check :palette-closes-its-own-tab (and settled? (= n0 n1))
                             {:before n0 :after n1})))))

(defn row-palette-arrow-keys
  "Up and Down, through the entry controller's own handler,
  move the selection and are taken (1); another key isn't (0), and
  neither is an arrow while an IME preedit is active. A
  composition the palette was closed over (Escape, a click outside)
  doesn't outlive it: open! starts with no preedit."
  [ui]
  (go-row :palette-arrow-keys support/check
    (on-gtk (let [p (:palette ui)]
              (try
                ((:open-palette!* ui))
                (let [down (palette/handle-key! p 0xff54)
                      i1 (:index @(:state p))
                      up (palette/handle-key! p 0xff52)
                      i0 (:index @(:state p))
                      other (palette/handle-key! p 0x061)
                      ;; while an IME composes, the arrows are its (candidate
                      ;; lists): 0, and the selection stays put
                      _ (reset! (:preedit-active? p) true)
                      composing (palette/handle-key! p 0xff54)
                      i2 (:index @(:state p))
                      ;; closed mid-composition, then opened again
                      _ (close! ui)
                      _ ((:open-palette!* ui))
                      reopened (palette/handle-key! p 0xff54)]
                  (support/check :palette-arrow-keys
                                 (and (= 1 down up) (= 1 i1) (= 0 i0) (= 0 other) (= 0 composing) (= 0 i2)
                                      (= 1 reopened))
                                 {:down down :up up :i1 i1 :i0 i0 :other other :composing composing
                                  :i2 i2 :reopened reopened}))
                (finally
                  (reset! (:preedit-active? p) false)
                  (close! ui)))))))

;; --- describing keys --------------------------------------------------------

(def ^:private KEY-L 0x06c)
(def ^:private KEY-A 0x061)
(def ^:private KEY-B 0x062)
(def ^:private KEY-SHIFT-L 0xffe1)
;; typing keys GDK names with words, from gdkkeysyms.h
(def ^:private KEY-PARENLEFT 0x028)
(def ^:private KEY-SPACE 0x020)
(def ^:private KEY-CYRILLIC-A 0x6c1)

(defn- run-palette!
  "Opens the palette, filters it to query and runs the first entry.
  GTK-thread only."
  [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)))

(defn- window-key! [ui keyval state]
  ((:handle-window-key! ui) ffi/null keyval 0 state ffi/null))

(defn row-describe-key-consumes
  "Describe Key's next key is described, never run: Cmd/Ctrl+L leaves the
  console uncleared and the description names :console/clear."
  [ui]
  (go-row :describe-key-consumes support/check
    (let [mark (on-gtk (support/out-end-offset ui))
          handled (on-gtk (run-palette! ui "describe the next key")
                          (window-key! ui KEY-L g/META-MASK))
          out (on-gtk (support/since ui mark))]
      (on-gtk (close! ui)
              (support/check :describe-key-consumes
                             (and (= 1 handled) (str/includes? out ":console/clear"))
                             {:handled handled :out out})))))

(defn row-describe-key-skips-modifier
  "A modifier pressed alone neither answers a pending Describe Key nor is
  described; the next real key (F6) is."
  [ui]
  (go-row :describe-key-skips-modifier support/check
    (let [mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (run-palette! ui "describe the next key")
                    (window-key! ui KEY-SHIFT-L 0)
                    (window-key! ui support/KEY-F6 0))
          out (on-gtk (support/since ui mark))]
      (on-gtk (close! ui)
              (support/check :describe-key-skips-modifier
                             (and (str/includes? out "; F6 runs") (str/includes? out ":focus/toggle")
                                  (not (str/includes? out "Shift_L")))
                             {:out out})))))

(defn row-key-bindings-lists
  "List Every Key Binding prints both scopes and the palette command under
  its own doc."
  [ui]
  (go-row :key-bindings-lists support/check
    (let [mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (run-palette! ui "list every key binding"))
          out (on-gtk (support/since ui mark))]
      (on-gtk (close! ui)
              (support/check :key-bindings-lists
                             (and (str/includes? out "; Window keys") (str/includes? out "; Editor keys")
                                  (str/includes? out "Shift+P  Show the command palette."))
                             {:start (subs out 0 (min 200 (count out)))})))))

(defn row-recent-keys-hides-typing
  "Recent keys counts typing without keeping it, word-named keys included
  (parenleft, space, Cyrillic_a), names the command that actually ran for
  F6, and says passed on for an Escape the window let go (nothing open).
  F6 goes first, so the typing can't merge into an earlier row's, and
  twice, so focus ends where it started."
  [ui]
  (go-row :recent-keys-hides-typing support/check
    (let [mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (window-key! ui support/KEY-F6 0)
                    (window-key! ui KEY-A 0)
                    (window-key! ui KEY-B 0)
                    (window-key! ui KEY-PARENLEFT 0)
                    (window-key! ui KEY-SPACE 0)
                    (window-key! ui KEY-CYRILLIC-A 0)
                    (window-key! ui support/KEY-ESCAPE 0)
                    (window-key! ui support/KEY-F6 0)
                    (run-palette! ui "show the keys you pressed last"))
          out (on-gtk (support/since ui mark))]
      (on-gtk (close! ui)
              (support/check :recent-keys-hides-typing
                             (and (str/includes? out "; typing (5 keys)") (str/includes? out "; F6  :focus/toggle")
                                  (str/includes? out "; Esc  passed on (editor or GTK)")
                                  (not (re-find #"(?m)^; [ab]  " out))
                                  (not-any? #(str/includes? out %) ["parenleft" "space" "Cyrillic"]))
                             {:out out})))))

(defn- answering-asker
  "Stands in for the timing alert: records each alert in asked and answers
  it with result at once, so no native dialog opens."
  [asked result]
  (fn [alert on-choice] (swap! asked conj alert) (on-choice result)))

(defn- time-palette!
  "Puts form in the editor, caret inside it, and runs Time the form from
  the palette with the timing alert answered by result. The console offset
  before it, and the alerts asked."
  [ui form result]
  (let [ed (:editor ui) asked (atom [])]
    (on-gtk (reset! (:time-ask* ui) (answering-asker asked result))
            (let [m (support/out-end-offset ui)]
              (editor/set-text! ed form)
              (support/place-cursor! (:buf ed) 1)
              (run-palette! ui "time the form")
              {:mark m :asked asked}))))

(defn- reset-timing-alert! [ui]
  (on-gtk (reset! (:time-ask* ui) nil) (reset! (:time-confirmed? ui) false)))

(defn row-time-form
  "Time the form at the caret asks first, naming side effects; Time It
  warns again in the console, prints its ; Time: line and leaves the REPL
  idle (in-process Jolt; about a second)."
  [ui]
  (go-row :time-form support/check
    (try
      (let [{:keys [mark asked]} (time-palette! ui "(+ 1 2)" {:index 0})
            timed? (await-until 6000 50 #(str/includes? (support/since ui mark) "; Time: "))
            idle? (await-until 3000 50 #(support/idle? ui))]
        (on-gtk (close! ui)
                (let [out (support/since ui mark)
                      warned? (str/includes? out "any side effect repeats too")
                      alert (first @asked)
                      asked? (and (= 1 (count @asked)) (str/includes? (:detail alert) "side effect"))]
                  (support/check :time-form (and timed? idle? warned? asked?)
                                 {:timed timed? :idle idle? :warned warned? :asked @asked :out out}))))
      (finally (reset-timing-alert! ui)))))

(defn row-time-form-cancel
  "Cancel (or dismissing the alert) times nothing: no ; Timing line and
  the REPL stays idle."
  [ui]
  (go-row :time-form-cancel support/check
    (try
      (let [a (time-palette! ui "(+ 1 2)" {:index 2})
            b (time-palette! ui "(+ 1 2)" {:error "dismissed"})
            idle? (await-until 3000 50 #(support/idle? ui))]
        (on-gtk (close! ui)
                (let [out (support/since ui (:mark a))]
                  (support/check :time-form-cancel
                                 (and idle? (not (str/includes? out "; Timing"))
                                      (= 1 (count @(:asked a))) (= 1 (count @(:asked b))))
                                 {:idle idle? :out out}))))
      (finally (reset-timing-alert! ui)))))

(defn row-time-form-alert-pending
  "While another alert is pending (a close alert, say), timing doesn't
  open a second one over it: the status bar says so, nothing is timed, and
  the other alert's guard stays taken. Each answer releases the guard
  timing took."
  [ui]
  (go-row :time-form-alert-pending support/check
    (try
      (let [_ (on-gtk (reset! (:alert-pending? ui) true))
            a (time-palette! ui "(+ 1 2)" {:index 2})
            said? (await-until 3000 50 #(= "Another alert is open." (g/label-text (:status-label ui))))
            still? (on-gtk @(:alert-pending? ui))
            _ (on-gtk (reset! (:alert-pending? ui) false))
            b (time-palette! ui "(+ 1 2)" {:index 2})
            released? (on-gtk (not @(:alert-pending? ui)))]
        (on-gtk (close! ui)
                (let [out (support/since ui (:mark a))]
                  (support/check :time-form-alert-pending
                                 (and said? still? released? (empty? @(:asked a)) (= 1 (count @(:asked b)))
                                      (not (str/includes? out "; Timing")))
                                 {:said said? :still still? :released released?
                                  :asked-a (count @(:asked a)) :asked-b (count @(:asked b)) :out out}))))
      (finally (on-gtk (reset! (:alert-pending? ui) false)) (reset-timing-alert! ui)))))

(defn row-time-form-dont-ask
  "Time It, Don't Ask Again times the form, and the next timing in this
  window starts without asking."
  [ui]
  (go-row :time-form-dont-ask support/check
    (try
      (let [a (time-palette! ui "(+ 1 2)" {:index 1})
            first? (await-until 6000 50 #(str/includes? (support/since ui (:mark a)) "; Time: "))
            _ (await-until 3000 50 #(support/idle? ui))
            b (time-palette! ui "(+ 2 3)" {:index 2})
            second? (await-until 6000 50 #(str/includes? (support/since ui (:mark b)) "; Time: "))
            idle? (await-until 3000 50 #(support/idle? ui))]
        (on-gtk (close! ui)
                (support/check :time-form-dont-ask
                               (and first? second? idle? (= 1 (count @(:asked a))) (empty? @(:asked b)))
                               {:first first? :second second? :idle idle?
                                :asked-a (count @(:asked a)) :asked-b (count @(:asked b))})))
      (finally (reset-timing-alert! ui)))))
