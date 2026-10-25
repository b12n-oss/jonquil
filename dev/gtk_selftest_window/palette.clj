(ns gtk-selftest-window.palette
  "Slice S8's command palette rows: Cmd/Ctrl+Shift+P opens it through the
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
