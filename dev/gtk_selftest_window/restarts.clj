(ns gtk-selftest-window.restarts
  "Restarts for the last failed evaluation, in the real window: Retry runs
  it again, Edit puts it at the prompt, Define puts (def NAME ) before it,
  and a failed Run is edited in its tab."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]))

(defn- run-palette!
  "Opens the palette, filters it to query and runs the first entry, then
  closes it. GTK-thread only."
  [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn- submit!
  "Types code at the console prompt and presses Return (the inspector rows'
  path), then waits for the evaluation to end and for want in what it
  printed. [ok mark]."
  [ui code want]
  (let [cv (:console ui)
        mark (on-gtk (let [m (support/out-end-offset ui)]
                       (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
                       (cview/handle-return! cv false)
                       m))
        ok (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) want)))]
    [ok mark]))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- input-caret
  "The caret's offset from the start of the console input."
  [ui]
  (let [cv (:console ui)]
    (- (g/cursor-offset (:buf cv)) (- (g/gtk-text-buffer-get-char-count (:buf cv)) (count (cview/input-text cv))))))

(defn row-restart-retry
  "A REPL entry that fails once: Retry shows its value; with nothing failed
  since, Retry says so."
  [ui]
  (go-row :restart-retry support/check
    (let [_ (submit! ui "(def tries (atom 0))" "#'user/tries")
          [failed? _] (submit! ui "(do (swap! tries inc) (when (< @tries 2) (throw (ex-info \"once\" {}))) :ok)" "once")
          mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (run-palette! ui "retry the last failed"))
          retried? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "=> :ok")))
          _ (on-gtk (run-palette! ui "retry the last failed"))
          nothing? (await-until 3000 20 #(= "; Nothing has failed since the last success." (status ui)))]
      (on-gtk (support/check :restart-retry (and failed? retried? nothing?)
                             {:failed failed? :retried retried? :nothing nothing? :status (status ui)})))))

(defn row-restart-edit
  "A failed REPL entry: Edit puts its code at the prompt."
  [ui]
  (go-row :restart-edit support/check
    (let [cv (:console ui)
          [failed? _] (submit! ui "(+ 1 :a)" "Error")
          _ (on-gtk (run-palette! ui "edit the last failed"))
          input (on-gtk (cview/input-text cv))]
      (on-gtk (cview/clear-input! cv)
              (support/check :restart-edit (and failed? (= "(+ 1 :a)" input)) {:failed failed? :input input})))))

(defn row-restart-define
  "An unresolved name: Define puts (def NAME ) before the code with the
  caret where the value goes, and Return with a value defines and retries."
  [ui]
  (go-row :restart-define support/check
    (let [cv (:console ui)
          [failed? _] (submit! ui "(undefined-xyz 41)" "Unable to resolve symbol")
          _ (on-gtk (run-palette! ui "define the name"))
          input (on-gtk (cview/input-text cv))
          caret (on-gtk (input-caret ui))
          mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "inc" -1 1)
                    (cview/handle-return! cv false))
          retried? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "=> 42")))]
      (on-gtk (support/check :restart-define
                             (and failed? (= "(def undefined-xyz )\n(undefined-xyz 41)" input) (= 19 caret) retried?)
                             {:failed failed? :input input :caret caret :retried retried?})))))

(defn row-restart-run-edit
  "A failed Run is edited in its tab, not at the prompt."
  [ui]
  (go-row :restart-run-edit support/check
    (let [[ran? _] (support/run-and-await ui "(/ 1 0)" 5000 #(str/includes? % "Divide by zero"))
          _ (on-gtk (run-palette! ui "edit the last failed"))
          said? (await-until 3000 20 #(= "; Edit the file and Run it again." (status ui)))]
      (on-gtk (support/check :restart-run-edit (and ran? said?) {:ran ran? :said said? :status (status ui)})))))
