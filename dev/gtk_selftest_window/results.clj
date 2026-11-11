(ns gtk-selftest-window.results
  "Live console results (LM1): each result line keeps a handle to its
  value; a click inspects it, and the verbs act on the result at the
  caret. In process, so the values live in this process."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]))

(defn- run-palette! [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- console-caret! [ui offset]
  (let [buf (:buf (:console ui))]
    (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))))

(defn- enter!
  "Types code at the console prompt and presses Return; the out offset
  before it."
  [ui code]
  (on-gtk (let [cv (:console ui)
                m (support/out-end-offset ui)]
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
            (cview/handle-return! cv false)
            m)))

(defn- console-text [ui]
  (let [buf (:buf (:console ui))]
    (g/buffer-text buf)))

(defn- offset-in
  "An offset inside the last occurrence of needle in the console."
  [ui needle]
  (some-> (str/last-index-of (console-text ui) needle) (+ 3)))

(defn row-result-spans
  "Each result line answers its own ref; the prompt answers none; Clear
  Last Output drops the cleared line's span and keeps the earlier one."
  [ui]
  (go-row :result-spans support/check
    (let [cv (:console ui)
          m1 (enter! ui "(+ 1 2)")
          _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m1) "=> 3")))
          m2 (enter! ui "(str \"a\" \"b\")")
          _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m2) "=> \"ab\"")))
          r1 (on-gtk (cview/result-at cv (offset-in ui "=> 3")))
          r2 (on-gtk (cview/result-at cv (offset-in ui "=> \"ab\"")))
          at-prompt (on-gtk (cview/result-at cv (dec (g/gtk-text-buffer-get-char-count (:buf cv)))))
          _ (on-gtk (cview/clear-last-output! cv))
          r1-after (on-gtk (cview/result-at cv (offset-in ui "=> 3")))
          r2-gone? (on-gtk (nil? (offset-in ui "=> \"ab\"")))]
      (on-gtk (support/check :result-spans
                             (and (= :value (:kind r1)) (= :value (:kind r2)) (not= (:id r1) (:id r2))
                                  (nil? at-prompt) (= r1 r1-after) r2-gone?)
                             {:r1 r1 :r2 r2 :at-prompt at-prompt :r1-after r1-after :r2-gone r2-gone?})))))

(defn row-result-click-inspects
  "A click on a result line opens that value in the inspector, headed by
  its own line rather than *1."
  [ui]
  (go-row :result-click-inspects support/check
    (try
      (let [m (enter! ui "[1 2 3]")
            _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> [1 2 3]")))
            _ (on-gtk ((:result-click!* ui) (offset-in ui "=> [1 2 3]")))
            inspected? (await-until 3000 20 #(str/includes? (g/label-text (:info (:inspector ui))) "3 items"))
            header (on-gtk (g/label-text (:header (:inspector ui))))]
        (on-gtk (support/check :result-click-inspects (and inspected? (= "[1 2 3]" header))
                               {:inspected inspected? :header header :info (g/label-text (:info (:inspector ui)))})))
      (finally (on-gtk ((:close-inspector!* ui)))))))

(defn row-result-click-waits-double-click-time
  "A single click on a result waits out GTK's own double-click time, so a
  slower double-click the system still counts never opens the inspector
  first. With the time at 900 ms the inspector is still closed at 600 ms,
  then opens."
  [ui]
  (go-row :result-click-waits-double-click-time support/check
    (let [settings (on-gtk (g/gtk-settings-get-default))
          before (on-gtk (g/double-click-time settings))]
      (try
        (let [m (enter! ui "[4 5 6 7]")
              _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> [4 5 6 7]")))
              _ (on-gtk (g/set-int-property! settings "gtk-double-click-time" 900))
              read-back (on-gtk (g/double-click-time settings))
              _ (on-gtk ((:result-release!* ui) (offset-in ui "=> [4 5 6 7]")))
              _ (Thread/sleep 600)
              ;; the header: the info line still reads "3 items" from the last row
              opened? #(= "[4 5 6 7]" (g/label-text (:header (:inspector ui))))
              early? (on-gtk (opened?))
              inspected? (await-until 3000 20 opened?)]
          (on-gtk (support/check :result-click-waits-double-click-time
                                 (and (= 900 read-back) (not early?) inspected?)
                                 {:read-back read-back :early early? :inspected inspected?})))
        (finally (on-gtk (g/set-int-property! settings "gtk-double-click-time" before)
                         ((:close-inspector!* ui))))))))

(defn row-result-verbs
  "With the caret in a result line: Copy says so, Define puts a def of the
  live value at the prompt, Jump refuses a value that isn't a var, and the
  prompt holds no result. The palette finds the commands by their own
  text; the caret goes back to the prompt at the end."
  [ui]
  (go-row :result-verbs support/check
    (let [cv (:console ui)
          to-prompt! (fn [] (console-caret! ui (g/gtk-text-buffer-get-char-count (:buf cv))))]
      (try
        (let [m (enter! ui "(+ 40 2)")
              _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> 42")))
              at (offset-in ui "=> 42")
              ref (on-gtk (cview/result-at cv at))
              _ (on-gtk (console-caret! ui at) (run-palette! ui "copy the console result"))
              copied? (await-until 3000 20 #(= "; Copied the result." (status ui)))
              _ (on-gtk (console-caret! ui at) (run-palette! ui "jump to the definition of the var the console"))
              not-var? (await-until 3000 20 #(= "; That result isn't a var." (status ui)))
              _ (on-gtk (console-caret! ui at) (run-palette! ui "define a var holding the console result"))
              input (on-gtk (cview/input-text cv))
              defined? (= (str "(def  (jonquil.results-rt/value! " (:id ref) " " (pr-str (:epoch ref)) "))") input)
              _ (on-gtk (cview/clear-input! cv)
                        (to-prompt!)
                        (run-palette! ui "inspect the console result"))
              none? (await-until 3000 20 #(= "; No result here." (status ui)))]
          (on-gtk (support/check :result-verbs (and ref copied? not-var? defined? none?)
                                 {:ref ref :copied copied? :not-var not-var? :input input :none none? :status (status ui)})))
        (finally (on-gtk (cview/clear-input! cv) (to-prompt!)))))))

(defn row-result-menu-verbs
  "The console's own right-click menu items reach the result verbs: the
  same four outcomes as row-result-verbs, fired by menu action name."
  [ui]
  (go-row :result-menu-verbs support/check
    (let [cv (:console ui)
          fire! (:repl-activate-menu-action!* ui)
          to-prompt! (fn [] (console-caret! ui (g/gtk-text-buffer-get-char-count (:buf cv))))]
      (try
        (let [m (enter! ui "(+ 40 2)")
              _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> 42")))
              at (offset-in ui "=> 42")
              ref (on-gtk (cview/result-at cv at))
              _ (on-gtk (console-caret! ui at) (fire! "result-copy"))
              copied? (await-until 3000 20 #(= "; Copied the result." (status ui)))
              copied-status (on-gtk (status ui))
              _ (on-gtk (console-caret! ui at) (fire! "result-jump"))
              not-var? (await-until 3000 20 #(= "; That result isn't a var." (status ui)))
              not-var-status (on-gtk (status ui))
              _ (on-gtk (console-caret! ui at) (fire! "result-define"))
              input (on-gtk (cview/input-text cv))
              defined? (= (str "(def  (jonquil.results-rt/value! " (:id ref) " " (pr-str (:epoch ref)) "))") input)
              _ (on-gtk (cview/clear-input! cv)
                        (to-prompt!)
                        (fire! "result-inspect"))
              none? (await-until 3000 20 #(= "; No result here." (status ui)))
              none-status (on-gtk (status ui))]
          (on-gtk (support/check :result-menu-verbs (and ref copied? not-var? defined? none?)
                                 {:ref ref :copied copied? :copied-status copied-status
                                  :not-var not-var? :not-var-status not-var-status
                                  :input input :none none? :none-status none-status})))
        (finally (on-gtk (cview/clear-input! cv) (to-prompt!)))))))

(defn row-result-quick-doc
  "The quick-doc verb on a var result prints that var's doc in the console
  as comment lines; on a value it says the result isn't a var."
  [ui]
  (go-row :result-quick-doc support/check
    (let [cv (:console ui)
          fire! (:repl-activate-menu-action!* ui)
          to-prompt! (fn [] (console-caret! ui (g/gtk-text-buffer-get-char-count (:buf cv))))]
      (try
        (let [m (enter! ui "#'clojure.core/inc")
              _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> #'clojure.core/inc")))
              at (offset-in ui "=> #'clojure.core/inc")
              d (on-gtk (support/out-end-offset ui))
              _ (on-gtk (console-caret! ui at) (fire! "result-doc"))
              printed? (await-until 5000 20 #(re-find #"(?m)^; .*one greater than num" (support/since ui d)))
              m2 (enter! ui "(+ 40 2)")
              _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m2) "=> 42")))
              _ (on-gtk (console-caret! ui (offset-in ui "=> 42")) (fire! "result-doc"))
              not-var? (await-until 3000 20 #(= "; That result isn't a var." (status ui)))]
          (on-gtk (support/check :result-quick-doc (and printed? not-var?)
                                 {:printed printed? :not-var not-var? :since (support/since ui d)
                                  :status (status ui)})))
        (finally (on-gtk (cview/clear-input! cv) (to-prompt!)))))))

(defn- widget-xy
  "The console view's widget coordinates of offset's character."
  [ui offset]
  (let [{:keys [view buf]} (:console ui)
        loc (ffi/alloc 16) out (ffi/alloc 8)]
    (try
      (g/with-iters* buf offset offset (fn [it _] (g/gtk-text-view-get-iter-location view it loc)))
      (g/gtk-text-view-buffer-to-window-coords view g/TEXT-WINDOW-WIDGET
                                               (ffi/read loc :int 0) (ffi/read loc :int 4) out (+ out 4))
      [(+ 2.0 (ffi/read out :int 0)) (+ 2.0 (ffi/read out :int 4))]
      (finally (ffi/free loc) (ffi/free out)))))

(defn row-result-right-click-moves-caret
  "A right-click on a result line puts the caret there first, so the
  menu's verbs act on the line clicked, not the one the caret was on."
  [ui]
  (go-row :result-right-click-moves-caret support/check
    (let [cv (:console ui)]
      (try
        (let [m (enter! ui "(+ 100 1)")
              _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> 101")))
              m2 (enter! ui "(+ 200 2)")
              _ (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m2) "=> 202")))
              a (offset-in ui "=> 101")
              b (offset-in ui "=> 202")
              want (on-gtk (cview/result-at cv b))
              _ (on-gtk (console-caret! ui a))
              _ (on-gtk (let [[x y] (widget-xy ui b)] ((:console-right-press!* ui) x y)))
              got (on-gtk (cview/result-at cv (g/cursor-offset (:buf cv))))]
          (on-gtk (support/check :result-right-click-moves-caret (and want (= want got))
                                 {:want want :got got})))
        (finally (on-gtk (console-caret! ui (g/gtk-text-buffer-get-char-count (:buf cv)))))))))
